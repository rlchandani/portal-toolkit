package com.portalinstaller.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.portalinstaller.R
import com.portalinstaller.data.AppContainer
import com.portalinstaller.data.ContentUriHelper
import com.portalinstaller.data.InstalledApp
import com.portalinstaller.data.PortalAppInfo
import com.portalinstaller.data.InstallPolicy
import com.portalinstaller.data.PortalDeviceConfig
import com.portalinstaller.data.store.AppProfile
import com.portalinstaller.data.store.HistoryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    data class Failed(val message: String) : InstallState()
}

sealed class UninstallState {
    data object Idle : UninstallState()
    data class InProgress(val packageName: String) : UninstallState()
    data object Success : UninstallState()
    data class Failed(val message: String) : UninstallState()
}

sealed class ProfileApplyState {
    data object Idle : ProfileApplyState()
    data class Applying(
        val profileName: String,
        val current: Int,
        val total: Int,
        val appName: String
    ) : ProfileApplyState()
    data class Success(val message: String) : ProfileApplyState()
    data class Failed(val message: String) : ProfileApplyState()
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

    private suspend fun addHistory(description: String, success: Boolean) {
        historyStore.add(HistoryEntry(System.currentTimeMillis(), description, success))
    }

    fun loadInstalledApps(context: Context) {
        viewModelScope.launch {
            _installedApps.value = connectionManager.loadInstalledApps(context)
        }
    }

    fun setSelectedApkUri(uri: Uri?) {
        _selectedApkUri.value = uri
        if (uri != null) {
            _installState.value = InstallState.Idle
        }
    }

    fun installApp(context: Context, app: InstalledApp) {
        val d = holder.dadb.value ?: return
        if (_profileApplyState.value is ProfileApplyState.Applying) {
            _installState.value = InstallState.Failed(context.getString(R.string.profile_install_busy))
            return
        }
        viewModelScope.launch {
            val compatibilityError = validatePortalApp(context, app.name, app.minSdkVersion)
            if (compatibilityError != null) {
                _installState.value = InstallState.Failed(compatibilityError)
                addHistory(
                    context.getString(R.string.history_blocked_item, app.name, compatibilityError),
                    success = false
                )
                return@launch
            }

            val baseApk = File(app.sourceDir)
            val splits = app.splitSourceDirs?.map { File(it) }.orEmpty()
            val totalSize = baseApk.length() + splits.sumOf { it.length() }

            var elapsed = 0
            _installState.value = InstallState.Installing(elapsed, totalSize)
            val tickerJob = launch {
                while (isActive) {
                    delay(1000)
                    elapsed++
                    _installState.value = InstallState.Installing(elapsed, totalSize)
                }
            }

            val result = if (splits.isEmpty()) {
                connectionManager.installApk(context, d, baseApk)
            } else {
                connectionManager.installMultipleApks(context, d, listOf(baseApk) + splits)
            }
            tickerJob.cancel()

            result
                .onSuccess {
                    _installState.value = InstallState.Success
                    addHistory(context.getString(R.string.history_installed_item, app.name), success = true)
                }
                .onFailure { e ->
                    val msg = e.message ?: context.getString(R.string.error_install_failed_generic)
                    _installState.value = InstallState.Failed(msg)
                    addHistory(context.getString(R.string.history_failed_item, app.name, msg), success = false)
                }
        }
    }

    fun installApkFromUri(context: Context, uri: Uri) {
        val d = holder.dadb.value ?: return
        if (_profileApplyState.value is ProfileApplyState.Applying) {
            _installState.value = InstallState.Failed(context.getString(R.string.profile_install_busy))
            return
        }
        viewModelScope.launch {
            _installState.value = InstallState.Installing()

            val apkFile = withContext(Dispatchers.IO) {
                val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext null
                val tempFile = File(context.cacheDir, "upload_${System.nanoTime()}.apk")
                tempFile.outputStream().use { output -> inputStream.copyTo(output) }
                inputStream.close()
                tempFile
            }

            if (apkFile == null) {
                _installState.value = InstallState.Failed(context.getString(R.string.error_read_apk_failed))
                return@launch
            }

            val fileName = ContentUriHelper.getDisplayName(context, uri) ?: apkFile.name
            val compatibilityError = readArchiveMinSdk(context, apkFile)?.let { minSdk ->
                validatePortalApp(context, fileName, minSdk)
            }
            if (compatibilityError != null) {
                apkFile.delete()
                _installState.value = InstallState.Failed(compatibilityError)
                addHistory(
                    context.getString(R.string.history_custom_apk_blocked, fileName, compatibilityError),
                    success = false
                )
                return@launch
            }

            var elapsed = 0
            _installState.value = InstallState.Installing(elapsed, apkFile.length())
            val tickerJob = launch {
                while (isActive) {
                    delay(1000)
                    elapsed++
                    _installState.value = InstallState.Installing(elapsed, apkFile.length())
                }
            }

            connectionManager.installApk(context, d, apkFile)
                .onSuccess {
                    tickerJob.cancel()
                    apkFile.delete()
                    _installState.value = InstallState.Success
                    addHistory(context.getString(R.string.history_custom_apk_installed, fileName), success = true)
                }
                .onFailure { e ->
                    tickerJob.cancel()
                    apkFile.delete()
                    val msg = e.message ?: context.getString(R.string.error_install_failed_generic)
                    _installState.value = InstallState.Failed(msg)
                    addHistory(context.getString(R.string.history_custom_apk_failed, msg), success = false)
                }
        }
    }

