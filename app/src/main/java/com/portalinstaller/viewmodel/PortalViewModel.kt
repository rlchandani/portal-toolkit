package com.portalinstaller.viewmodel

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.portalinstaller.R
import com.portalinstaller.data.ContentUriHelper
import com.portalinstaller.data.DeviceStatus
import com.portalinstaller.data.DiscoveredPortal
import com.portalinstaller.data.InstalledApp
import com.portalinstaller.data.PortalConnectionManager
import com.portalinstaller.data.PortalDiscoveryManager
import com.portalinstaller.data.PortalDeviceConfig
import com.portalinstaller.data.UsbPortalHelper
import dadb.Dadb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data object Connected : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

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

sealed class WirelessAdbState {
    data object Idle : WirelessAdbState()
    data class Enabling(val port: Int) : WirelessAdbState()
    data class Success(val message: String) : WirelessAdbState()
    data class Failed(val message: String) : WirelessAdbState()
}

sealed class PortalDiscoveryState {
    data object Idle : PortalDiscoveryState()
    data class Scanning(val scanned: Int, val total: Int, val found: Int) : PortalDiscoveryState()
    data class Success(val portals: List<DiscoveredPortal>) : PortalDiscoveryState()
    data class Failed(val message: String) : PortalDiscoveryState()
}

sealed class PhotoUploadState {
    data object Idle : PhotoUploadState()
    data class Uploading(val current: Int, val total: Int) : PhotoUploadState()
    data class Success(val count: Int) : PhotoUploadState()
    data class Failed(val message: String) : PhotoUploadState()
}

data class HistoryEntry(
    val timestamp: Long,
    val description: String,
    val success: Boolean
)

