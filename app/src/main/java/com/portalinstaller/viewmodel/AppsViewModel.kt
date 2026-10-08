package com.portalinstaller.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.portalinstaller.R
import com.portalinstaller.data.AppContainer
import com.portalinstaller.data.ContentUriHelper
import com.portalinstaller.data.InstalledApp
import com.portalinstaller.data.PortalAppInfo
import com.portalinstaller.data.AdbSession
import com.portalinstaller.data.store.AppProfile
import com.portalinstaller.data.store.HistoryEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed class InstallState {
    data object Idle : InstallState()
    data class Installing(val elapsedSeconds: Int = 0, val sizeBytes: Long = 0) : InstallState()
    data object Success : InstallState()
    data class Failed(val message: String, val cancelled: Boolean = false) : InstallState()
}

sealed class UninstallState {
    data object Idle : UninstallState()
    data class InProgress(val packageName: String) : UninstallState()
    data object Success : UninstallState()
    data class Failed(val message: String) : UninstallState()
}

/** Modul de sortare pentru lista de aplicatii Portal. */
enum class PortalAppsSort { NAME, SIZE }

/** Rezultatul unei actiuni rapide pe o aplicatie (force-stop/clear-data/launch). */
sealed class AppActionState {
    data object Idle : AppActionState()
    data class Done(val message: String) : AppActionState()
    data class Failed(val message: String) : AppActionState()
}

sealed class ProfileApplyState {
    data object Idle : ProfileApplyState()
    data class Applying(
        val profileName: String,
        val current: Int,
        val total: Int,
        val appName: String
    ) : ProfileApplyState()
    /**
     * Rezultatul final al aplicarii unui profil, cu detalierea pe fiecare
     * pachet: cele reusite si cele esuate (cu motivul).
     */
    data class Done(
        val profileName: String,
        val succeeded: List<String>,
        val failed: List<Pair<String, String>>,
        val cancelled: Boolean = false,
        val deviceLabel: String? = null,
        val sessionGeneration: Long? = null
    ) : ProfileApplyState()
    data class Failed(val message: String) : ProfileApplyState()
}

/**
 * Reduce o lista de rezultate (pachet -> reusit) la o stare [ProfileApplyState.Done].
 * Extras ca functie pura pentru a fi testabil fara conexiune ADB.
 */
internal fun foldProfileResults(
    results: List<Pair<String, Boolean>>,
    profileName: String = "",
    reasons: Map<String, String> = emptyMap(),
    plannedPackages: List<String> = emptyList(),
    cancelled: Boolean = false
): ProfileApplyState.Done {
    val succeeded = results.filter { it.second }.map { it.first }
    val failed = results.filter { !it.second }
        .map { it.first to (reasons[it.first] ?: "") }
    val unconfirmed = if (cancelled) plannedPackages.filterNot { pkg -> results.any { it.first == pkg } }
        .map { it to "Cancelled before an install result was confirmed; device work may still finish" } else emptyList()
    return ProfileApplyState.Done(profileName, succeeded, failed + unconfirmed, cancelled)
}

/**
 * Gestionează aplicațiile: lista instalată local, instalarea pe Portal
 * (din listă sau dintr-un APK ales), dezinstalarea, listarea aplicațiilor de
 * pe Portal și aplicarea profilurilor. Folosește conexiunea partajată din
 * [AppContainer.connectionHolder].
 */
class AppsViewModel(app: Application) : AndroidViewModel(app) {

    private val container = AppContainer.from(app)
    private val connectionManager = container.connectionManager
    private val holder = container.connectionHolder
    private val historyStore = container.historyStore

    private val _installState = MutableStateFlow<InstallState>(InstallState.Idle)
    val installState: StateFlow<InstallState> = _installState.asStateFlow()

    private val _uninstallState = MutableStateFlow<UninstallState>(UninstallState.Idle)
    val uninstallState: StateFlow<UninstallState> = _uninstallState.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledApp>> = _installedApps.asStateFlow()

    private val _selectedApkUri = MutableStateFlow<Uri?>(null)
    val selectedApkUri: StateFlow<Uri?> = _selectedApkUri.asStateFlow()

