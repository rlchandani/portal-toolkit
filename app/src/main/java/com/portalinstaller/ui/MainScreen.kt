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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.portalinstaller.R
import com.portalinstaller.data.ContentUriHelper
import com.portalinstaller.data.DeviceStatus
import com.portalinstaller.data.DiscoveredPortal
import com.portalinstaller.data.InstalledApp
import com.portalinstaller.data.PortalDeviceConfig
import com.portalinstaller.data.UsbPortalHelper
import com.portalinstaller.data.UsbPortalStatus
import com.portalinstaller.util.AppLanguage
import com.portalinstaller.util.AppLanguageManager
import com.portalinstaller.viewmodel.ConnectionState
import com.portalinstaller.viewmodel.HistoryEntry
import com.portalinstaller.viewmodel.InstallState
import com.portalinstaller.viewmodel.PhotoUploadState
import com.portalinstaller.viewmodel.PortalDiscoveryState
import com.portalinstaller.viewmodel.PortalViewModel
import com.portalinstaller.viewmodel.UninstallState
import com.portalinstaller.viewmodel.WirelessAdbState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
private val SectionCardShape = RoundedCornerShape(20.dp)

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
    val isLoadingPortalPackages by viewModel.isLoadingPortalPackages.collectAsStateWithLifecycle()
    val portalPackagesError by viewModel.portalPackagesError.collectAsStateWithLifecycle()
    val deviceStatus by viewModel.deviceStatus.collectAsStateWithLifecycle()
    val portalDiscoveryState by viewModel.portalDiscoveryState.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by remember { mutableIntStateOf(0) }
    var usbRefreshTrigger by remember { mutableIntStateOf(0) }
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
        key1 = usbRefreshTrigger
    ) {
        value = UsbPortalHelper.getStatus(context)
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == UsbPortalHelper.ACTION_USB_PERMISSION) {
                    usbRefreshTrigger++
                }
            }
        }

        val filter = IntentFilter(UsbPortalHelper.ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }

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

    Scaffold(
        topBar = {
            TopAppBar(title = { AppBrandHeader() })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Sectiunea de conectare
            ConnectionSection(
                ipAddress = ipAddress,
                port = port,
                connectionState = connectionState,
                connectionMethod = connectionMethod,
                deviceStatus = deviceStatus,
                onIpChange = { ipAddress = it },
                onPortChange = { port = it },
                onConnect = {
                    prefs.edit()
                        .putString("last_ip", ipAddress)
                        .putString("last_port", port)
                        .apply()
                    viewModel.connect(context, ipAddress, port.toIntOrNull() ?: 5555)
                },
                onDisconnect = { viewModel.disconnect() }
            )

            // Bara de progres in timpul instalarii
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

            // Tab-uri scrollabile orizontal pentru ecrane mici.
            ScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 12.dp) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.tab_apps)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.tab_photos)) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text(stringResource(R.string.tab_history)) }
                )
                Tab(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    text = { Text(stringResource(R.string.tab_wireless_adb)) }
                )
                Tab(
                    selected = selectedTab == 4,
                    onClick = {
                        selectedTab = 4
                        viewModel.loadPortalPackages(context)
                    },
                    text = { Text(stringResource(R.string.tab_portal_apps)) }
                )
                Tab(
                    selected = selectedTab == 5,
                    onClick = { selectedTab = 5 },
                    text = { Text(stringResource(R.string.tab_settings)) }
                )
            }

            // Continut tab-uri
            when (selectedTab) {
                0 -> InstalledAppsTab(
                    apps = installedApps,
                    connectionState = connectionState,
                    installState = installState,
                    context = context,
                    selectedUri = selectedApkUri,
                    onPickFile = {
                        apkPickerLauncher.launch("application/vnd.android.package-archive")
                    },
                    onInstall = { app -> viewModel.installApp(context, app) },
                    onInstallCustomApk = { uri -> viewModel.installApkFromUri(context, uri) }
                )
                1 -> PhotosTab(
                    selectedUris = selectedPhotoUris,
                    connectionState = connectionState,
                    photoUploadState = photoUploadState,
                    onPickPhotos = { photoPickerLauncher.launch("image/*") },
                    onUpload = { viewModel.uploadPhotos(context) }
                )
                2 -> HistoryTab(history = operationHistory)
                3 -> WirelessAdbTab(
                    connectionState = connectionState,
                    usbStatus = usbStatus,
                    wirelessAdbState = wirelessAdbState,
                    discoveryState = portalDiscoveryState,
                    port = port,
                    onRefreshUsb = { usbRefreshTrigger++ },
                    onRequestUsbPermission = {
                        if (UsbPortalHelper.requestPermission(context)) {
                            // refresh will also happen via BroadcastReceiver after user response
                        } else {
                            usbRefreshTrigger++
                        }
                    },
                    onConnectUsb = { viewModel.connectUsb(context) },
                    onEnableWirelessAdb = { viewModel.enableWirelessAdb(context, port.toIntOrNull() ?: 5555) },
                    onDiscoverPortals = { viewModel.discoverWirelessPortals(context, port.toIntOrNull() ?: 5555) },
                    onConnectDiscoveredPortal = { portal ->
                        ipAddress = portal.ip
                        prefs.edit().putString("last_ip", portal.ip).putString("last_port", portal.port.toString()).apply()
                        viewModel.connectDiscoveredPortal(context, portal)
                    }
                )
                4 -> PortalAppsTab(
                    packages = portalPackages,
                    isLoading = isLoadingPortalPackages,
                    uninstallState = uninstallState,
                    onRefresh = { viewModel.loadPortalPackages(context) },
                    onUninstall = { packageName -> viewModel.uninstallApp(context, packageName) }
                )
                5 -> SettingsTab(
                    context = context,
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
private fun ConnectionSection(
    ipAddress: String,
    port: String,
    connectionState: ConnectionState,
    connectionMethod: String?,
    deviceStatus: DeviceStatus?,
    onIpChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
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
            }

            if (deviceStatus != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = SectionCardShape,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    )
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(
                            text = connectionMethod ?: stringResource(R.string.connection_method_adb),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${stringResource(R.string.storage_label)}:",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = formatStorage(deviceStatus),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${stringResource(R.string.ram_label)}:",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = formatRam(deviceStatus.ramFreeBytes),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        } else {
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
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstalledAppsTab(
    apps: List<InstalledApp>,
    connectionState: ConnectionState,
    installState: InstallState,
    context: Context,
    selectedUri: Uri?,
    onPickFile: () -> Unit,
    onInstall: (InstalledApp) -> Unit,
    onInstallCustomApk: (Uri) -> Unit
) {
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
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
                    .weight(1f)
                    .height(56.dp)
            )
            OutlinedButton(
                onClick = { sortBySize = !sortBySize },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.height(56.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
            ) {
                Text(sortLabel)
            }
            OutlinedButton(
                onClick = onPickFile,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.height(56.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
            ) {
                Text(stringResource(R.string.choose_apk_compact))
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
                            installState !is InstallState.Installing,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        if (installState is InstallState.Installing) {
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
                        isInstalling = installState is InstallState.Installing,
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
                TextButton(onClick = {
                    onInstall(app)
                    pendingConfirmation = null
                }) {
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
private fun formatStorage(deviceStatus: DeviceStatus): String {
    val free = deviceStatus.storageFreeBytes
    val total = deviceStatus.storageTotalBytes
    return if (free != null && total != null) {
        stringResource(
            R.string.storage_free_of_total,
            formatFileSize(free),
            formatFileSize(total)
        )
    } else {
        stringResource(R.string.unavailable)
    }
}

@Composable
private fun formatRam(ramFreeBytes: Long?): String {
    return ramFreeBytes?.let {
        stringResource(R.string.ram_free_amount, formatFileSize(it))
    } ?: stringResource(R.string.unavailable)
}

@Composable
private fun WirelessAdbTab(
    connectionState: ConnectionState,
    usbStatus: UsbPortalStatus,
    wirelessAdbState: WirelessAdbState,
    discoveryState: PortalDiscoveryState,
    port: String,
    onRefreshUsb: () -> Unit,
    onRequestUsbPermission: () -> Unit,
    onConnectUsb: () -> Unit,
    onEnableWirelessAdb: () -> Unit,
    onDiscoverPortals: () -> Unit,
    onConnectDiscoveredPortal: (DiscoveredPortal) -> Unit
) {
    val isConnected = connectionState is ConnectionState.Connected
    val isEnabling = wirelessAdbState is WirelessAdbState.Enabling
    val isScanning = discoveryState is PortalDiscoveryState.Scanning

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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.usb_portal_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                        OutlinedButton(
                            onClick = onRefreshUsb,
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Text(stringResource(R.string.refresh_usb))
                        }
                    }
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
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Text(stringResource(R.string.connect_usb))
                            }
                        }
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
                                text = stringResource(R.string.network_portals_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = stringResource(R.string.network_portals_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = onDiscoverPortals,
                            enabled = !isScanning,
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Text(
                                if (isScanning) {
                                    stringResource(R.string.searching)
                                } else {
                                    stringResource(R.string.search)
                                }
                            )
                        }
                    }

                    when (val state = discoveryState) {
                        is PortalDiscoveryState.Idle -> Unit
                        is PortalDiscoveryState.Scanning -> {
                            Spacer(modifier = Modifier.height(10.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = stringResource(
                                    R.string.discovery_progress,
                                    state.scanned,
                                    state.total,
                                    state.found
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        is PortalDiscoveryState.Success -> {
                            Spacer(modifier = Modifier.height(10.dp))
                            if (state.portals.isEmpty()) {
                                Text(
                                    text = stringResource(
                                        R.string.discovery_no_portals,
                                        port.ifBlank { "5555" }
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    state.portals.forEach { portal ->
                                        DiscoveredPortalItem(
                                            portal = portal,
                                            onConnect = { onConnectDiscoveredPortal(portal) }
                                        )
                                    }
                                }
                            }
                        }
                        is PortalDiscoveryState.Failed -> Unit
                    }
                }
            }
        }
    }
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
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
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onConnect,
                modifier = Modifier.width(132.dp),
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
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(packages, key = { it }) { packageName ->
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
                            Text(
                                text = packageName,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
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
            pkgInfo.versionName ?: "1.0"
        }.getOrDefault("1.0")
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppLanguage.entries.forEach { language ->
                            FilterChip(
                                selected = selectedLanguage == language,
                                onClick = { onSelectLanguage(language) },
                                label = { Text(stringResource(language.labelRes)) }
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
private fun PhotosTab(
    selectedUris: List<Uri>,
    connectionState: ConnectionState,
    photoUploadState: PhotoUploadState,
    onPickPhotos: () -> Unit,
    onUpload: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))

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
                enabled = connectionState is ConnectionState.Connected && !isUploading,
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

private fun formatFileSize(bytes: Long): String {
    val locale = java.util.Locale.getDefault()
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(locale, "%.1f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format(locale, "%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format(locale, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
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
