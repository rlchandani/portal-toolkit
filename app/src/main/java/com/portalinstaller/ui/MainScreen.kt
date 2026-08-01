package com.portalinstaller.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.portalinstaller.R
import com.portalinstaller.ui.common.formatFileSize
import com.portalinstaller.data.AppProfileStore
import com.portalinstaller.data.SavedPortal
import com.portalinstaller.data.SavedPortalStore
import com.portalinstaller.data.UsbPortalHelper
import com.portalinstaller.data.UsbPortalStatus
import com.portalinstaller.util.AppLanguageManager
import com.portalinstaller.viewmodel.BackupState
import com.portalinstaller.viewmodel.ConnectionState
import com.portalinstaller.viewmodel.InstallState
import com.portalinstaller.viewmodel.PhotoUploadState
import com.portalinstaller.viewmodel.PortalDiscoveryState
import com.portalinstaller.viewmodel.PortalViewModel
import com.portalinstaller.viewmodel.ProfileApplyState
import com.portalinstaller.viewmodel.UninstallState
import com.portalinstaller.viewmodel.WirelessAdbState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.portalinstaller.ui.apps.AppProfileEditorDialog
import com.portalinstaller.ui.apps.AppsHubTab
import com.portalinstaller.ui.devices.BackupAppSelectionDialog
import com.portalinstaller.ui.devices.BackupRestoreConfirmationDialog
import com.portalinstaller.ui.devices.DevicesTab
import com.portalinstaller.ui.devices.PortalScannerDialog
import com.portalinstaller.ui.more.MoreTab
import com.portalinstaller.ui.more.loadHistoryFromPrefs
import com.portalinstaller.ui.more.saveHistoryToPrefs
import com.portalinstaller.ui.photos.PhotosTab

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
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val navigateToTab: (PortalRoute) -> Unit = { destination ->
        navController.navigate(destination.route) {
            popUpTo(PortalRoute.START_DESTINATION) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
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
                PortalRoute.bottomNavOrder.forEach { destination ->
                    val selected = currentRoute == destination.route
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navigateToTab(destination)
                            if (destination == PortalRoute.Apps &&
                                connectionState is ConnectionState.Connected
                            ) {
                                viewModel.loadPortalPackages(context)
                            }
                        },
                        icon = {
                            NavigationGlyph(destination.glyphIndex, selected)
                        },
                        label = { Text(stringResource(destination.labelRes)) }
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

            NavHost(
                navController = navController,
                startDestination = PortalRoute.START_DESTINATION
            ) {
                composable(PortalRoute.Apps.route) { AppsHubTab(
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
                    onOpenDevices = { navigateToTab(PortalRoute.Devices) },
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
                ) }
                composable(PortalRoute.Photos.route) { PhotosTab(
                    selectedUris = selectedPhotoUris,
                    isWifiConnected = isWifiConnected,
                    photoUploadState = photoUploadState,
                    onOpenDevices = { navigateToTab(PortalRoute.Devices) },
                    onPickPhotos = { photoPickerLauncher.launch("image/*") },
                    onUpload = { viewModel.uploadPhotos(context) }
                ) }
                composable(PortalRoute.Devices.route) { DevicesTab(
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
                ) }
                composable(PortalRoute.More.route) { MoreTab(
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
                ) }
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