    private val _portalPackages = MutableStateFlow<List<String>>(emptyList())
    val portalPackages: StateFlow<List<String>> = _portalPackages.asStateFlow()

    private val _portalApps = MutableStateFlow<List<PortalAppInfo>>(emptyList())
    val portalApps: StateFlow<List<PortalAppInfo>> = _portalApps.asStateFlow()

    private val _isLoadingPortalPackages = MutableStateFlow(false)
    val isLoadingPortalPackages: StateFlow<Boolean> = _isLoadingPortalPackages.asStateFlow()

    private val _portalPackagesError = MutableStateFlow<String?>(null)
    val portalPackagesError: StateFlow<String?> = _portalPackagesError.asStateFlow()

    private val _profileApplyState = MutableStateFlow<ProfileApplyState>(ProfileApplyState.Idle)
    val profileApplyState: StateFlow<ProfileApplyState> = _profileApplyState.asStateFlow()

    private val _portalAppsSort = MutableStateFlow(PortalAppsSort.NAME)
    val portalAppsSort: StateFlow<PortalAppsSort> = _portalAppsSort.asStateFlow()

    private val _appActionState = MutableStateFlow<AppActionState>(AppActionState.Idle)
    val appActionState: StateFlow<AppActionState> = _appActionState.asStateFlow()

    // Retinem Job-urile operatiunilor lungi ca sa le putem anula din UI.
    private var installJob: Job? = null
    private var profileApplyJob: Job? = null
    // Incarcarea + imbogatirea listei Portal: o retinem ca sa anulam o rulare
    // veche cand pornim una noua sau cand conexiunea se schimba (altfel doua
    // pase scriu concurent in _portalApps).
    private var portalLoadJob: Job? = null
    private var activeInstallSession: AdbSession? = null
    @Volatile private var profileGeneration = 0L
    private val _profileResults = MutableStateFlow<ProfileApplyState.Done?>(null)
    val profileResults: StateFlow<ProfileApplyState.Done?> = _profileResults.asStateFlow()

    init {
        viewModelScope.launch {
            var previous = holder.capture()
            holder.session.collect { current ->
                if (current != previous) onDisconnected()
                previous = current
            }
        }
    }

    fun setPortalAppsSort(sort: PortalAppsSort) {
        _portalAppsSort.value = sort
        _portalApps.value = sortPortalApps(_portalApps.value, sort)
    }

    private fun sortPortalApps(apps: List<PortalAppInfo>, sort: PortalAppsSort): List<PortalAppInfo> =
        when (sort) {
            PortalAppsSort.NAME -> apps.sortedBy { (it.label ?: it.name).lowercase() }
            PortalAppsSort.SIZE -> apps.sortedByDescending { it.sizeBytes }
        }

    private suspend fun addHistory(description: String, success: Boolean) {
        historyStore.add(HistoryEntry(System.currentTimeMillis(), description, success))
    }

    fun loadInstalledApps(context: Context) {
        viewModelScope.launch {
            // PackageManager poate arunca (ex. TransactionTooLarge pe liste mari);
            // fara runCatching coroutina moare in tacere si lista ramane goala.
            runCatching { connectionManager.loadInstalledApps(context) }
                .onSuccess { _installedApps.value = it }
        }
    }

    fun setSelectedApkUri(uri: Uri?) {
        _selectedApkUri.value = uri
        if (uri != null) {
            _installState.value = InstallState.Idle
        }
    }

    fun installApp(context: Context, app: InstalledApp) {
        beginInstall(context, app.name, app.minSdkVersion, prepare = {
            listOf(File(app.sourceDir)) + app.splitSourceDirs.orEmpty().map(::File)
        })
    }

    fun installApkFromUri(context: Context, uri: Uri) {
        var temporary: File? = null
        beginInstall(context, "APK", null, prepare = {
            withContext(Dispatchers.IO) {
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException(context.getString(R.string.error_read_apk_failed))
                input.use {
                    val file = File.createTempFile("upload-", ".apk", context.cacheDir)
                    temporary = file
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            val count = it.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                    listOf(file)
                }
            }
        }, cleanup = { temporary?.delete(); Unit },
            labelProvider = { ContentUriHelper.getDisplayName(context, uri) ?: "APK" })
    }

