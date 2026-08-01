package com.portalinstaller.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.os.Build
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.portalinstaller.R
import com.portalinstaller.ui.common.SectionCardShape
import com.portalinstaller.ui.common.formatFileSize
import com.portalinstaller.ui.common.formatRam
import com.portalinstaller.ui.common.formatStorage
import com.portalinstaller.data.AppProfile
import com.portalinstaller.data.AppProfileStore
import com.portalinstaller.data.ContentUriHelper
import com.portalinstaller.data.DeviceStatus
import com.portalinstaller.data.DiscoveredPortal
import com.portalinstaller.data.InstalledApp
import com.portalinstaller.data.PortalDeviceConfig
import com.portalinstaller.data.PortalAppInfo
import com.portalinstaller.data.SavedPortal
import com.portalinstaller.data.SavedPortalStore
import com.portalinstaller.data.UsbPortalHelper
import com.portalinstaller.data.UsbPortalStatus
import com.portalinstaller.util.AppLanguage
import com.portalinstaller.util.AppLanguageManager
import com.portalinstaller.viewmodel.BackupState
import com.portalinstaller.viewmodel.ConnectionState
import com.portalinstaller.viewmodel.HistoryEntry
import com.portalinstaller.viewmodel.InstallState
import com.portalinstaller.viewmodel.OsUpdateState
import com.portalinstaller.viewmodel.PhotoUploadState
import com.portalinstaller.viewmodel.PortalDiscoveryState
import com.portalinstaller.viewmodel.PortalViewModel
import com.portalinstaller.viewmodel.ProfileApplyState
import com.portalinstaller.viewmodel.UninstallState
import com.portalinstaller.viewmodel.WirelessAdbState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Cuvinte-cheie care semnaleaza split APK-uri dependente de dispozitivul sursa.
 */
private val SPLIT_APK_RISK_KEYWORDS = listOf(
    "config.arm64_v8a",
    "config.armeabi_v7a",
    "config.x86",
    "config.x86_64",
    "config.hdpi",
    "config.xhdpi",
    "config.xxhdpi",
    "config.xxxhdpi"
)

/**
 * Pragul de marime peste care cerem confirmare explicita inainte de instalare.
 */
