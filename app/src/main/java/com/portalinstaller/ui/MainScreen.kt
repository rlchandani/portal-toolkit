package com.portalinstaller.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.portalinstaller.R
import com.portalinstaller.ui.common.ConnectionStatusChip
import com.portalinstaller.ui.common.formatFileSize
import com.portalinstaller.data.AppContainer
import com.portalinstaller.data.UsbPortalHelper
import com.portalinstaller.data.UsbPortalStatus
import com.portalinstaller.data.store.SavedPortal
import com.portalinstaller.data.store.ThemeMode
import com.portalinstaller.util.AppLanguageManager
import com.portalinstaller.viewmodel.AppsViewModel
import com.portalinstaller.viewmodel.MirrorSaveState
import com.portalinstaller.viewmodel.MirrorViewModel
import com.portalinstaller.viewmodel.BackupState
import com.portalinstaller.viewmodel.BackupViewModel
import com.portalinstaller.viewmodel.ConnectionMethod
import com.portalinstaller.viewmodel.ConnectionState
import com.portalinstaller.viewmodel.ConnectionViewModel
import com.portalinstaller.viewmodel.FileManagerViewModel
import com.portalinstaller.viewmodel.FileTransferState
import com.portalinstaller.viewmodel.InstallState
import com.portalinstaller.viewmodel.ScreensaverViewModel
import com.portalinstaller.viewmodel.UploadSummaryState
import com.portalinstaller.viewmodel.PickerEvent
import com.portalinstaller.viewmodel.PortalDiscoveryState
import com.portalinstaller.viewmodel.LogcatViewModel
import com.portalinstaller.viewmodel.PowerControlState
import com.portalinstaller.viewmodel.ShellViewModel
import com.portalinstaller.viewmodel.AppActionState
import com.portalinstaller.viewmodel.PortalAppsSort
import com.portalinstaller.viewmodel.ProfileApplyState
import com.portalinstaller.viewmodel.UninstallState
import com.portalinstaller.viewmodel.WirelessAdbState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.portalinstaller.ui.apps.AppProfileEditorDialog
import com.portalinstaller.ui.apps.AppsHubTab
import com.portalinstaller.ui.devices.BackupAppSelectionDialog
import com.portalinstaller.ui.devices.BackupRestoreConfirmationDialog
import com.portalinstaller.ui.devices.DevicesTab
import com.portalinstaller.ui.devices.PortalScannerDialog
import com.portalinstaller.ui.screensaver.ScreensaverTab
import com.portalinstaller.ui.tools.ToolsTab

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    connectionViewModel: ConnectionViewModel = viewModel(),
    appsViewModel: AppsViewModel = viewModel(),
    backupViewModel: BackupViewModel = viewModel(),
    screensaverViewModel: ScreensaverViewModel = viewModel(),
    shellViewModel: ShellViewModel = viewModel(),
    logcatViewModel: LogcatViewModel = viewModel(),
    mirrorViewModel: MirrorViewModel = viewModel(),
    fileManagerViewModel: FileManagerViewModel = viewModel()
) {
    val context = LocalContext.current
    val container = remember { AppContainer.from(context) }
    val scope = rememberCoroutineScope()

    val connectionState by connectionViewModel.connectionState.collectAsStateWithLifecycle()
    val connectionMethod by connectionViewModel.connectionMethod.collectAsStateWithLifecycle()
    val wirelessAdbState by connectionViewModel.wirelessAdbState.collectAsStateWithLifecycle()
    val deviceStatus by connectionViewModel.deviceStatus.collectAsStateWithLifecycle()
    val portalDiscoveryState by connectionViewModel.portalDiscoveryState.collectAsStateWithLifecycle()
    val osUpdateState by connectionViewModel.osUpdateState.collectAsStateWithLifecycle()
    val connectionEvents by connectionViewModel.connectionEvents.collectAsStateWithLifecycle()
    val powerControlState by connectionViewModel.powerControlState.collectAsStateWithLifecycle()

    val installState by appsViewModel.installState.collectAsStateWithLifecycle()
    val uninstallState by appsViewModel.uninstallState.collectAsStateWithLifecycle()
    val installedApps by appsViewModel.installedApps.collectAsStateWithLifecycle()
    val selectedApkUri by appsViewModel.selectedApkUri.collectAsStateWithLifecycle()
    val portalPackages by appsViewModel.portalPackages.collectAsStateWithLifecycle()
    val portalApps by appsViewModel.portalApps.collectAsStateWithLifecycle()
    val isLoadingPortalPackages by appsViewModel.isLoadingPortalPackages.collectAsStateWithLifecycle()
    val portalPackagesError by appsViewModel.portalPackagesError.collectAsStateWithLifecycle()
    val profileApplyState by appsViewModel.profileApplyState.collectAsStateWithLifecycle()
    val portalAppsSort by appsViewModel.portalAppsSort.collectAsStateWithLifecycle()
    val appActionState by appsViewModel.appActionState.collectAsStateWithLifecycle()

    val backupState by backupViewModel.backupState.collectAsStateWithLifecycle()

    val screensaverState by screensaverViewModel.state.collectAsStateWithLifecycle()
    val screensaverUploadState by screensaverViewModel.uploadState.collectAsStateWithLifecycle()
    val screensaverNotConnected by screensaverViewModel.notConnected.collectAsStateWithLifecycle()

    val shellTranscript by shellViewModel.transcript.collectAsStateWithLifecycle()
    val shellIsRunning by shellViewModel.isRunning.collectAsStateWithLifecycle()
    val shellCommandHistory by shellViewModel.commandHistory.collectAsStateWithLifecycle()

    val mirrorSaveState by mirrorViewModel.saveState.collectAsStateWithLifecycle()
    val mirrorNotConnected by mirrorViewModel.notConnected.collectAsStateWithLifecycle()

    val fileTransferState by fileManagerViewModel.transferState.collectAsStateWithLifecycle()
    val fileNotConnected by fileManagerViewModel.notConnected.collectAsStateWithLifecycle()

    val operationHistory by container.historyStore.history.collectAsStateWithLifecycle(initialValue = emptyList())
    val savedPortals by container.savedPortalStore.portals.collectAsStateWithLifecycle(initialValue = emptyList())
    val appProfiles by container.appProfileStore.profiles.collectAsStateWithLifecycle(initialValue = emptyList())
    val themeMode by container.settingsStore.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
    val useDynamicColor by container.settingsStore.useDynamicColor.collectAsStateWithLifecycle(initialValue = false)

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
        key1 = usbRefreshTrigger,
        key2 = connectionState
    ) {
        // Sondam USB-ul doar cat timp NU suntem conectati: cand exista deja o
        // conexiune, cardul de conectare nu se vede, deci nu are rost sa trezim
        // procesul la fiecare 750 ms si sa recompunem.
        if (connectionState is ConnectionState.Connected) {
            value = UsbPortalHelper.getStatus(context)
            return@produceState
        }
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
        appsViewModel.setSelectedApkUri(uri)
    }

    // File picker pentru poze (selectie multipla). Incarcarea sare automat peste
    // duplicate (dupa nume+dimensiune); rezumatul de la final arata cate au fost
    // sarite. Push-ul merge in folderul de screensaver ales.
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            screensaverViewModel.uploadPhotos(context, uris)
        }
    }

    // Google Photos import: launcher for the OAuth consent screen (when Google needs
    // the user to grant the picker scope), then the events that open Google's picker.
    val photosAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        screensaverViewModel.onAuthResolved(context, result.data)
    }
    LaunchedEffect(Unit) {
        screensaverViewModel.pickerEvents.collect { event ->
            when (event) {
                is PickerEvent.NeedsAuthResolution ->
                    photosAuthLauncher.launch(
                        IntentSenderRequest.Builder(event.pendingIntent).build()
                    )
                is PickerEvent.OpenPicker ->
                    CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(event.uri))
            }
        }
    }
    // When the user returns from Google's picker (or any resume), ask the ViewModel to
    // continue the import. It no-ops unless a picker session is actually pending.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                screensaverViewModel.onPickerReturned(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val backupCreateLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        uri?.let { backupViewModel.createBackup(context, it, selectedBackupPackages) }
    }

    val backupRestoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { backupViewModel.inspectBackup(context, it) }
    }

    // Initializeaza lista de aplicatii la prima incarcare.
    LaunchedEffect(Unit) {
        appsViewModel.loadInstalledApps(context)
    }

    // Reconectare automata la pornire daca avem deja un IP salvat.
    LaunchedEffect(Unit) {
        if (ipAddress.isNotBlank() && connectionState is ConnectionState.Disconnected) {
            connectionViewModel.connect(context, ipAddress, port.toIntOrNull() ?: 5555)
        }
    }

    LaunchedEffect(connectionState, connectionMethod, deviceStatus) {
        if (connectionState is ConnectionState.Connected &&
            connectionMethod == ConnectionMethod.WIFI &&
            ipAddress.isNotBlank()
        ) {
            val connectedPort = port.toIntOrNull() ?: 5555
            val discovered = (portalDiscoveryState as? PortalDiscoveryState.Success)
                ?.portals
                ?.firstOrNull { it.ip == ipAddress && it.port == connectedPort }
            val existing = savedPortals.firstOrNull {
                it.ip == ipAddress && it.port == connectedPort
            }
            container.savedPortalStore.upsert(
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

    // Toate efectele care traduc starile ViewModel in Snackbar sunt grupate in
    // MainScreenSnackbarEffects (fara UI proprie), ca sa tinem acest fisier focusat.
    MainScreenSnackbarEffects(
        snackbarHostState = snackbarHostState,
        context = context,
        connectionState = connectionState,
        installState = installState,
        uninstallState = uninstallState,
        screensaverUploadState = screensaverUploadState,
        screensaverNotConnected = screensaverNotConnected,
        wirelessAdbState = wirelessAdbState,
        portalPackagesError = portalPackagesError,
        portalDiscoveryState = portalDiscoveryState,
        backupState = backupState,
        profileApplyState = profileApplyState,
        appActionState = appActionState,
        powerControlState = powerControlState,
        mirrorSaveState = mirrorSaveState,
        mirrorNotConnected = mirrorNotConnected,
        fileTransferState = fileTransferState,
        fileNotConnected = fileNotConnected,
        connectionViewModel = connectionViewModel,
        appsViewModel = appsViewModel,
        backupViewModel = backupViewModel,
        screensaverViewModel = screensaverViewModel,
        mirrorViewModel = mirrorViewModel,
        fileManagerViewModel = fileManagerViewModel
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { AppBrandHeader() },
                actions = {
                    ConnectionStatusChip(connectionState = connectionState)
                    Spacer(modifier = Modifier.width(12.dp))
                }
            )
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
                                appsViewModel.loadPortalPackages(context)
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(
                            R.string.install_progress_message,
                            formatFileSize(state.sizeBytes),
                            state.elapsedSeconds
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                    TextButton(onClick = { appsViewModel.cancelInstall() }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }

            val isWifiConnected = connectionState is ConnectionState.Connected &&
                connectionMethod == ConnectionMethod.WIFI
            val activePortalName = savedPortals.firstOrNull {
                it.ip == ipAddress && it.port == (port.toIntOrNull() ?: 5555)
            }?.name

            NavHost(
                navController = navController,
                startDestination = PortalRoute.START_DESTINATION,
                // Fara fade in/out la schimbarea taburilor: comutare instantanee.
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None }
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
                    portalAppsSort = portalAppsSort,
                    profiles = appProfiles,
                    profileApplyState = profileApplyState,
                    onOpenDevices = { navigateToTab(PortalRoute.Devices) },
                    onPickFile = {
                        apkPickerLauncher.launch("application/vnd.android.package-archive")
                    },
                    onInstall = { app -> appsViewModel.installApp(context, app) },
                    onInstallCustomApk = { uri -> appsViewModel.installApkFromUri(context, uri) },
                    onRefreshPortalApps = { appsViewModel.loadPortalPackages(context) },
                    onSortPortalApps = { sort -> appsViewModel.setPortalAppsSort(sort) },
                    onUninstall = { packageName -> appsViewModel.uninstallApp(context, packageName) },
                    onLaunchApp = { packageName -> appsViewModel.launchApp(context, packageName) },
                    onForceStopApp = { packageName -> appsViewModel.forceStopApp(context, packageName) },
                    onClearAppData = { packageName -> appsViewModel.clearAppData(context, packageName) },
                    onCreateProfile = { showProfileEditor = true },
                    onApplyProfile = { profile ->
                        appsViewModel.applyProfile(context, profile, installedApps)
                    },
                    onCancelApplyProfile = { appsViewModel.cancelProfileApply() },
                    onDeleteProfile = { profile ->
                        scope.launch { container.appProfileStore.remove(profile.id) }
                    }
                ) }
                composable(PortalRoute.Photos.route) {
                    LaunchedEffect(isWifiConnected) {
                        if (isWifiConnected && screensaverState.folder == null) {
                            screensaverViewModel.loadFolders()
                        }
                    }
                    ScreensaverTab(
                        state = screensaverState,
                        uploadState = screensaverUploadState,
                        isWifiConnected = isWifiConnected,
                        viewModel = screensaverViewModel,
                        onOpenDevices = { navigateToTab(PortalRoute.Devices) },
                        onPickPhotos = { photoPickerLauncher.launch("image/*") },
                        onAddGooglePhotos = { screensaverViewModel.startGooglePhotosImport(context) }
                    )
                }
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
                    connectionEvents = connectionEvents,
                    onRefreshDashboard = { connectionViewModel.refreshDeviceDashboard() },
                    onReboot = { connectionViewModel.reboot(context, recovery = false) },
                    onRebootRecovery = { connectionViewModel.reboot(context, recovery = true) },
                    onScreenOn = { connectionViewModel.setScreenOn(context, on = true) },
                    onScreenOff = { connectionViewModel.setScreenOn(context, on = false) },
                    onLaunchApp = { pkg -> connectionViewModel.launchPortalApp(context, pkg) },
                    onIpChange = { ipAddress = it },
                    onPortChange = { port = it },
                    onConnectManual = {
                        prefs.edit()
                            .putString("last_ip", ipAddress)
                            .putString("last_port", port)
                            .apply()
                        connectionViewModel.connect(context, ipAddress, port.toIntOrNull() ?: 5555)
                    },
                    onConnectSaved = { saved ->
                        ipAddress = saved.ip
                        port = saved.port.toString()
                        prefs.edit()
                            .putString("last_ip", saved.ip)
                            .putString("last_port", saved.port.toString())
                            .apply()
                        connectionViewModel.connect(context, saved.ip, saved.port)
                    },
                    onRemoveSaved = { saved ->
                        scope.launch { container.savedPortalStore.remove(saved.id) }
                    },
                    onRenameSaved = { saved, name ->
                        scope.launch { container.savedPortalStore.rename(saved.id, name) }
                    },
                    onDisconnect = {
                        connectionViewModel.disconnect()
                        appsViewModel.onDisconnected()
                        backupViewModel.onDisconnected()
                        screensaverViewModel.onDisconnected()
                        shellViewModel.onDisconnected()
                        logcatViewModel.onDisconnected()
                        mirrorViewModel.onDisconnected()
                        fileManagerViewModel.onDisconnected()
                    },
                    onScanPortals = {
                        showPortalScanner = true
                        connectionViewModel.discoverWirelessPortals(context, port.toIntOrNull() ?: 5555)
                    },
                    onRefreshUsb = { usbRefreshTrigger++ },
                    onRequestUsbPermission = {
                        if (!UsbPortalHelper.requestPermission(context)) {
                            usbRefreshTrigger++
                        }
                    },
                    onConnectUsb = { connectionViewModel.connectUsb(context) },
                    onEnableWirelessAdb = {
                        connectionViewModel.enableWirelessAdb(context, port.toIntOrNull() ?: 5555)
                    },
                    onRefreshOsUpdateStatus = { connectionViewModel.refreshOsUpdateStatus(context) },
                    onSetOsUpdatesBlocked = { blocked ->
                        connectionViewModel.setOsUpdatesBlocked(context, blocked)
                    },
                    onChooseBackupApps = {
                        appsViewModel.loadPortalPackages(context)
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
                    },
                    onCancelBackup = { backupViewModel.cancelBackup() }
                ) }
                composable(PortalRoute.Tools.route) { ToolsTab(
                    context = context,
                    history = operationHistory,
                    selectedLanguage = selectedLanguage,
                    onSelectLanguage = { language ->
                        if (selectedLanguage != language) {
                            selectedLanguage = language
                            AppLanguageManager.applyLanguage(context, language)
                            (context as? Activity)?.recreate()
                        }
                    },
                    themeMode = themeMode,
                    onSelectThemeMode = { mode ->
                        scope.launch { container.settingsStore.setThemeMode(mode) }
                    },
                    useDynamicColor = useDynamicColor,
                    onToggleDynamicColor = { enabled ->
                        scope.launch { container.settingsStore.setUseDynamicColor(enabled) }
                    },
                    shellTranscript = shellTranscript,
                    shellIsRunning = shellIsRunning,
                    shellCommandHistory = shellCommandHistory,
                    shellQuickCommands = shellViewModel.quickCommands,
                    onShellRun = { command -> shellViewModel.run(context, command) },
                    onShellCancel = { shellViewModel.cancel() },
                    onShellClear = { shellViewModel.clearTranscript() },
                    logcatViewModel = logcatViewModel,
                    mirrorViewModel = mirrorViewModel,
                    fileManagerViewModel = fileManagerViewModel
                ) }
            }
        }
    }

    if (showPortalScanner) {
        PortalScannerDialog(
            discoveryState = portalDiscoveryState,
            port = port,
            onScan = {
                connectionViewModel.discoverWirelessPortals(context, port.toIntOrNull() ?: 5555)
            },
            onConnect = { portal ->
                ipAddress = portal.ip
                prefs.edit()
                    .putString("last_ip", portal.ip)
                    .putString("last_port", portal.port.toString())
                    .apply()
                showPortalScanner = false
                connectionViewModel.connectDiscoveredPortal(context, portal)
            },
            onDismiss = { showPortalScanner = false }
        )
    }

    (backupState as? BackupState.ReadyToRestore)?.let { ready ->
        BackupRestoreConfirmationDialog(
            ready = ready,
            onDismiss = { backupViewModel.clearBackupState() },
            onConfirm = {
                backupViewModel.restoreBackup(context, ready.source) {
                    appsViewModel.loadPortalPackages(context)
                    connectionViewModel.refreshOsUpdateStatus(context)
                }
            }
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
                scope.launch { container.appProfileStore.saveProfile(name, packageNames) }
                showProfileEditor = false
            },
            onDismiss = { showProfileEditor = false }
        )
    }
}