    private fun beginInstall(
        context: Context, label: String, minSdk: Int?,
        prepare: suspend () -> List<File>, cleanup: () -> Unit = {},
        labelProvider: suspend () -> String = { label }
    ) {
        val session = holder.capture() ?: return
        if (installJob?.isCompleted == false) return
        if (profileApplyJob?.isCompleted == false) {
            _installState.value = InstallState.Failed(context.getString(R.string.profile_install_busy))
            return
        }
        activeInstallSession = session
        _installState.value = InstallState.Installing()
        installJob = holder.launchForSession(viewModelScope, session) {
            var ticker: Job? = null
            try {
                val actualLabel = labelProvider()
                val apks = prepare()
                holder.ensureCurrent(session)
                val totalSize = apks.sumOf { it.length() }
                var elapsed = 0
                _installState.value = InstallState.Installing(0, totalSize)
                ticker = launch {
                    while (isActive) {
                        delay(1000)
                        _installState.value = InstallState.Installing(++elapsed, totalSize)
                    }
                }
                val result = if (apks.size == 1) {
                    connectionManager.installApk(context, session.dadb, apks.single(), minSdk)
                } else connectionManager.installMultipleApks(context, session.dadb, apks, minSdk)
                ticker.cancel()
                holder.ensureCurrent(session)
                _installState.value = result.fold(
                    onSuccess = { InstallState.Success },
                    onFailure = { InstallState.Failed(it.message ?: context.getString(R.string.error_unknown)) }
                )
                addHistory(if (result.isSuccess) context.getString(R.string.history_installed_item, actualLabel)
                    else context.getString(R.string.history_failed_item, actualLabel, result.exceptionOrNull()?.message.orEmpty()),
                    result.isSuccess)
            } catch (e: CancellationException) {
                if (activeInstallSession == session) _installState.value = InstallState.Failed(
                    "Cancelled; an in-flight device installation may still finish", cancelled = true)
                throw e
            } catch (e: Exception) {
                if (holder.isCurrent(session)) _installState.value = InstallState.Failed(
                    e.message ?: context.getString(R.string.error_unknown))
            } finally {
                ticker?.cancel()
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { cleanup() }
            }
        }
    }

    fun loadPortalPackages(context: Context) {
        val session = holder.capture()
        if (session == null) {
            _portalPackages.value = emptyList()
            _portalPackagesError.value = context.getString(R.string.error_connect_first_for_portal_apps)
            return
        }
        // Anulam o incarcare anterioara ca sa nu se suprapuna scrierile in _portalApps.
        portalLoadJob?.cancel()
        val d = session.dadb
        portalLoadJob = holder.launchForSession(viewModelScope, session) {
            _isLoadingPortalPackages.value = true
            _portalPackagesError.value = null
            connectionManager.listPortalPackages(context, d)
                .onSuccess {
                    holder.ensureCurrent(session)

                    _portalApps.value = emptyList()
                    _portalPackages.value = it
                    _portalPackagesError.value = null
                    _isLoadingPortalPackages.value = false

                    // Imbogatirea ruleaza in acelasi Job (nu un launch orfan), ca
                    // anularea incarcarii sa opreasca si detaliile + iconitele.
                    connectionManager.listPortalApps(context, d)
                        .onSuccess { details ->
                            holder.ensureCurrent(session)

                            _portalApps.value = sortPortalApps(details, _portalAppsSort.value)
                            enrichPortalApps(context, session)
                        }
                }
                .onFailure { e ->
                    holder.ensureCurrent(session)

                    _portalApps.value = emptyList()
                    _portalPackages.value = emptyList()
                    _portalPackagesError.value =
                        e.message ?: context.getString(R.string.error_load_portal_apps_failed)
                    _isLoadingPortalPackages.value = false
                }
        }
    }

