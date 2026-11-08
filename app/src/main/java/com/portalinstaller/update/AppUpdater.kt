/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Portal Toolkit's own wireless updates from GitHub Releases: checked daily and when the app opens;
 * a newer one shows a notification, a Home banner and the Settings row. Update downloads it in a
 * foreground worker, checks it against [OtaPolicy] and installs it with PackageInstaller.
 */
object AppUpdater {
    const val TAG = "ToolkitUpdate"
    const val ACTION_UPDATE = "com.portalinstaller.update.UPDATE"
    private const val CHECK_WORK = "toolkit-update-check"
    private const val INSTALL_WORK = "toolkit-update-install"
    private const val CHECK_INTERVAL_DAYS = 1L
    private const val APK_FILE = "toolkit-update.apk"

    private val mutable = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = mutable.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OtaHttp()

    /** Sent to "Install unknown apps": coming back with it on carries the update on. */
    @Volatile private var waitingForPermission = false

    fun installedCode(context: Context): Int =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()

    fun installedName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    /** The app opened: schedule the daily check, show a known update at once, then check GitHub. */
    fun onAppOpened(context: Context) {
        val app = context.applicationContext
        schedule(app)
        UpdateNotifier.cancelConfirm(app)
        if (!isBusy(mutable.value)) {
            known(app)?.let { manifest -> mutable.value = AppUpdateState.Available(manifest) }
                ?: apkFile(app).delete()
        }
        scope.launch { runCatching { check(app, quiet = true) } }
    }

    /** "Check for updates": runs outside the screen's lifetime and reports what it found. */
    fun checkInBackground(context: Context) {
        val app = context.applicationContext
        scope.launch { runCatching { check(app, quiet = false) } }
    }

    fun schedule(context: Context) {
        val work = PeriodicWorkRequestBuilder<UpdateCheckWorker>(CHECK_INTERVAL_DAYS, TimeUnit.DAYS)
            .setConstraints(network()).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(CHECK_WORK, ExistingPeriodicWorkPolicy.KEEP, work)
    }

    /** Asks GitHub; a newer version is remembered, shown and notified once. Never interrupts an update. */
    suspend fun check(context: Context, quiet: Boolean): OtaManifest? {
        if (!quiet) mutable.update { if (isBusy(it)) it else AppUpdateState.Checking }
        val store = UpdateStore(context)
        val manifest = try {
            fetch()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val reason = (error as? OtaException)?.message ?: OtaCopy.BAD_MANIFEST
            Log.w(TAG, "Update check failed: $reason")
            if (!quiet) mutable.update { if (isBusy(it)) it else AppUpdateState.Failed(null, reason) }
            else mutable.update { if (it == AppUpdateState.Checking) AppUpdateState.Idle else it }
            return null
        }
        store.saveCheckedAt(System.currentTimeMillis())
        return when (val offer = OtaPolicy.offer(manifest, context.packageName, installedCode(context), Build.VERSION.SDK_INT)) {
            Offer.UpToDate -> {
                store.saveLatest(null)
                mutable.update { if (isBusy(it)) it else AppUpdateState.UpToDate(store.lastCheckedAt()) }
                null
            }
            is Offer.Rejected -> {
                Log.w(TAG, "Update refused: ${offer.reason}")
                mutable.update { if (isBusy(it) || quiet && it !is AppUpdateState.Checking) it else AppUpdateState.Failed(null, offer.reason) }
                null
            }
            is Offer.Available -> {
                store.saveLatest(offer.manifest)
                mutable.update { if (isBusy(it) || it is AppUpdateState.NeedsPermission) it else AppUpdateState.Available(offer.manifest) }
                if (store.notifiedCode() < offer.manifest.versionCode) {
                    UpdateNotifier.available(context, offer.manifest)
                    store.saveNotified(offer.manifest.versionCode)
                }
                offer.manifest
            }
        }
    }

    /** Update (row, banner or notification). Asks for "Install unknown apps" first if needed, then installs in a worker. */
    fun start(context: Context) {
        val app = context.applicationContext
        if (isBusy(mutable.value)) return
        if (!app.packageManager.canRequestPackageInstalls()) {
            askForInstallPermission(context)
            return
        }
        val work = OneTimeWorkRequestBuilder<UpdateInstallWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(network()).build()
        WorkManager.getInstance(app).enqueueUniqueWork(INSTALL_WORK, ExistingWorkPolicy.KEEP, work)
    }