    fun loadPortalPackages(context: Context) {
        val d = holder.dadb.value
        if (d == null) {
            _portalPackages.value = emptyList()
            _portalPackagesError.value = context.getString(R.string.error_connect_first_for_portal_apps)
            return
        }
        viewModelScope.launch {
            _isLoadingPortalPackages.value = true
            _portalPackagesError.value = null
            connectionManager.listPortalPackages(context, d)
                .onSuccess {
                    _portalApps.value = emptyList()
                    _portalPackages.value = it
                    _portalPackagesError.value = null
                    _isLoadingPortalPackages.value = false

                    // Enrichment is deliberately separate: the package list must
                    // appear even if metadata for one application is slow or unavailable.
                    viewModelScope.launch {
                        connectionManager.listPortalApps(context, d)
                            .onSuccess { details -> _portalApps.value = details }
                    }
                }
                .onFailure { e ->
                    _portalApps.value = emptyList()
                    _portalPackages.value = emptyList()
                    _portalPackagesError.value =
                        e.message ?: context.getString(R.string.error_load_portal_apps_failed)
                    _isLoadingPortalPackages.value = false
                }
        }
    }

    fun uninstallApp(context: Context, packageName: String) {
        val d = holder.dadb.value ?: return
        viewModelScope.launch {
            _uninstallState.value = UninstallState.InProgress(packageName)
            connectionManager.uninstallApp(context, d, packageName)
                .onSuccess {
                    _uninstallState.value = UninstallState.Success
                    addHistory(context.getString(R.string.history_uninstalled_item, packageName), success = true)
                    loadPortalPackages(context)
                }
                .onFailure { e ->
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
        val connection = holder.dadb.value
        if (connection == null) {
            _profileApplyState.value = ProfileApplyState.Failed(context.getString(R.string.profile_connect_first))
            return
        }
        if (_profileApplyState.value is ProfileApplyState.Applying ||
            _installState.value is InstallState.Installing
        ) {
            _profileApplyState.value = ProfileApplyState.Failed(context.getString(R.string.profile_install_busy))
            return
        }

        viewModelScope.launch {
            val appsByPackage = availableApps.associateBy { it.packageName }
            var installedCount = 0
            val failedPackages = mutableListOf<String>()
            val packages = profile.packageNames.sorted()

            packages.forEachIndexed { index, packageName ->
                val app = appsByPackage[packageName]
                _profileApplyState.value = ProfileApplyState.Applying(
                    profileName = profile.name,
                    current = index + 1,
                    total = packages.size,
                    appName = app?.name ?: packageName
                )

                if (app == null) {
                    failedPackages += packageName
                    return@forEachIndexed
                }

                val compatibilityError = validatePortalApp(context, app.name, app.minSdkVersion)
                if (compatibilityError != null) {
                    failedPackages += packageName
                    return@forEachIndexed
                }

                val baseApk = File(app.sourceDir)
                val splits = app.splitSourceDirs?.map(::File).orEmpty()
                val result = if (splits.isEmpty()) {
                    connectionManager.installApk(context, connection, baseApk)
                } else {
                    connectionManager.installMultipleApks(context, connection, listOf(baseApk) + splits)
                }
                if (result.isSuccess) installedCount++ else failedPackages += packageName
            }

            val message = if (failedPackages.isEmpty()) {
                context.getString(R.string.profile_apply_success, profile.name, installedCount)
            } else {
                context.getString(
                    R.string.profile_apply_partial,
                    profile.name,
                    installedCount,
                    failedPackages.size
                )
            }
            _profileApplyState.value = ProfileApplyState.Success(message)
            addHistory(message, success = failedPackages.isEmpty())
            loadPortalPackages(context)
        }
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
    }

    /** Resetează starea dependentă de conexiune când Portal-ul se deconectează. */
    fun onDisconnected() {
        _installState.value = InstallState.Idle
        _profileApplyState.value = ProfileApplyState.Idle
        _portalPackagesError.value = null
    }

    private fun validatePortalApp(context: Context, label: String, minSdkVersion: Int): String? {
        return if (!InstallPolicy.isCompatible(minSdkVersion)) {
            context.getString(
                R.string.error_portal_sdk_incompatible,
                label,
                minSdkVersion,
                PortalDeviceConfig.MAX_SDK
            )
        } else {
            null
        }
    }

    private fun readArchiveMinSdk(context: Context, apkFile: File): Int? {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageArchiveInfo(
                apkFile.absolutePath,
                android.content.pm.PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
        }
        return packageInfo?.applicationInfo?.minSdkVersion
    }
}