    fun uninstallApp(context: Context, packageName: String) {
        val session = holder.capture() ?: return
        val d = session.dadb
        holder.launchForSession(viewModelScope, session) {
            _uninstallState.value = UninstallState.InProgress(packageName)
            connectionManager.uninstallApp(context, d, packageName)
                .onSuccess {
                    holder.ensureCurrent(session)

                    _uninstallState.value = UninstallState.Success
                    addHistory(context.getString(R.string.history_uninstalled_item, packageName), success = true)
                    holder.ensureCurrent(session)
                    loadPortalPackages(context)
                }
                .onFailure { e ->
                    holder.ensureCurrent(session)

                    val msg = e.message ?: context.getString(R.string.error_uninstall_failed_generic)
                    _uninstallState.value = UninstallState.Failed(msg)
                    addHistory(
                        context.getString(R.string.history_uninstall_failed, packageName, msg),
                        success = false
                    )
                }
        }
    }

    fun applyProfile(context: Context, profile: AppProfile, availableApps: List<InstalledApp>) {
        val session = holder.capture()
        if (session == null) {
            _profileApplyState.value = ProfileApplyState.Failed(context.getString(R.string.profile_connect_first))
            return
        }
        if (profileApplyJob?.isCompleted == false ||
            installJob?.isCompleted == false
        ) {
            _profileApplyState.value = ProfileApplyState.Failed(context.getString(R.string.profile_install_busy))
            return
        }

        val connection = session.dadb
        val generation = ++profileGeneration
        val packages = profile.packageNames.sorted()
        val results = mutableListOf<Pair<String, Boolean>>()
        val reasons = mutableMapOf<String, String>()
        _profileApplyState.value = ProfileApplyState.Applying(profile.name, 0, profile.packageNames.size, "")
        profileApplyJob = holder.launchForSession(viewModelScope, session) {
            val appsByPackage = availableApps.associateBy { it.packageName }

            try {
                packages.forEachIndexed { index, packageName ->
                    holder.ensureCurrent(session)
                    val app = appsByPackage[packageName]
                    _profileApplyState.value = ProfileApplyState.Applying(
                        profileName = profile.name,
                        current = index + 1,
                        total = packages.size,
                        appName = app?.name ?: packageName
                    )

                    val label = app?.name ?: packageName
                    if (app == null) {
                        results += packageName to false
                        reasons[packageName] = context.getString(R.string.profile_app_not_found)
                        return@forEachIndexed
                    }

                    val baseApk = File(app.sourceDir)
                    val splits = app.splitSourceDirs?.map(::File).orEmpty()
                    val result = if (splits.isEmpty()) {
                        connectionManager.installApk(context, connection, baseApk, app.minSdkVersion)
                    } else {
                        connectionManager.installMultipleApks(context, connection, listOf(baseApk) + splits, app.minSdkVersion)
                    }
                    holder.ensureCurrent(session)
                    if (result.isSuccess) {
                        results += packageName to true
                    } else {
                        results += packageName to false
                        reasons[packageName] = result.exceptionOrNull()?.message ?: label
                    }
                }
            } catch (e: CancellationException) {
                throw e
            }

            val done = foldProfileResults(results, profile.name, reasons)
                .copy(deviceLabel = session.label, sessionGeneration = session.generation)
            val message = if (done.failed.isEmpty()) {
                context.getString(R.string.profile_apply_success, profile.name, done.succeeded.size)
            } else {
                context.getString(
                    R.string.profile_apply_partial,
                    profile.name,
                    done.succeeded.size,
                    done.failed.size
                )
            }
            _profileResults.value = done
            _profileApplyState.value = done
            addHistory(message, success = done.failed.isEmpty())
            holder.ensureCurrent(session)
            loadPortalPackages(context)
        }
        profileApplyJob?.invokeOnCompletion { failure ->
            if (failure is CancellationException && generation == profileGeneration) {
                val done = foldProfileResults(results, profile.name, reasons, packages, cancelled = true)
                    .copy(deviceLabel = session.label, sessionGeneration = session.generation)
                _profileResults.value = done
                if (holder.isCurrent(session)) _profileApplyState.value = done
            }
        }
    }

    /** Anuleaza instalarea in curs (daca exista). */
    fun cancelInstall() {
        if (installJob?.isCompleted == false) {
            installJob?.cancel()
            _installState.value = InstallState.Failed(
                "Cancelled; an in-flight device installation may still finish", cancelled = true)
        }
    }