class PortalViewModel(
    private val connectionManager: PortalConnectionManager = PortalConnectionManager(),
    private val discoveryManager: PortalDiscoveryManager = PortalDiscoveryManager(connectionManager)
) : ViewModel() {

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _connectionMethod = MutableStateFlow<String?>(null)
    val connectionMethod: StateFlow<String?> = _connectionMethod.asStateFlow()

    private val _installState = MutableStateFlow<InstallState>(InstallState.Idle)
    val installState: StateFlow<InstallState> = _installState.asStateFlow()

    private val _uninstallState = MutableStateFlow<UninstallState>(UninstallState.Idle)
    val uninstallState: StateFlow<UninstallState> = _uninstallState.asStateFlow()

    private val _wirelessAdbState = MutableStateFlow<WirelessAdbState>(WirelessAdbState.Idle)
    val wirelessAdbState: StateFlow<WirelessAdbState> = _wirelessAdbState.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledApp>> = _installedApps.asStateFlow()

    private val _selectedApkUri = MutableStateFlow<Uri?>(null)
    val selectedApkUri: StateFlow<Uri?> = _selectedApkUri.asStateFlow()

    private val _photoUploadState = MutableStateFlow<PhotoUploadState>(PhotoUploadState.Idle)
    val photoUploadState: StateFlow<PhotoUploadState> = _photoUploadState.asStateFlow()

    private val _selectedPhotoUris = MutableStateFlow<List<Uri>>(emptyList())
    val selectedPhotoUris: StateFlow<List<Uri>> = _selectedPhotoUris.asStateFlow()

    private val _operationHistory = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val operationHistory: StateFlow<List<HistoryEntry>> = _operationHistory.asStateFlow()

    private val _portalPackages = MutableStateFlow<List<String>>(emptyList())
    val portalPackages: StateFlow<List<String>> = _portalPackages.asStateFlow()

    private val _isLoadingPortalPackages = MutableStateFlow(false)
    val isLoadingPortalPackages: StateFlow<Boolean> = _isLoadingPortalPackages.asStateFlow()

    private val _portalPackagesError = MutableStateFlow<String?>(null)
    val portalPackagesError: StateFlow<String?> = _portalPackagesError.asStateFlow()

    private val _deviceStatus = MutableStateFlow<DeviceStatus?>(null)
    val deviceStatus: StateFlow<DeviceStatus?> = _deviceStatus.asStateFlow()

    private val _portalDiscoveryState = MutableStateFlow<PortalDiscoveryState>(PortalDiscoveryState.Idle)
    val portalDiscoveryState: StateFlow<PortalDiscoveryState> = _portalDiscoveryState.asStateFlow()

    private var dadb: Dadb? = null

    private fun addHistoryEntry(description: String, success: Boolean) {
        _operationHistory.value = listOf(
            HistoryEntry(System.currentTimeMillis(), description, success)
        ) + _operationHistory.value
    }

    fun restoreHistory(entries: List<HistoryEntry>) {
        _operationHistory.value = entries
    }

    fun connect(context: Context, ip: String, port: Int = 5555) {
        if (_connectionState.value is ConnectionState.Connecting) return
        viewModelScope.launch {
            _connectionState.value = ConnectionState.Connecting
            connectionManager.connect(context, ip, port)
                .onSuccess { d ->
                    dadb = d
                    _portalPackagesError.value = null
                    _connectionMethod.value = context.getString(R.string.connection_method_wifi)
                    _connectionState.value = ConnectionState.Connected
                    refreshDeviceStatus()
                }
                .onFailure { e ->
                    _connectionState.value = ConnectionState.Error(
                        e.message ?: context.getString(R.string.error_unknown)
                    )
                }
        }
    }

    fun connectUsb(context: Context) {
        if (_connectionState.value is ConnectionState.Connecting) return
        viewModelScope.launch {
            _connectionState.value = ConnectionState.Connecting
            UsbPortalHelper.connect(context)
                .onSuccess { d ->
                    dadb = d
                    _portalPackagesError.value = null
                    _connectionMethod.value = context.getString(R.string.connection_method_usb)
                    _connectionState.value = ConnectionState.Connected
                    addHistoryEntry(context.getString(R.string.history_connected_usb), success = true)
                    refreshDeviceStatus()
                }
                .onFailure { e ->
                    val msg = e.message ?: context.getString(R.string.error_usb_connection_open_failed)
                    _connectionState.value = ConnectionState.Error(msg)
                    addHistoryEntry(
                        context.getString(R.string.history_usb_connect_failed, msg),
                        success = false
                    )
                }
        }
    }

    fun disconnect() {
        closeDadbAsync()
        _connectionState.value = ConnectionState.Disconnected
        _connectionMethod.value = null
        _installState.value = InstallState.Idle
        _photoUploadState.value = PhotoUploadState.Idle
        _portalPackagesError.value = null
        _wirelessAdbState.value = WirelessAdbState.Idle
        _deviceStatus.value = null
    }

    private fun refreshDeviceStatus() {
        val d = dadb ?: return
        viewModelScope.launch {
            _deviceStatus.value = connectionManager.getDeviceStatus(d)
        }
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
        val d = dadb ?: return
        viewModelScope.launch {
            val compatibilityError = validatePortalApp(
                context = context,
                label = app.name,
                minSdkVersion = app.minSdkVersion
            )
            if (compatibilityError != null) {
                _installState.value = InstallState.Failed(compatibilityError)
                addHistoryEntry(
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
                    addHistoryEntry(context.getString(R.string.history_installed_item, app.name), success = true)
                }
                .onFailure { e ->
                    val msg = e.message ?: context.getString(R.string.error_install_failed_generic)
                    _installState.value = InstallState.Failed(msg)
                    addHistoryEntry(
                        context.getString(R.string.history_failed_item, app.name, msg),
                        success = false
                    )
                }
        }
    }

    fun installApkFromUri(context: Context, uri: Uri) {
        val d = dadb ?: return
        viewModelScope.launch {
            _installState.value = InstallState.Installing()

            val apkFile = withContext(Dispatchers.IO) {
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: return@withContext null
                val tempFile = File(context.cacheDir, "upload_${System.nanoTime()}.apk")
                tempFile.outputStream().use { output ->
                    inputStream.copyTo(output)
                }
                inputStream.close()
                tempFile
            }

            if (apkFile == null) {
                _installState.value = InstallState.Failed(context.getString(R.string.error_read_apk_failed))
                return@launch
            }

            val fileName = ContentUriHelper.getDisplayName(context, uri) ?: apkFile.name
            val compatibilityError = readArchiveMinSdk(context, apkFile)?.let { minSdk ->
                validatePortalApp(context = context, label = fileName, minSdkVersion = minSdk)
            }
            if (compatibilityError != null) {
                apkFile.delete()
                _installState.value = InstallState.Failed(compatibilityError)
                addHistoryEntry(
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
                    addHistoryEntry(
                        context.getString(R.string.history_custom_apk_installed, fileName),
                        success = true
                    )
                }
                .onFailure { e ->
                    tickerJob.cancel()
                    apkFile.delete()
                    val msg = e.message ?: context.getString(R.string.error_install_failed_generic)
                    _installState.value = InstallState.Failed(msg)
                    addHistoryEntry(
                        context.getString(R.string.history_custom_apk_failed, msg),
                        success = false
                    )
                }
        }
    }

    fun setSelectedPhotoUris(uris: List<Uri>) {
        _selectedPhotoUris.value = uris
        if (uris.isNotEmpty()) {
            _photoUploadState.value = PhotoUploadState.Idle
        }
    }

    fun uploadPhotos(context: Context) {
        val d = dadb ?: return
        val uris = _selectedPhotoUris.value
        if (uris.isEmpty()) return

        viewModelScope.launch {
            var successCount = 0
            var lastError: String? = null

            uris.forEachIndexed { index, uri ->
                _photoUploadState.value = PhotoUploadState.Uploading(
                    current = index + 1,
                    total = uris.size
                )

                val photoFile = withContext(Dispatchers.IO) {
                    val inputStream = context.contentResolver.openInputStream(uri)
                        ?: return@withContext null
                    val fileName = ContentUriHelper.getDisplayName(context, uri) ?: "photo_${System.nanoTime()}.jpg"
                    val tempFile = File(context.cacheDir, fileName)
                    tempFile.outputStream().use { output ->
                        inputStream.copyTo(output)
                    }
                    inputStream.close()
                    tempFile
                }

                if (photoFile == null) {
                    lastError = context.getString(R.string.error_read_photo_failed)
                    return@forEachIndexed
                }

                connectionManager.pushPhoto(context, d, photoFile)
                    .onSuccess { successCount++ }
                    .onFailure { e -> lastError = e.message ?: context.getString(R.string.error_unknown) }

                photoFile.delete()
            }

            _photoUploadState.value = if (lastError == null) {
                PhotoUploadState.Success(successCount)
            } else {
                PhotoUploadState.Failed(
                    context.getString(
                        R.string.error_photo_upload_partial,
                        successCount,
                        uris.size,
                        lastError
                    )
                )
            }

            addHistoryEntry(
                description = context.getString(R.string.history_photo_upload, successCount, uris.size),
                success = lastError == null
            )

            _selectedPhotoUris.value = emptyList()
        }
    }

    fun loadPortalPackages(context: Context) {
        val d = dadb
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
                    _portalPackages.value = it
                    _portalPackagesError.value = null
                }
                .onFailure { e ->
                    _portalPackages.value = emptyList()
                    _portalPackagesError.value = e.message ?: context.getString(R.string.error_load_portal_apps_failed)
                }
            _isLoadingPortalPackages.value = false
        }
    }

    fun uninstallApp(context: Context, packageName: String) {
        val d = dadb ?: return
        viewModelScope.launch {
            _uninstallState.value = UninstallState.InProgress(packageName)
            connectionManager.uninstallApp(context, d, packageName)
                .onSuccess {
                    _uninstallState.value = UninstallState.Success
                    addHistoryEntry(context.getString(R.string.history_uninstalled_item, packageName), success = true)
                    loadPortalPackages(context)
                }
                .onFailure { e ->
                    val msg = e.message ?: context.getString(R.string.error_uninstall_failed_generic)
                    _uninstallState.value = UninstallState.Failed(msg)
                    addHistoryEntry(
                        context.getString(R.string.history_uninstall_failed, packageName, msg),
                        success = false
                    )
                }
        }
    }

    fun enableWirelessAdb(context: Context, port: Int = 5555) {
        val d = dadb ?: return
        viewModelScope.launch {
            _wirelessAdbState.value = WirelessAdbState.Enabling(port)
            connectionManager.enableWirelessAdb(context, d, port)
                .onSuccess { message ->
                    _wirelessAdbState.value = WirelessAdbState.Success(message)
                    addHistoryEntry(
                        context.getString(R.string.history_wireless_adb_enabled, port),
                        success = true
                    )
                    closeDadbAsync()
                    _connectionState.value = ConnectionState.Disconnected
                    _connectionMethod.value = null
                    _deviceStatus.value = null
                }
                .onFailure { e ->
                    val msg = e.message ?: context.getString(R.string.error_enable_wireless_adb)
                    _wirelessAdbState.value = WirelessAdbState.Failed(msg)
                    addHistoryEntry(
                        context.getString(R.string.history_wireless_adb_failed, msg),
                        success = false
                    )
                }
        }
    }

    fun discoverWirelessPortals(context: Context, port: Int = 5555) {
        if (_portalDiscoveryState.value is PortalDiscoveryState.Scanning) return
        viewModelScope.launch {
            _portalDiscoveryState.value = PortalDiscoveryState.Scanning(scanned = 0, total = 254, found = 0)
            discoveryManager.discoverPortals(
                context = context,
                port = port,
                onProgress = { scanned, total, found ->
                    _portalDiscoveryState.value = PortalDiscoveryState.Scanning(scanned, total, found)
                }
            ).onSuccess { portals ->
                _portalDiscoveryState.value = PortalDiscoveryState.Success(portals)
            }.onFailure { error ->
                _portalDiscoveryState.value = PortalDiscoveryState.Failed(
                    error.message ?: context.getString(R.string.error_discovery_failed)
                )
            }
        }
    }

    fun connectDiscoveredPortal(context: Context, portal: DiscoveredPortal) {
        connect(context, portal.ip, portal.port)
    }

    fun clearUninstallState() {
        _uninstallState.value = UninstallState.Idle
    }

    fun clearWirelessAdbState() {
        _wirelessAdbState.value = WirelessAdbState.Idle
    }

    fun clearPortalDiscoveryState() {
        _portalDiscoveryState.value = PortalDiscoveryState.Idle
    }

    fun clearInstallState() {
        _installState.value = InstallState.Idle
    }

    fun clearPhotoUploadState() {
        _photoUploadState.value = PhotoUploadState.Idle
    }

    fun clearConnectionError() {
        if (_connectionState.value is ConnectionState.Error) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    fun clearPortalPackagesError() {
        _portalPackagesError.value = null
    }

    private fun validatePortalApp(context: Context, label: String, minSdkVersion: Int): String? {
        return if (minSdkVersion > PortalDeviceConfig.MAX_SDK) {
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

    private fun closeDadbAsync() {
        val connection = dadb ?: return
        dadb = null
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { connection.close() }
        }
    }

    override fun onCleared() {
        super.onCleared()
        closeDadbAsync()
    }
}