private const val LARGE_APP_SIZE_THRESHOLD_BYTES = 50L * 1024 * 1024 // 50 MB

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: PortalViewModel = viewModel()) {
    val context = LocalContext.current

    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val connectionMethod by viewModel.connectionMethod.collectAsStateWithLifecycle()
    val installState by viewModel.installState.collectAsStateWithLifecycle()
    val uninstallState by viewModel.uninstallState.collectAsStateWithLifecycle()
    val wirelessAdbState by viewModel.wirelessAdbState.collectAsStateWithLifecycle()
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
    val selectedApkUri by viewModel.selectedApkUri.collectAsStateWithLifecycle()
    val photoUploadState by viewModel.photoUploadState.collectAsStateWithLifecycle()
    val selectedPhotoUris by viewModel.selectedPhotoUris.collectAsStateWithLifecycle()
    val operationHistory by viewModel.operationHistory.collectAsStateWithLifecycle()
    val portalPackages by viewModel.portalPackages.collectAsStateWithLifecycle()
    val portalApps by viewModel.portalApps.collectAsStateWithLifecycle()
    val isLoadingPortalPackages by viewModel.isLoadingPortalPackages.collectAsStateWithLifecycle()
    val portalPackagesError by viewModel.portalPackagesError.collectAsStateWithLifecycle()
    val deviceStatus by viewModel.deviceStatus.collectAsStateWithLifecycle()
    val portalDiscoveryState by viewModel.portalDiscoveryState.collectAsStateWithLifecycle()
    val backupState by viewModel.backupState.collectAsStateWithLifecycle()
    val profileApplyState by viewModel.profileApplyState.collectAsStateWithLifecycle()
    val osUpdateState by viewModel.osUpdateState.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by remember { mutableIntStateOf(2) }
    var usbRefreshTrigger by remember { mutableIntStateOf(0) }
    var showPortalScanner by remember { mutableStateOf(false) }
    var showBackupAppSelector by remember { mutableStateOf(false) }
    var showProfileEditor by remember { mutableStateOf(false) }
    var selectedBackupPackages by remember { mutableStateOf<Set<String>?>(null) }
    var selectedLanguage by remember { mutableStateOf(AppLanguageManager.getSavedLanguage(context)) }
    var savedPortals by remember { mutableStateOf(SavedPortalStore.load(context)) }
    var appProfiles by remember { mutableStateOf(AppProfileStore.load(context)) }

    // Pastram ultimul IP si port folosite pentru reconectare rapida.
    val prefs = remember {
        context.getSharedPreferences("portal_installer_prefs", Context.MODE_PRIVATE)
    }
    var ipAddress by remember { mutableStateOf(prefs.getString("last_ip", "") ?: "") }
    var port by remember { mutableStateOf(prefs.getString("last_port", "5555") ?: "5555") }
    val usbStatus by produceState(
        initialValue = UsbPortalStatus(
            portalDeviceDetected = false,
            adbCapableDeviceDetected = false,
            hasPermission = false,
            deviceLabel = null,
            details = context.getString(R.string.usb_checking_devices)
        ),
        key1 = usbRefreshTrigger
    ) {
        while (isActive) {
            value = UsbPortalHelper.getStatus(context)
            delay(750L)
        }
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    UsbPortalHelper.ACTION_USB_PERMISSION,
                    android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED,
                    android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    usbRefreshTrigger++
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(UsbPortalHelper.ACTION_USB_PERMISSION)
            addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    // File picker pentru fisiere APK
    val apkPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        viewModel.setSelectedApkUri(uri)
    }

    // File picker pentru poze (selectie multipla)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        viewModel.setSelectedPhotoUris(uris)
    }

    val backupCreateLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        uri?.let { viewModel.createBackup(context, it, selectedBackupPackages) }
    }

    val backupRestoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.inspectBackup(context, it) }
    }

    // Initializeaza lista de aplicatii la prima incarcare.
    LaunchedEffect(Unit) {
        viewModel.loadInstalledApps(context)
    }

    // Restaureaza istoricul salvat din sesiunea anterioara.
    LaunchedEffect(Unit) {
        val savedHistory = loadHistoryFromPrefs(prefs)
        if (savedHistory.isNotEmpty()) {
            viewModel.restoreHistory(savedHistory)
        }
    }

    // Salveaza istoricul in SharedPreferences cand apar intrari noi.
    LaunchedEffect(operationHistory) {
        if (operationHistory.isNotEmpty()) {
            saveHistoryToPrefs(prefs, operationHistory)
        }
    }

    // Reconectare automata la pornire daca avem deja un IP salvat.
    LaunchedEffect(Unit) {
        if (ipAddress.isNotBlank() && connectionState is ConnectionState.Disconnected) {
            viewModel.connect(context, ipAddress, port.toIntOrNull() ?: 5555)
        }
    }

    LaunchedEffect(connectionState, connectionMethod, deviceStatus) {
        if (connectionState is ConnectionState.Connected &&
            connectionMethod == context.getString(R.string.connection_method_wifi) &&
            ipAddress.isNotBlank()
        ) {
            val connectedPort = port.toIntOrNull() ?: 5555
            val discovered = (portalDiscoveryState as? PortalDiscoveryState.Success)
                ?.portals
                ?.firstOrNull { it.ip == ipAddress && it.port == connectedPort }
            val existing = savedPortals.firstOrNull {
                it.ip == ipAddress && it.port == connectedPort
            }
            savedPortals = SavedPortalStore.upsert(
                context,
                SavedPortal(
                    name = existing?.name
                        ?: discovered?.displayName
                        ?: context.getString(R.string.saved_portal_default_name, ipAddress),
                    ip = ipAddress,
                    port = connectedPort,
                    model = deviceStatus?.model?.takeIf { it.isNotBlank() }
                        ?: discovered?.model
                        ?: existing?.model.orEmpty(),
                    lastConnectedAt = System.currentTimeMillis()
                )
            )
        }
    }

    LaunchedEffect(ipAddress, connectionState) {
        if (connectionState is ConnectionState.Connected) {
            selectedBackupPackages = null
        }
    }

    // Afiseaza Snackbar pentru stari importante si erori.
    LaunchedEffect(connectionState) {
        if (connectionState is ConnectionState.Error) {
            snackbarHostState.showSnackbar((connectionState as ConnectionState.Error).message)
            viewModel.clearConnectionError()
        }
    }

    LaunchedEffect(installState) {
        when (val state = installState) {
            is InstallState.Success -> {
                snackbarHostState.showSnackbar(context.getString(R.string.snackbar_install_success))
                viewModel.clearInstallState()
            }
            is InstallState.Failed -> {
                snackbarHostState.showSnackbar(state.message)
                viewModel.clearInstallState()
            }
            else -> {}
        }
    }

    LaunchedEffect(photoUploadState) {
        when (val state = photoUploadState) {
            is PhotoUploadState.Success -> {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.snackbar_photos_success, state.count)
                )
                viewModel.clearPhotoUploadState()
            }
            is PhotoUploadState.Failed -> {
                snackbarHostState.showSnackbar(state.message)
                viewModel.clearPhotoUploadState()
            }
            else -> {}
        }
    }

    LaunchedEffect(uninstallState) {
        when (val state = uninstallState) {
            is UninstallState.Success -> {
                snackbarHostState.showSnackbar(context.getString(R.string.snackbar_uninstall_success))
                viewModel.clearUninstallState()
            }
            is UninstallState.Failed -> {
                snackbarHostState.showSnackbar(state.message)
                viewModel.clearUninstallState()
            }
            else -> {}
        }
    }

    LaunchedEffect(wirelessAdbState) {
        when (val state = wirelessAdbState) {
            is WirelessAdbState.Success -> {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.snackbar_wireless_adb_enabled)
                )
                viewModel.clearWirelessAdbState()
            }
            is WirelessAdbState.Failed -> {
                snackbarHostState.showSnackbar(state.message)
                viewModel.clearWirelessAdbState()
            }
            else -> {}
        }
    }

    LaunchedEffect(portalPackagesError) {
        portalPackagesError?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearPortalPackagesError()
        }
    }

    LaunchedEffect(portalDiscoveryState) {
        if (portalDiscoveryState is PortalDiscoveryState.Failed) {
            snackbarHostState.showSnackbar(
                (portalDiscoveryState as PortalDiscoveryState.Failed).message
            )
            viewModel.clearPortalDiscoveryState()
        }
    }

    LaunchedEffect(backupState) {
        when (val state = backupState) {
            is BackupState.Success -> {
                snackbarHostState.showSnackbar(state.message)
                viewModel.clearBackupState()
            }
            is BackupState.Failed -> {
                snackbarHostState.showSnackbar(state.message)
                viewModel.clearBackupState()
            }
            else -> Unit
        }
    }

    LaunchedEffect(profileApplyState) {
        when (val state = profileApplyState) {
            is ProfileApplyState.Success -> {
                snackbarHostState.showSnackbar(state.message)
                viewModel.clearProfileApplyState()
            }
            is ProfileApplyState.Failed -> {
                snackbarHostState.showSnackbar(state.message)
                viewModel.clearProfileApplyState()
            }
            else -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { AppBrandHeader() })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                val destinations = listOf(
                    Triple(R.string.tab_devices, 2, 2),
                    Triple(R.string.tab_apps, 0, 0),
                    Triple(R.string.tab_photos, 1, 1),
                    Triple(R.string.tab_more, 3, 3)
                )
                destinations.forEach { (label, screen, glyph) ->
                    NavigationBarItem(
                        selected = selectedTab == screen,
                        onClick = {
                            selectedTab = screen
                            if (screen == 0 &&
                                connectionState is ConnectionState.Connected
                            ) {
                                viewModel.loadPortalPackages(context)
                            }
                        },
                        icon = {
                            NavigationGlyph(glyph, selectedTab == screen)
                        },
                        label = { Text(stringResource(label)) }
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (installState is InstallState.Installing) {
                val state = installState as InstallState.Installing
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = stringResource(
                        R.string.install_progress_message,
                        formatFileSize(state.sizeBytes),
                        state.elapsedSeconds
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            val isWifiConnected = connectionState is ConnectionState.Connected &&
                connectionMethod == context.getString(R.string.connection_method_wifi)
            val activePortalName = savedPortals.firstOrNull {
                it.ip == ipAddress && it.port == (port.toIntOrNull() ?: 5555)
            }?.name

            when (selectedTab) {
                0 -> AppsHubTab(
                    isWifiConnected = isWifiConnected,
                    activePortalName = activePortalName,
                    activePortalAddress = "$ipAddress:${port.ifBlank { "5555" }}",
                    apps = installedApps,
                    connectionState = connectionState,
                    installState = installState,
                    context = context,
                    selectedUri = selectedApkUri,
                    portalPackages = portalPackages,
                    portalApps = portalApps,
                    isLoadingPortalPackages = isLoadingPortalPackages,
                    uninstallState = uninstallState,
                    profiles = appProfiles,
                    profileApplyState = profileApplyState,
                    onOpenDevices = { selectedTab = 2 },
                    onPickFile = {
                        apkPickerLauncher.launch("application/vnd.android.package-archive")
                    },
                    onInstall = { app -> viewModel.installApp(context, app) },
                    onInstallCustomApk = { uri -> viewModel.installApkFromUri(context, uri) },
                    onRefreshPortalApps = { viewModel.loadPortalPackages(context) },
                    onUninstall = { packageName -> viewModel.uninstallApp(context, packageName) },
                    onCreateProfile = { showProfileEditor = true },
                    onApplyProfile = { profile ->
                        viewModel.applyProfile(context, profile, installedApps)
                    },
                    onDeleteProfile = { profile ->
                        appProfiles = AppProfileStore.remove(context, profile.id)
                    }
                )
                1 -> PhotosTab(
                    selectedUris = selectedPhotoUris,
                    isWifiConnected = isWifiConnected,
                    photoUploadState = photoUploadState,
                    onOpenDevices = { selectedTab = 2 },
                    onPickPhotos = { photoPickerLauncher.launch("image/*") },
                    onUpload = { viewModel.uploadPhotos(context) }
                )
                2 -> DevicesTab(
                    ipAddress = ipAddress,
                    port = port,
                    connectionState = connectionState,
                    connectionMethod = connectionMethod,
                    deviceStatus = deviceStatus,
                    savedPortals = savedPortals,
                    usbStatus = usbStatus,
                    wirelessAdbState = wirelessAdbState,
                    osUpdateState = osUpdateState,
                    backupState = backupState,
                    portalPackages = portalPackages,
                    isLoadingPortalPackages = isLoadingPortalPackages,
                    selectedBackupPackages = selectedBackupPackages,
                    onIpChange = { ipAddress = it },
                    onPortChange = { port = it },
                    onConnectManual = {
                        prefs.edit()
                            .putString("last_ip", ipAddress)
                            .putString("last_port", port)
                            .apply()
                        viewModel.connect(context, ipAddress, port.toIntOrNull() ?: 5555)
                    },
                    onConnectSaved = { saved ->
                        ipAddress = saved.ip
                        port = saved.port.toString()
                        prefs.edit()
                            .putString("last_ip", saved.ip)
                            .putString("last_port", saved.port.toString())
                            .apply()
                        viewModel.connect(context, saved.ip, saved.port)
                    },
                    onRemoveSaved = { saved ->
                        savedPortals = SavedPortalStore.remove(context, saved.id)
                    },
                    onRenameSaved = { saved, name ->
                        savedPortals = SavedPortalStore.rename(context, saved.id, name)
                    },
                    onDisconnect = { viewModel.disconnect() },
                    onScanPortals = {
                        showPortalScanner = true
                        viewModel.discoverWirelessPortals(context, port.toIntOrNull() ?: 5555)
                    },
                    onRefreshUsb = { usbRefreshTrigger++ },
                    onRequestUsbPermission = {
                        if (!UsbPortalHelper.requestPermission(context)) {
                            usbRefreshTrigger++
                        }
                    },
                    onConnectUsb = { viewModel.connectUsb(context) },
                    onEnableWirelessAdb = {
                        viewModel.enableWirelessAdb(context, port.toIntOrNull() ?: 5555)
                    },
                    onRefreshOsUpdateStatus = { viewModel.refreshOsUpdateStatus(context) },
                    onSetOsUpdatesBlocked = { blocked ->
                        viewModel.setOsUpdatesBlocked(context, blocked)
                    },
                    onChooseBackupApps = {
                        viewModel.loadPortalPackages(context)
                        showBackupAppSelector = true
                    },
                    onCreateBackup = {
                        backupCreateLauncher.launch(
                            "portal-backup-${System.currentTimeMillis()}.zip"
                        )
                    },
                    onRestoreBackup = {
                        backupRestoreLauncher.launch(
                            arrayOf("application/zip", "application/octet-stream")
                        )
                    }
                )
                3 -> MoreTab(
                    context = context,
                    history = operationHistory,
                    selectedLanguage = selectedLanguage,
                    onSelectLanguage = { language ->
                        if (selectedLanguage != language) {
                            selectedLanguage = language
                            AppLanguageManager.applyLanguage(context, language)
                            (context as? Activity)?.recreate()
                        }
                    }
                )
            }
        }
    }

    if (showPortalScanner) {
        PortalScannerDialog(
            discoveryState = portalDiscoveryState,
            port = port,
            onScan = {
                viewModel.discoverWirelessPortals(context, port.toIntOrNull() ?: 5555)
            },
            onConnect = { portal ->
                ipAddress = portal.ip
                prefs.edit()
                    .putString("last_ip", portal.ip)
                    .putString("last_port", portal.port.toString())
                    .apply()
                showPortalScanner = false
                viewModel.connectDiscoveredPortal(context, portal)
            },
            onDismiss = { showPortalScanner = false }
        )
    }

    (backupState as? BackupState.ReadyToRestore)?.let { ready ->
        BackupRestoreConfirmationDialog(
            ready = ready,
            onDismiss = { viewModel.clearBackupState() },
            onConfirm = { viewModel.restoreBackup(context, ready.source) }
        )
    }

    if (showBackupAppSelector) {
        BackupAppSelectionDialog(
            packages = portalPackages,
            isLoading = isLoadingPortalPackages,
            selectedPackages = selectedBackupPackages,
            onUseAll = {
                selectedBackupPackages = null
                showBackupAppSelector = false
            },
            onConfirm = { selected ->
                selectedBackupPackages = selected
                showBackupAppSelector = false
            },
            onDismiss = { showBackupAppSelector = false }
        )
    }

    if (showProfileEditor) {
        AppProfileEditorDialog(
            apps = installedApps,
            onSave = { name, packageNames ->
                appProfiles = AppProfileStore.saveProfile(context, name, packageNames)
                showProfileEditor = false
            },
            onDismiss = { showProfileEditor = false }
        )
    }
}

@Composable
private fun NavigationGlyph(index: Int, selected: Boolean) {
    val color = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Canvas(modifier = Modifier.size(24.dp)) {
        val strokeWidth = 1.8.dp.toPx()
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        val inset = 3.dp.toPx()

        when (index) {
            0 -> {
                val cellSize = 7.dp.toPx()
                val gap = 4.dp.toPx()
                listOf(
                    Offset(inset, inset),
                    Offset(inset + cellSize + gap, inset),
                    Offset(inset, inset + cellSize + gap),
                    Offset(inset + cellSize + gap, inset + cellSize + gap)
                ).forEach { topLeft ->
                    drawRoundRect(
                        color = color,
                        topLeft = topLeft,
                        size = Size(cellSize, cellSize),
                        cornerRadius = CornerRadius(1.5.dp.toPx()),
                        style = stroke
                    )
                }
            }

            1 -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(inset, 4.dp.toPx()),
                    size = Size(18.dp.toPx(), 16.dp.toPx()),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                    style = stroke
                )
                drawCircle(
                    color = color,
                    radius = 1.5.dp.toPx(),
                    center = Offset(17.dp.toPx(), 8.dp.toPx())
                )
                drawLine(
                    color = color,
                    start = Offset(5.dp.toPx(), 17.dp.toPx()),
                    end = Offset(10.dp.toPx(), 12.dp.toPx()),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = color,
                    start = Offset(10.dp.toPx(), 12.dp.toPx()),
                    end = Offset(18.dp.toPx(), 19.dp.toPx()),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round
                )
            }

            2 -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(3.dp.toPx(), 5.dp.toPx()),
                    size = Size(13.dp.toPx(), 15.dp.toPx()),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                    style = stroke
                )
                drawRoundRect(
                    color = color,
                    topLeft = Offset(11.dp.toPx(), 3.dp.toPx()),
                    size = Size(10.dp.toPx(), 13.dp.toPx()),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                    style = stroke
                )
            }

            else -> {
                listOf(6.dp, 12.dp, 18.dp).forEach { x ->
                    drawCircle(
                        color = color,
                        radius = 1.8.dp.toPx(),
                        center = Offset(x.toPx(), 12.dp.toPx())
                    )
                }
            }
        }
    }
}