    /** Back in front: if we were waiting for the switch and it's now on, the update carries on by itself. */
    fun resumeIfAllowed(context: Context) {
        if (waitingForPermission && context.packageManager.canRequestPackageInstalls()) {
            waitingForPermission = false
            (mutable.value as? AppUpdateState.NeedsPermission)?.let { mutable.value = AppUpdateState.Available(it.manifest) }
            start(context)
        }
    }

    /** Download, verify (fingerprint, size, package, version, signer), install. False (with the state saying why) if not. */
    suspend fun install(context: Context): Boolean {
        val manifest = currentManifest(context) ?: check(context, quiet = false) ?: return false
        if (!context.packageManager.canRequestPackageInstalls()) {
            waitingForPermission = true
            mutable.value = AppUpdateState.NeedsPermission(manifest)
            return false
        }
        val apk = apkFile(context)
        return try {
            mutable.value = AppUpdateState.Downloading(manifest, 0)
            val downloaded = http.download(manifest.apkUrl, apk, manifest.size) { done ->
                mutable.value = AppUpdateState.Downloading(manifest, done)
            }
            mutable.value = AppUpdateState.Verifying(manifest)
            val problem = OtaPolicy.verifyDownload(manifest, downloaded.sha256, downloaded.size)
                ?: OtaPolicy.verifyArchive(manifest, ApkArchiveInspector.facts(context, apk))
            if (problem != null) throw OtaException(problem)
            mutable.value = AppUpdateState.Installing(manifest)
            UpdateInstallSession.commit(context, apk)
            true
        } catch (cancelled: CancellationException) {
            apk.delete()
            mutable.value = AppUpdateState.Available(manifest)
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Update failed: ${error.javaClass.simpleName}: ${error.message}")
            apk.delete()
            mutable.value = AppUpdateState.Failed(manifest, (error as? OtaException)?.message ?: OtaCopy.INSTALL_FAILED)
            false
        }
    }

    /** The installer said no: show why, keep the offer so Try again works. */
    internal fun failed(context: Context, reason: String) {
        mutable.value = AppUpdateState.Failed(currentManifest(context), reason)
        apkFile(context).delete()
    }

    private fun askForInstallPermission(context: Context) {
        val manifest = currentManifest(context) ?: return checkInBackground(context)
        waitingForPermission = true
        mutable.value = AppUpdateState.NeedsPermission(manifest)
        val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(settings) }.onFailure { Log.w(TAG, "Couldn't open Install unknown apps") }
    }

    private suspend fun fetch(): OtaManifest =
        when (val parsed = OtaManifest.parse(http.fetchText(OtaPolicy.FEED_URL, OtaPolicy.MAX_MANIFEST_BYTES))) {
            is ManifestParse.Valid -> parsed.manifest
            is ManifestParse.Invalid -> throw OtaException(parsed.reason)
        }

    /** The update being offered right now: from the state, else the last one GitHub offered, if still newer. */
    private fun currentManifest(context: Context): OtaManifest? = when (val s = mutable.value) {
        is AppUpdateState.Available -> s.manifest
        is AppUpdateState.Downloading -> s.manifest
        is AppUpdateState.Verifying -> s.manifest
        is AppUpdateState.Installing -> s.manifest
        is AppUpdateState.NeedsPermission -> s.manifest
        is AppUpdateState.Failed -> s.manifest
        else -> null
    } ?: known(context)

    private fun known(context: Context): OtaManifest? = UpdateStore(context).latest()?.takeIf {
        OtaPolicy.offer(it, context.packageName, installedCode(context), Build.VERSION.SDK_INT) is Offer.Available
    }

    private fun isBusy(state: AppUpdateState) = state is AppUpdateState.Downloading ||
        state is AppUpdateState.Verifying || state is AppUpdateState.Installing

    private fun apkFile(context: Context) = File(context.cacheDir, APK_FILE)

    private fun network() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
}