    /** Anuleaza aplicarea profilului in curs (daca exista). */
    fun cancelProfileApply() {
        if (profileApplyJob?.isCompleted == false) {
            _profileApplyState.value = ProfileApplyState.Failed(
                "Cancellation requested; an in-flight device installation may still finish")
            profileApplyJob?.cancel()
        }
    }

    /**
     * Imbogateste aplicatiile Portal una cate una (eticheta + iconita), pe masura
     * ce APK-urile sunt descarcate si puse in cache. Actualizeaza fluxul dupa
     * fiecare aplicatie, ca UI-ul sa afiseze iconitele progresiv.
     */
    private suspend fun enrichPortalApps(context: Context, session: AdbSession) {
        val d = session.dadb
        withContext(Dispatchers.IO) {
            val current = _portalApps.value
            current.forEach { app ->
                // isActive respecta anularea portalLoadJob-ului: cand pornim o
                // noua incarcare, aceasta bucla se opreste curat.
                if (!isActive) return@forEach
                val enriched = connectionManager.enrichPortalApp(context, d, app)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                holder.ensureCurrent(session)
                if (enriched != app) {
                    _portalApps.value = sortPortalApps(
                        _portalApps.value.map { if (it.packageName == app.packageName) enriched else it },
                        _portalAppsSort.value
                    )
                }
            }
        }
    }

    fun forceStopApp(context: Context, packageName: String) {
        val session = holder.capture() ?: return
        val d = session.dadb
        holder.launchForSession(viewModelScope, session) {
            connectionManager.forceStopApp(context, d, packageName)
                .onSuccess {
                    holder.ensureCurrent(session)

                    _appActionState.value = AppActionState.Done(
                        context.getString(R.string.apps_force_stopped, packageName)
                    )
                }
                .onFailure {
                    holder.ensureCurrent(session)

                    _appActionState.value = AppActionState.Failed(
                        it.message ?: context.getString(R.string.error_unknown)
                    )
                }
        }
    }

    fun clearAppData(context: Context, packageName: String) {
        val session = holder.capture() ?: return
        val d = session.dadb
        holder.launchForSession(viewModelScope, session) {
            connectionManager.clearAppData(context, d, packageName)
                .onSuccess {
                    holder.ensureCurrent(session)

                    _appActionState.value = AppActionState.Done(
                        context.getString(R.string.apps_data_cleared, packageName)
                    )
                }
                .onFailure {
                    holder.ensureCurrent(session)

                    _appActionState.value = AppActionState.Failed(
                        it.message ?: context.getString(R.string.error_unknown)
                    )
                }
        }
    }

    fun launchApp(context: Context, packageName: String) {
        val session = holder.capture() ?: return
        val d = session.dadb
        holder.launchForSession(viewModelScope, session) {
            connectionManager.launchApp(d, packageName)
                .onSuccess {
                    holder.ensureCurrent(session)

                    _appActionState.value = AppActionState.Done(
                        context.getString(R.string.apps_launched, packageName)
                    )
                }
                .onFailure {
                    holder.ensureCurrent(session)

                    _appActionState.value = AppActionState.Failed(
                        it.message ?: context.getString(R.string.error_unknown)
                    )
                }
        }
    }

    fun clearAppActionState() {
        _appActionState.value = AppActionState.Idle
    }

    fun clearProfileApplyState() {
        _profileApplyState.value = ProfileApplyState.Idle
    }

    fun clearUninstallState() {
        _uninstallState.value = UninstallState.Idle
    }

    fun clearInstallState() {
        _installState.value = InstallState.Idle
    }

    fun clearPortalPackagesError() {
        _portalPackagesError.value = null
        _profileApplyState.value = ProfileApplyState.Idle
    }

    /** Resetează starea dependentă de conexiune când Portal-ul se deconectează. */
    fun onDisconnected() {
        cancelInstall()
        cancelProfileApply()
        activeInstallSession = null
        portalLoadJob?.cancel()
        _portalPackages.value = emptyList()
        _portalApps.value = emptyList()
        _isLoadingPortalPackages.value = false
        _portalPackagesError.value = null
        _uninstallState.value = UninstallState.Idle
        _appActionState.value = AppActionState.Idle
    }
}