@Composable
private fun AppBrandHeader() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(width = 20.dp, height = 14.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.92f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "↓",
                    color = MaterialTheme.colorScheme.primaryContainer,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "Portal",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.92f)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Toolkit",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun AppsHubTab(
    isWifiConnected: Boolean,
    activePortalName: String?,
    activePortalAddress: String,
    apps: List<InstalledApp>,
    connectionState: ConnectionState,
    installState: InstallState,
    context: Context,
    selectedUri: Uri?,
    portalPackages: List<String>,
    portalApps: List<PortalAppInfo>,
    isLoadingPortalPackages: Boolean,
    uninstallState: UninstallState,
    profiles: List<AppProfile>,
    profileApplyState: ProfileApplyState,
    onOpenDevices: () -> Unit,
    onPickFile: () -> Unit,
    onInstall: (InstalledApp) -> Unit,
    onInstallCustomApk: (Uri) -> Unit,
    onRefreshPortalApps: () -> Unit,
    onUninstall: (String) -> Unit,
    onCreateProfile: () -> Unit,
    onApplyProfile: (AppProfile) -> Unit,
    onDeleteProfile: (AppProfile) -> Unit
) {
    var selectedSection by remember { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        if (isWifiConnected) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = activePortalName ?: stringResource(R.string.active_portal),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = activePortalAddress,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = onOpenDevices) {
                        Text(stringResource(R.string.change))
                    }
                }
            }
        }

        TabRow(selectedTabIndex = selectedSection) {
            Tab(
                selected = selectedSection == 0,
                onClick = { selectedSection = 0 },
                text = { Text(stringResource(R.string.apps_from_phone)) }
            )
            Tab(
                selected = selectedSection == 1,
                onClick = {
                    selectedSection = 1
                    onRefreshPortalApps()
                },
                text = { Text(stringResource(R.string.apps_on_portal)) }
            )
            Tab(
                selected = selectedSection == 2,
                onClick = { selectedSection = 2 },
                text = { Text(stringResource(R.string.apps_profiles)) }
            )
        }

        if (!isWifiConnected && selectedSection != 2) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.no_portal_connected_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.no_portal_connected_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onOpenDevices,
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text(stringResource(R.string.open_devices))
                }
            }
        } else if (selectedSection == 0) {
            InstalledAppsTab(
                apps = apps,
                connectionState = connectionState,
                installState = installState,
                externalInstallBusy = profileApplyState is ProfileApplyState.Applying,
                context = context,
                selectedUri = selectedUri,
                onPickFile = onPickFile,
                onInstall = onInstall,
                onInstallCustomApk = onInstallCustomApk
            )
        } else if (selectedSection == 1) {
            PortalAppsTab(
                packages = portalPackages,
                apps = portalApps,
                isLoading = isLoadingPortalPackages,
                uninstallState = uninstallState,
                onRefresh = onRefreshPortalApps,
                onUninstall = onUninstall
            )
        } else {
            ProfilesTab(
                profiles = profiles,
                applyState = profileApplyState,
                isConnected = isWifiConnected,
                onCreate = onCreateProfile,
                onApply = onApplyProfile,
                onDelete = onDeleteProfile
            )
        }
    }
}

@Composable
private fun ProfilesTab(
    profiles: List<AppProfile>,
    applyState: ProfileApplyState,
    isConnected: Boolean,
    onCreate: () -> Unit,
    onApply: (AppProfile) -> Unit,
    onDelete: (AppProfile) -> Unit
) {
    val applying = applyState as? ProfileApplyState.Applying
    var pendingDelete by remember { mutableStateOf<AppProfile?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Button(
                onClick = onCreate,
                enabled = applying == null,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp)
            ) {
                Text(stringResource(R.string.profile_create))
            }
        }

        if (!isConnected) {
            item {
                Text(
                    text = stringResource(R.string.profile_connect_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (applying != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                    )
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = stringResource(
                                R.string.profile_applying_title,
                                applying.profileName
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(
                                R.string.profile_applying_progress,
                                applying.current,
                                applying.total,
                                applying.appName
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        if (profiles.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.profiles_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(profiles, key = { it.id }) { profile ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = profile.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = stringResource(
                                R.string.profile_app_count,
                                profile.packageNames.size
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { onApply(profile) },
                                enabled = isConnected && applying == null,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text(stringResource(R.string.profile_apply))
                            }
                            TextButton(
                                onClick = { pendingDelete = profile },
                                enabled = applying == null
                            ) {
                                Text(stringResource(R.string.remove))
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.profile_delete_title)) },
            text = {
                Text(stringResource(R.string.profile_delete_message, profile.name))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(profile)
                        pendingDelete = null
                    }
                ) {
                    Text(stringResource(R.string.remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun DevicesTab(
    ipAddress: String,
    port: String,
    connectionState: ConnectionState,
    connectionMethod: String?,
    deviceStatus: DeviceStatus?,
    savedPortals: List<SavedPortal>,
    usbStatus: UsbPortalStatus,
    wirelessAdbState: WirelessAdbState,
    osUpdateState: OsUpdateState,
    backupState: BackupState,
    portalPackages: List<String>,
    isLoadingPortalPackages: Boolean,
    selectedBackupPackages: Set<String>?,
    onIpChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onConnectManual: () -> Unit,
    onConnectSaved: (SavedPortal) -> Unit,
    onRemoveSaved: (SavedPortal) -> Unit,
    onRenameSaved: (SavedPortal, String) -> Unit,
    onDisconnect: () -> Unit,
    onScanPortals: () -> Unit,
    onRefreshUsb: () -> Unit,
    onRequestUsbPermission: () -> Unit,
    onConnectUsb: () -> Unit,
    onEnableWirelessAdb: () -> Unit,
    onRefreshOsUpdateStatus: () -> Unit,
    onSetOsUpdatesBlocked: (Boolean) -> Unit,
    onChooseBackupApps: () -> Unit,
    onCreateBackup: () -> Unit,
    onRestoreBackup: () -> Unit
) {
    var selectedSection by remember { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedSection) {
            Tab(
                selected = selectedSection == 0,
                onClick = { selectedSection = 0 },
                text = { Text(stringResource(R.string.devices_wifi)) }
            )
            Tab(
                selected = selectedSection == 1,
                onClick = { selectedSection = 1 },
                text = { Text(stringResource(R.string.devices_backup)) }
            )
            Tab(
                selected = selectedSection == 2,
                onClick = { selectedSection = 2 },
                text = { Text(stringResource(R.string.devices_usb)) }
            )
        }

        if (selectedSection == 0) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    ConnectionSection(
                        ipAddress = ipAddress,
                        port = port,
                        connectionState = connectionState,
                        connectionMethod = connectionMethod,
                        deviceStatus = deviceStatus,
                        onIpChange = onIpChange,
                        onPortChange = onPortChange,
                        onConnect = onConnectManual,
                        onDisconnect = onDisconnect,
                        onScanPortals = onScanPortals
                    )
                }
                item {
                    OsUpdateBlockCard(
                        connectionState = connectionState,
                        state = osUpdateState,
                        onRefresh = onRefreshOsUpdateStatus,
                        onSetBlocked = onSetOsUpdatesBlocked
                    )
                }
                item {
                    SavedPortalsCard(
                        portals = savedPortals,
                        currentAddress = if (connectionState is ConnectionState.Connected) {
                            "$ipAddress:${port.ifBlank { "5555" }}"
                        } else {
                            null
                        },
                        onConnect = onConnectSaved,
                        onRemove = onRemoveSaved,
                        onRename = onRenameSaved
                    )
                }
            }
        } else if (selectedSection == 1) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp)
            ) {
                item {
                    BackupCard(
                        isConnected = connectionState is ConnectionState.Connected,
                        backupState = backupState,
                        availablePackageCount = portalPackages.size,
                        isLoadingPackages = isLoadingPortalPackages,
                        selectedPackages = selectedBackupPackages,
                        onChooseApps = onChooseBackupApps,
                        onCreateBackup = onCreateBackup,
                        onRestoreBackup = onRestoreBackup
                    )
                }
            }
        } else {
            WirelessAdbGuideTab(
                connectionState = connectionState,
                connectionMethod = connectionMethod,
                usbStatus = usbStatus,
                wirelessAdbState = wirelessAdbState,
                port = port,
                onRefreshUsb = onRefreshUsb,
                onRequestUsbPermission = onRequestUsbPermission,
                onConnectUsb = onConnectUsb,
                onEnableWirelessAdb = onEnableWirelessAdb
            )
        }
    }
}

@Composable
private fun SavedPortalsCard(
    portals: List<SavedPortal>,
    currentAddress: String?,
    onConnect: (SavedPortal) -> Unit,
    onRemove: (SavedPortal) -> Unit,
    onRename: (SavedPortal, String) -> Unit
) {
    var portalToRename by remember { mutableStateOf<SavedPortal?>(null) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = SectionCardShape
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = stringResource(R.string.saved_portals_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (portals.isEmpty()) {
                Text(
                    text = stringResource(R.string.saved_portals_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                portals.forEachIndexed { index, portal ->
                    if (index > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    val isCurrent = currentAddress == portal.id
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isCurrent) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                            } else {
                                MaterialTheme.colorScheme.surface
                            }
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = portal.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (isCurrent) {
                                    Text(
                                        text = stringResource(R.string.active),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Text(
                                text = portal.id,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (portal.model.isNotBlank() && portal.model != portal.name) {
                                Text(
                                    text = portal.model,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(
                                    onClick = { onConnect(portal) },
                                    enabled = !isCurrent,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Text(
                                        if (isCurrent) {
                                            stringResource(R.string.active)
                                        } else {
                                            stringResource(R.string.connect)
                                        }
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(onClick = { portalToRename = portal }) {
                                    Text(stringResource(R.string.rename))
                                }
                                TextButton(
                                    onClick = { onRemove(portal) },
                                    enabled = !isCurrent
                                ) {
                                    Text(stringResource(R.string.remove))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    portalToRename?.let { portal ->
        RenamePortalDialog(
            portal = portal,
            onDismiss = { portalToRename = null },
            onSave = { name ->
                onRename(portal, name)
                portalToRename = null
            }
        )
    }
}

@Composable
private fun RenamePortalDialog(
    portal: SavedPortal,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var name by remember(portal.id) { mutableStateOf(portal.name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.portal_rename_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.portal_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = portal.id,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name) },
                enabled = name.isNotBlank()
            ) {
                Text(stringResource(R.string.profile_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

@Composable
private fun ConnectionSection(
    ipAddress: String,
    port: String,
    connectionState: ConnectionState,
    connectionMethod: String?,
    deviceStatus: DeviceStatus?,
    onIpChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onScanPortals: () -> Unit
) {
    val isConnected = connectionState is ConnectionState.Connected
    val ipSummary = ipAddress.ifBlank { "-" }
    val portSummary = port.ifBlank { "5555" }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        if (isConnected) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.connection_status_connected),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "$ipSummary:$portSummary",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        TextButton(
                            onClick = onDisconnect,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(stringResource(R.string.disconnect))
                        }
                    }

                    if (deviceStatus != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = connectionMethod ?: stringResource(R.string.connection_method_adb),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.storage_label),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = formatStorage(deviceStatus),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.ram_label),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = formatRam(deviceStatus.ramFreeBytes),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onScanPortals,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(stringResource(R.string.network_portals_title))
                    }
                }
            }
        } else {
            Text(
                text = stringResource(R.string.connect_over_wifi),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = ipAddress,
                    onValueChange = onIpChange,
                    label = { Text(stringResource(R.string.ip_address_label)) },
                    placeholder = { Text(stringResource(R.string.ip_address_placeholder)) },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp),
                    enabled = connectionState !is ConnectionState.Connecting
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { if (it.all { c -> c.isDigit() }) onPortChange(it) },
                    label = { Text(stringResource(R.string.port_label)) },
                    singleLine = true,
                    modifier = Modifier
                        .width(92.dp)
                        .heightIn(min = 56.dp),
                    enabled = connectionState !is ConnectionState.Connecting
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = SectionCardShape,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                )
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    val statusText = when (connectionState) {
                        is ConnectionState.Connected -> stringResource(R.string.connection_status_connected)
                        is ConnectionState.Connecting -> stringResource(R.string.connection_status_connecting)
                        is ConnectionState.Error -> stringResource(R.string.connection_status_failed)
                        ConnectionState.Disconnected -> stringResource(R.string.connection_status_ready)
                    }

                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "IP: ${ipAddress.ifBlank { "-" }} • ${port.ifBlank { "5555" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            when (connectionState) {
                is ConnectionState.Disconnected -> {
                    Button(
                        onClick = onConnect,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = ipAddress.isNotBlank(),
                        shape = RoundedCornerShape(18.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(stringResource(R.string.connect_to_portal))
                    }
                }
                is ConnectionState.Connecting -> {
                    Button(
                        onClick = {},
                        modifier = Modifier.fillMaxWidth(),
                        enabled = false,
                        shape = RoundedCornerShape(18.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.connection_status_connecting))
                    }
                }
                is ConnectionState.Connected -> Unit
                is ConnectionState.Error -> {
                    Button(
                        onClick = onConnect,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(stringResource(R.string.retry))
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onScanPortals,
                modifier = Modifier.fillMaxWidth(),
                enabled = connectionState !is ConnectionState.Connecting,
                shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(stringResource(R.string.network_portals_title))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstalledAppsTab(
    apps: List<InstalledApp>,
    connectionState: ConnectionState,
    installState: InstallState,
    externalInstallBusy: Boolean,
    context: Context,
    selectedUri: Uri?,
    onPickFile: () -> Unit,
    onInstall: (InstalledApp) -> Unit,
    onInstallCustomApk: (Uri) -> Unit
) {
    val installBusy = installState is InstallState.Installing || externalInstallBusy
    var searchQuery by remember { mutableStateOf("") }
    var pendingConfirmation by remember { mutableStateOf<InstalledApp?>(null) }
    var sortBySize by remember { mutableStateOf(false) }
    val sortLabel = if (sortBySize) {
        stringResource(R.string.sort_size_compact)
    } else {
        stringResource(R.string.sort_name_compact)
    }

    val filteredApps = remember(apps, searchQuery, sortBySize) {
        val base = if (searchQuery.isBlank()) {
            apps
        } else {
            apps.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                    it.packageName.contains(searchQuery, ignoreCase = true)
            }
        }
        if (sortBySize) {
            base.sortedByDescending { it.apkSize }
        } else {
            base.sortedBy { it.name.lowercase() }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        text = stringResource(R.string.search_app_label),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                singleLine = true,
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        TextButton(
                            onClick = { searchQuery = "" },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("X")
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { sortBySize = !sortBySize },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
                ) {
                    Text(sortLabel)
                }
                OutlinedButton(
                    onClick = onPickFile,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .width(104.dp)
                        .height(48.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
                ) {
                    Text(stringResource(R.string.choose_apk_compact))
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        if (selectedUri != null) {
            val fileName = remember(selectedUri) {
                ContentUriHelper.getDisplayName(context, selectedUri)
                    ?: context.getString(R.string.unknown_apk_file)
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.selected_file_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = fileName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Button(
                        onClick = { onInstallCustomApk(selectedUri) },
                        enabled = connectionState is ConnectionState.Connected &&
                            !installBusy,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        if (installBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.install_on_portal))
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }

        if (apps.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.loading_installed_apps),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else if (filteredApps.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.no_apps_found, searchQuery),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredApps, key = { it.packageName }) { app ->
                    InstalledAppItem(
                        app = app,
                        context = context,
                        isConnected = connectionState is ConnectionState.Connected,
                        isInstalling = installBusy,
                        onInstall = {
                            val totalSize = app.apkSize +
                                (app.splitSourceDirs?.sumOf { java.io.File(it).length() } ?: 0L)
                            if (totalSize > LARGE_APP_SIZE_THRESHOLD_BYTES) {
                                pendingConfirmation = app
                            } else {
                                onInstall(app)
                            }
                        }
                    )
                }
            }
        }
    }

    pendingConfirmation?.let { app ->
        val totalSize = app.apkSize + (app.splitSourceDirs?.sumOf { java.io.File(it).length() } ?: 0L)
        val hasRiskySplits = (
            app.splitSourceDirs?.any { path ->
                SPLIT_APK_RISK_KEYWORDS.any { keyword -> path.contains(keyword, ignoreCase = true) }
            } == true
        )
        AlertDialog(
            onDismissRequest = { pendingConfirmation = null },
            title = { Text(stringResource(R.string.confirm_install_title)) },
            text = {
                val warning = if (hasRiskySplits) {
                    stringResource(R.string.confirm_install_split_warning)
                } else {
                    ""
                }
                Text(
                    stringResource(
                        R.string.confirm_install_message,
                        app.name,
                        formatFileSize(totalSize)
                    ) + warning
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onInstall(app)
                        pendingConfirmation = null
                    },
                    enabled = !installBusy
                ) {
                    Text(stringResource(R.string.install))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingConfirmation = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}


@Composable
private fun WirelessAdbTab(
    connectionState: ConnectionState,
    usbStatus: UsbPortalStatus,
    wirelessAdbState: WirelessAdbState,
    port: String,
    onRefreshUsb: () -> Unit,
    onRequestUsbPermission: () -> Unit,
    onConnectUsb: () -> Unit,
    onEnableWirelessAdb: () -> Unit
) {
    val isConnected = connectionState is ConnectionState.Connected
    val isUsbConnecting = connectionState is ConnectionState.Connecting
    val isEnabling = wirelessAdbState is WirelessAdbState.Enabling

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = SectionCardShape,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = stringResource(R.string.wireless_setup_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.wireless_setup_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.usb_otg_recommendation),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    if (usbStatus.deviceLabel != null) {
                        Text(
                            text = usbStatus.deviceLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Text(
                        text = usbStatus.details,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (usbStatus.portalDeviceDetected) {
                        if (!usbStatus.hasPermission) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = onRequestUsbPermission,
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Text(stringResource(R.string.grant_usb_permission))
                            }
                        } else if (usbStatus.adbCapableDeviceDetected && connectionState !is ConnectionState.Connected) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = onConnectUsb,
                                enabled = !isUsbConnecting,
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                if (isUsbConnecting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.usb_connecting))
                                } else {
                                    Text(stringResource(R.string.connect_usb))
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    TextButton(
                        onClick = onRefreshUsb,
                        enabled = !isUsbConnecting,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text(stringResource(R.string.refresh_usb))
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = SectionCardShape,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.wireless_adb_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = stringResource(R.string.wireless_adb_port, port.ifBlank { "5555" }),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = onEnableWirelessAdb,
                            enabled = isConnected && !isEnabling,
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            if (isEnabling) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.enabling))
                            } else {
                                Text(stringResource(R.string.enable))
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (isConnected) {
                            stringResource(R.string.wireless_adb_connected_hint)
                        } else {
                            stringResource(R.string.wireless_adb_usb_hint)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.wireless_setup_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

    }
}

@Composable
private fun WirelessAdbGuideTab(
    connectionState: ConnectionState,
    connectionMethod: String?,
    usbStatus: UsbPortalStatus,
    wirelessAdbState: WirelessAdbState,
    port: String,
    onRefreshUsb: () -> Unit,
    onRequestUsbPermission: () -> Unit,
    onConnectUsb: () -> Unit,
    onEnableWirelessAdb: () -> Unit
) {
    val context = LocalContext.current
    var showDiagnostics by remember { mutableStateOf(false) }
    val isUsbConnected = connectionState is ConnectionState.Connected &&
        connectionMethod == context.getString(R.string.connection_method_usb)
    val isConnecting = connectionState is ConnectionState.Connecting
    val isEnabling = wirelessAdbState is WirelessAdbState.Enabling
    val currentStep = when {
        isUsbConnected || isEnabling -> 4
        !usbStatus.portalDeviceDetected -> 1
        !usbStatus.adbCapableDeviceDetected -> 2
        else -> 3
    }
    val stepTitle = when (currentStep) {
        1 -> stringResource(R.string.usb_step_connect_title)
        2 -> stringResource(R.string.usb_step_enable_adb_title)
        3 -> stringResource(R.string.usb_step_authorize_title)
        else -> stringResource(R.string.usb_step_wireless_title)
    }
    val stepDescription = when (currentStep) {
        1 -> stringResource(R.string.usb_step_connect_description)
        2 -> stringResource(R.string.usb_step_enable_adb_description)
        3 -> stringResource(
            if (usbStatus.hasPermission) {
                R.string.usb_step_connect_app_description
            } else {
                R.string.usb_step_authorize_description
            }
        )
        else -> stringResource(R.string.usb_step_wireless_description)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = SectionCardShape,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.wireless_setup_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.usb_step_counter, currentStep),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stepTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stepDescription,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (currentStep == 1) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.usb_otg_recommendation),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    when {
                        currentStep == 3 && !usbStatus.hasPermission -> {
                            Button(
                                onClick = onRequestUsbPermission,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                Text(stringResource(R.string.grant_usb_permission))
                            }
                        }
                        currentStep == 3 -> {
                            Button(
                                onClick = onConnectUsb,
                                enabled = !isConnecting,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                if (isConnecting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.usb_connecting))
                                } else {
                                    Text(stringResource(R.string.connect_usb))
                                }
                            }
                        }
                        currentStep == 4 -> {
                            Button(
                                onClick = onEnableWirelessAdb,
                                enabled = isUsbConnected && !isEnabling,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                if (isEnabling) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.enabling))
                                } else {
                                    Text(stringResource(R.string.enable_wireless_adb))
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.wireless_setup_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = { showDiagnostics = !showDiagnostics },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text(
                            stringResource(
                                if (showDiagnostics) {
                                    R.string.usb_hide_diagnostics
                                } else {
                                    R.string.usb_show_diagnostics
                                }
                            )
                        )
                    }
                    if (showDiagnostics) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                usbStatus.deviceLabel?.let { label ->
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Text(
                                    text = usbStatus.details,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = stringResource(
                                        R.string.wireless_adb_port,
                                        port.ifBlank { "5555" }
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                TextButton(
                                    onClick = onRefreshUsb,
                                    enabled = !isConnecting,
                                    modifier = Modifier.align(Alignment.End)
                                ) {
                                    Text(stringResource(R.string.refresh_usb))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PortalScannerDialog(
    discoveryState: PortalDiscoveryState,
    port: String,
    onScan: () -> Unit,
    onConnect: (DiscoveredPortal) -> Unit,
    onDismiss: () -> Unit
) {
    val isScanning = discoveryState is PortalDiscoveryState.Scanning

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.network_portals_title)) },
        text = {
            when (discoveryState) {
                is PortalDiscoveryState.Idle -> {
                    Text(stringResource(R.string.network_portals_description))
                }
                is PortalDiscoveryState.Scanning -> {
                    Column {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            stringResource(
                                R.string.discovery_progress,
                                discoveryState.scanned,
                                discoveryState.total,
                                discoveryState.found
                            )
                        )
                    }
                }
                is PortalDiscoveryState.Success -> {
                    if (discoveryState.portals.isEmpty()) {
                        Text(
                            stringResource(
                                R.string.discovery_no_portals,
                                port.ifBlank { "5555" }
                            )
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(
                                items = discoveryState.portals,
                                key = { "${it.ip}:${it.port}" }
                            ) { portal ->
                                DiscoveredPortalItem(
                                    portal = portal,
                                    onConnect = { onConnect(portal) }
                                )
                            }
                        }
                    }
                }
                is PortalDiscoveryState.Failed -> {
                    Text(discoveryState.message)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onScan, enabled = !isScanning) {
                Text(
                    if (isScanning) {
                        stringResource(R.string.searching)
                    } else {
                        stringResource(R.string.search)
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun DiscoveredPortalItem(
    portal: DiscoveredPortal,
    onConnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = portal.displayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "IP: ${portal.ip}:${portal.port}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val subtitle = listOf(portal.brand, portal.model)
                .filter { it.isNotBlank() && it != "unknown" }
                .joinToString(" • ")
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(
                onClick = onConnect,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(stringResource(R.string.connect))
            }
        }
    }
}

@Composable
private fun InstalledAppItem(
    app: InstalledApp,
    context: Context,
    isConnected: Boolean,
    isInstalling: Boolean,
    onInstall: () -> Unit
) {
    val isPortalCompatible = app.minSdkVersion <= PortalDeviceConfig.MAX_SDK
    val hasSplitApks = !app.splitSourceDirs.isNullOrEmpty()
    val pm = context.packageManager
    val icon by produceState<ImageBitmap?>(initialValue = null, app.packageName) {
        value = withContext(Dispatchers.IO) {
            try {
                // LoadApplicationIcon returneaza un Drawable; il convertim in ImageBitmap.
                val drawable = pm.getApplicationIcon(app.packageName)
                val bitmap = android.graphics.Bitmap.createBitmap(
                    drawable.intrinsicWidth.coerceAtLeast(64),
                    drawable.intrinsicHeight.coerceAtLeast(64),
                    android.graphics.Bitmap.Config.ARGB_8888
                )
                val canvas = android.graphics.Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bitmap.asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon?.let { image ->
                androidx.compose.foundation.Image(
                    bitmap = image,
                    contentDescription = app.name,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatFileSize(app.apkSize),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (app.minSdkVersion > PortalDeviceConfig.MAX_SDK) {
                Text(
                    text = stringResource(
                        R.string.portal_api_incompatible,
                        app.minSdkVersion,
                        PortalDeviceConfig.MAX_SDK
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
                }
                if (hasSplitApks) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.tertiaryContainer)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = stringResource(
                                R.string.split_apk_badge,
                                app.splitSourceDirs?.size ?: 0
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = onInstall,
                enabled = isConnected && !isInstalling && isPortalCompatible,
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(stringResource(R.string.install))
            }
        }
    }
}

/**
 * Calculeaza un factor de subesantionare (inSampleSize) pentru a decoda
 * o imagine la o rezolutie mult mai mica decat originalul, evitand
 * incarcarea integrala in memorie a unor poze de rezolutie mare doar
 * pentru un thumbnail mic.
 */
private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    val (height, width) = options.outHeight to options.outWidth
    var inSampleSize = 1
    if (height > reqHeight || width > reqWidth) {
        val halfHeight = height / 2
        val halfWidth = width / 2
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}

/**
 * Incarca asincron (pe Dispatchers.IO) un thumbnail redus pentru un Uri
 * de poza, ca sa nu blocheze UI-ul si sa nu consume memorie excesiva.
 * Content resolver-ul este deschis de doua ori: o data pentru dimensiuni
 * si o data pentru decodarea efectiva, deoarece stream-urile content://
 * nu sunt garantat resetabile sau repositionabile.
 */
@Composable
private fun rememberPhotoThumbnail(context: Context, uri: Uri): ImageBitmap? {
    val state = produceState<ImageBitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            try {
                val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, boundsOptions)
                }
                val sampleSize = calculateInSampleSize(boundsOptions, 150, 150)
                val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, decodeOptions)
                }?.asImageBitmap()
            } catch (e: Exception) {
                null
            }
        }
    }
    return state.value
}

@Composable
private fun PhotoThumbnail(uri: Uri) {
    val context = LocalContext.current
    val thumbnail = rememberPhotoThumbnail(context, uri)

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}

@Composable
private fun HistoryTab(history: List<HistoryEntry>) {
    if (history.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.history_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val dateFormat = remember {
        java.text.SimpleDateFormat("dd MMM, HH:mm:ss", java.util.Locale.getDefault())
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(history) { entry ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (entry.success) "+" else "-",
                        color = if (entry.success) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(24.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = entry.description,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = dateFormat.format(java.util.Date(entry.timestamp)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PortalAppsTab(
    packages: List<String>,
    apps: List<PortalAppInfo>,
    isLoading: Boolean,
    uninstallState: UninstallState,
    onRefresh: () -> Unit,
    onUninstall: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.portal_apps_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
            OutlinedButton(
                onClick = onRefresh,
                enabled = !isLoading,
                shape = RoundedCornerShape(18.dp)
            ) {
                Text(
                    if (isLoading) {
                        stringResource(R.string.loading)
                    } else {
                        stringResource(R.string.refresh)
                    }
                )
            }
        }

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (packages.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.portal_apps_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            val displayApps = if (apps.isNotEmpty()) {
                apps
            } else {
                packages.map {
                    PortalAppInfo(it, it, "-", 0L, 0L, null)
                }
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(displayApps, key = { it.packageName }) { app ->
                    val packageName = app.packageName
                    val uninstallInProgress = uninstallState as? UninstallState.InProgress
                    val isThisUninstalling = uninstallInProgress?.packageName == packageName
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.size(44.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = app.name.take(1).uppercase(),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = app.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = packageName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = stringResource(
                                        R.string.portal_app_details,
                                        app.versionName,
                                        formatFileSize(app.sizeBytes)
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = { onUninstall(packageName) },
                                enabled = !isThisUninstalling,
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Text(
                                    if (isThisUninstalling) {
                                        stringResource(R.string.loading_ellipsis)
                                    } else {
                                        stringResource(R.string.uninstall)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MoreTab(
    context: Context,
    history: List<HistoryEntry>,
    selectedLanguage: AppLanguage,
    onSelectLanguage: (AppLanguage) -> Unit
) {
    var selectedSection by remember { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedSection) {
            Tab(
                selected = selectedSection == 0,
                onClick = { selectedSection = 0 },
                text = { Text(stringResource(R.string.tab_history)) }
            )
            Tab(
                selected = selectedSection == 1,
                onClick = { selectedSection = 1 },
                text = { Text(stringResource(R.string.tab_settings)) }
            )
        }

        if (selectedSection == 0) {
            HistoryTab(history)
        } else {
            SettingsTab(
                context = context,
                selectedLanguage = selectedLanguage,
                onSelectLanguage = onSelectLanguage
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTab(
    context: Context,
    selectedLanguage: AppLanguage,
    onSelectLanguage: (AppLanguage) -> Unit
) {
    val versionName = remember(context) {
        runCatching {
            val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            pkgInfo.versionName ?: "1.1.0"
        }.getOrDefault("1.1.0")
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = SectionCardShape
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.settings_language_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.settings_language_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    val systemLanguage = AppLanguage.SYSTEM
                    FilterChip(
                        selected = selectedLanguage == systemLanguage,
                        onClick = { onSelectLanguage(systemLanguage) },
                        label = {
                            Text(
                                text = stringResource(systemLanguage.labelRes),
                                modifier = Modifier.fillMaxWidth(),
                                maxLines = 1
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AppLanguage.entries
                            .filterNot { it == systemLanguage }
                            .forEach { language ->
                                FilterChip(
                                    selected = selectedLanguage == language,
                                    onClick = { onSelectLanguage(language) },
                                    label = {
                                        Text(
                                            text = stringResource(language.labelRes),
                                            modifier = Modifier.fillMaxWidth(),
                                            maxLines = 1
                                        )
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = SectionCardShape
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.settings_about_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.settings_app_name_value, stringResource(R.string.app_name)),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.settings_app_version_value, versionName),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.settings_portal_support_value),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun OsUpdateBlockCard(
    connectionState: ConnectionState,
    state: OsUpdateState,
    onRefresh: () -> Unit,
    onSetBlocked: (Boolean) -> Unit
) {
    LaunchedEffect(connectionState) {
        if (connectionState is ConnectionState.Connected) onRefresh()
    }

    val isConnected = connectionState is ConnectionState.Connected
    val isLoading = state is OsUpdateState.Loading
    val status = state as? OsUpdateState.Ready

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = SectionCardShape
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.update_block_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.update_block_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = status?.status?.isBlocked == true,
                    onCheckedChange = onSetBlocked,
                    enabled = isConnected && !isLoading && status?.status?.supportedPackages?.isNotEmpty() == true
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            when {
                !isConnected -> Text(
                    text = stringResource(R.string.update_block_connect_first),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                isLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.update_block_checking), style = MaterialTheme.typography.labelSmall)
                }
                state is OsUpdateState.Failed -> Text(
                    text = state.message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
                status?.status?.supportedPackages?.isEmpty() == true -> Text(
                    text = stringResource(R.string.update_block_not_supported),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                status != null -> Text(
                    text = stringResource(
                        if (status.status.isBlocked) {
                            R.string.update_block_enabled
                        } else {
                            R.string.update_block_disabled
                        }
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (status.status.isBlocked) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            if (isConnected && !isLoading) {
                TextButton(
                    onClick = onRefresh,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(R.string.refresh))
                }
            }
        }
    }
}

@Composable
private fun AppProfileEditorDialog(
    apps: List<InstalledApp>,
    onSave: (name: String, packageNames: Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var profileName by remember { mutableStateOf("") }
    var selection by remember(apps) { mutableStateOf(emptySet<String>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_create_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = profileName,
                    onValueChange = { profileName = it.take(40) },
                    label = { Text(stringResource(R.string.profile_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = { selection = apps.map { it.packageName }.toSet() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.select_all))
                    }
                    TextButton(
                        onClick = { selection = emptySet() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.select_none))
                    }
                }
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(apps, key = { it.packageName }) { app ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = app.packageName in selection,
                                onCheckedChange = { checked ->
                                    selection = if (checked) {
                                        selection + app.packageName
                                    } else {
                                        selection - app.packageName
                                    }
                                }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = app.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = app.packageName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.profile_app_count, selection.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(profileName.trim(), selection) },
                enabled = profileName.isNotBlank() && selection.isNotEmpty()
            ) {
                Text(stringResource(R.string.profile_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun BackupAppSelectionDialog(
    packages: List<String>,
    isLoading: Boolean,
    selectedPackages: Set<String>?,
    onUseAll: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var selection by remember(packages, selectedPackages) {
        mutableStateOf(
            selectedPackages
                ?.intersect(packages.toSet())
                ?: packages.toSet()
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_choose_apps_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.backup_choose_apps_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = { selection = packages.toSet() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.select_all))
                    }
                    TextButton(
                        onClick = { selection = emptySet() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.select_none))
                    }
                }
                when {
                    isLoading -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    packages.isEmpty() -> {
                        Text(
                            text = stringResource(R.string.portal_apps_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            items(packages, key = { it }) { packageName ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = packageName in selection,
                                        onCheckedChange = { checked ->
                                            selection = if (checked) {
                                                selection + packageName
                                            } else {
                                                selection - packageName
                                            }
                                        }
                                    )
                                    Text(
                                        text = packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        R.string.backup_apps_selected,
                        selection.size
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (selection.size == packages.size) {
                        onUseAll()
                    } else {
                        onConfirm(selection)
                    }
                },
                enabled = !isLoading
            ) {
                Text(stringResource(R.string.save_selection))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun BackupCard(
    isConnected: Boolean,
    backupState: BackupState,
    availablePackageCount: Int,
    isLoadingPackages: Boolean,
    selectedPackages: Set<String>?,
    onChooseApps: () -> Unit,
    onCreateBackup: () -> Unit,
    onRestoreBackup: () -> Unit
) {
    val isWorking = backupState is BackupState.Creating ||
        backupState is BackupState.Restoring ||
        backupState is BackupState.Previewing

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = SectionCardShape
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.backup_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.backup_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.backup_apps_label),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = when {
                            isLoadingPackages -> stringResource(R.string.loading)
                            selectedPackages == null && availablePackageCount > 0 -> stringResource(
                                R.string.backup_apps_all,
                                availablePackageCount
                            )
                            selectedPackages == null -> stringResource(R.string.backup_apps_all_simple)
                            else -> stringResource(
                                R.string.backup_apps_selected,
                                selectedPackages.size
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(
                    onClick = onChooseApps,
                    enabled = isConnected && !isWorking,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(stringResource(R.string.choose))
                }
            }
            Spacer(modifier = Modifier.height(10.dp))

            if (isWorking) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                val progressText = when (backupState) {
                    is BackupState.Creating -> stringResource(
                        R.string.backup_creating_progress,
                        backupState.current,
                        backupState.total,
                        backupState.packageName
                    )
                    is BackupState.Restoring -> stringResource(
                        R.string.backup_restoring_progress,
                        backupState.current,
                        backupState.total,
                        backupState.packageName
                    )
                    is BackupState.Previewing -> stringResource(R.string.backup_preview_progress)
                    else -> ""
                }
                Text(
                    text = progressText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Button(
                onClick = onCreateBackup,
                enabled = isConnected && !isWorking,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.backup_create))
            }
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedButton(
                onClick = onRestoreBackup,
                enabled = isConnected && !isWorking,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.backup_restore))
            }
            if (!isConnected) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.backup_connect_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.backup_limitations),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BackupRestoreConfirmationDialog(
    ready: BackupState.ReadyToRestore,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val preview = ready.preview
    val deviceName = listOf(preview.manufacturer, preview.model)
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .ifBlank { stringResource(R.string.backup_preview_unknown_device) }
    val created = if (preview.createdAt > 0) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(preview.createdAt))
    } else {
        "-"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_preview_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.backup_preview_source, deviceName),
                    fontWeight = FontWeight.SemiBold
                )
                if (preview.sdk.isNotBlank()) {
                    Text(stringResource(R.string.backup_preview_sdk, preview.sdk))
                }
                Text(stringResource(R.string.backup_preview_date, created))
                Text(
                    stringResource(
                        R.string.backup_preview_contents,
                        preview.appCount,
                        preview.settingCount
                    )
                )
                Text(
                    text = stringResource(R.string.backup_preview_warning),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.backup_restore_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

@Composable
private fun PhotosTab(
    selectedUris: List<Uri>,
    isWifiConnected: Boolean,
    photoUploadState: PhotoUploadState,
    onOpenDevices: () -> Unit,
    onPickPhotos: () -> Unit,
    onUpload: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (!isWifiConnected) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.photos_connect_hint),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onOpenDevices) {
                        Text(stringResource(R.string.open_devices))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(onClick = onPickPhotos) {
            Text(stringResource(R.string.select_photos))
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (selectedUris.isNotEmpty()) {
            Text(
                text = stringResource(R.string.selected_photos_count, selectedUris.size),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                gridItems(selectedUris) { uri ->
                    PhotoThumbnail(uri = uri)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            val isUploading = photoUploadState is PhotoUploadState.Uploading

            Button(
                onClick = onUpload,
                enabled = isWifiConnected && !isUploading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isUploading) {
                    val state = photoUploadState as PhotoUploadState.Uploading
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(
                            R.string.uploading_progress,
                            state.current,
                            state.total
                        )
                    )
                } else {
                    Text(stringResource(R.string.send_to_portal))
                }
            }
        } else {
            Text(
                text = stringResource(R.string.select_photos_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}


/**
 * Serializeaza istoricul de operatii ca JSON si il salveaza in
 * SharedPreferences, ca sa persiste intre repornirile aplicatiei.
 * Limitam la ultimele 100 de intrari, ca sa nu crestem nelimitat
 * dimensiunea datelor salvate.
 */
private fun saveHistoryToPrefs(prefs: SharedPreferences, history: List<HistoryEntry>) {
    val jsonArray = org.json.JSONArray()
    history.take(100).forEach { entry ->
        val obj = org.json.JSONObject()
        obj.put("timestamp", entry.timestamp)
        obj.put("description", entry.description)
        obj.put("success", entry.success)
        jsonArray.put(obj)
    }
    prefs.edit().putString("operation_history", jsonArray.toString()).apply()
}

/**
 * Deserializeaza istoricul salvat din SharedPreferences. Daca nu exista
 * date salvate sau JSON-ul este corupt, intoarce o lista goala fara sa
 * arunce o exceptie vizibila utilizatorului.
 */
private fun loadHistoryFromPrefs(prefs: SharedPreferences): List<HistoryEntry> {
    val raw = prefs.getString("operation_history", null) ?: return emptyList()
    return try {
        val jsonArray = org.json.JSONArray(raw)
        (0 until jsonArray.length()).map { i ->
            val obj = jsonArray.getJSONObject(i)
            HistoryEntry(
                timestamp = obj.getLong("timestamp"),
                description = obj.getString("description"),
                success = obj.getBoolean("success")
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}
