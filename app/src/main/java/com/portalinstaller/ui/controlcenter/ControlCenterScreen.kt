/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.controlcenter

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.portalinstaller.data.AppContainer
import com.portalinstaller.data.companion.CompanionConnection
import com.portalinstaller.data.companion.ControlCenterState
import com.portalinstaller.data.store.ThemeMode
import com.portalinstaller.ui.MainScreen
import com.portalinstaller.viewmodel.ControlCenterViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private enum class CenterTab(val label: String, val glyph: String) {
    HOME("Home", "home"), REMOTE("Remote", "remote"), PHOTOS("Photos", "photos"),
    APPS("Apps", "apps"), SETTINGS("Settings", "settings")
}
private enum class CenterDetail { NONE, LAYOUT, SCENES, JARVIS, SNAPSHOTS, OPERATIONS }
private data class RestoreCandidate(val deviceId: String, val generation: Long, val snapshot: JSONObject)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlCenterScreen() {
    val context = LocalContext.current
    val factory = remember(context.applicationContext) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ControlCenterViewModel(context.applicationContext) as T
        }
    }
    val vm: ControlCenterViewModel = viewModel(factory = factory)
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbars = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableStateOf(CenterTab.HOME) }
    var detail by rememberSaveable(state.activeDeviceId) { mutableStateOf(CenterDetail.NONE) }
    var advancedTools by rememberSaveable { mutableStateOf(false) }
    var pairDialog by rememberSaveable { mutableStateOf(false) }
    var deviceMenu by remember { mutableStateOf(false) }
    var pairAddress by rememberSaveable { mutableStateOf("") }
    var pendingRestore by remember { mutableStateOf<RestoreCandidate?>(null) }
    var exportDevice by rememberSaveable { mutableStateOf<String?>(null) }
    var importDevice by rememberSaveable { mutableStateOf<String?>(null) }
    var exportGeneration by rememberSaveable { mutableStateOf(-1L) }
    var importGeneration by rememberSaveable { mutableStateOf(-1L) }
    var photoImportDevice by rememberSaveable { mutableStateOf<String?>(null) }
    var photoImportGeneration by rememberSaveable { mutableStateOf(-1L) }
    var settingsDomain by rememberSaveable { mutableStateOf("immortal") }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var appQuery by rememberSaveable { mutableStateOf("") }
    var showCatalog by rememberSaveable { mutableStateOf(false) }
    var forgetDevice by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) {
        if (it.isNotEmpty()) {
            val fresh = vm.state.value
            if (fresh.activeDeviceId == photoImportDevice && fresh.sessionGeneration == photoImportGeneration) vm.uploadUris(context, it)
            else scope.launch { snackbars.showSnackbar("Portal selection changed. Select the photos again.") }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            val capturedDevice = exportDevice
            scope.launch {
                val generation = exportGeneration
                if (capturedDevice == null || vm.state.value.activeDeviceId != capturedDevice || vm.state.value.sessionGeneration != generation) {
                    snackbars.showSnackbar("Portal selection changed. Export again for the selected device.")
                    return@launch
                }
                val snapshot = vm.exportSnapshotResult()
                val fresh = vm.state.value
                if (fresh.activeDeviceId != capturedDevice || fresh.sessionGeneration != generation || snapshot == null) {
                    snackbars.showSnackbar("Snapshot could not be exported. Refresh and try again.")
                } else {
                    runCatching {
                        val bytes = snapshot.toString(2).toByteArray(Charsets.UTF_8)
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                                ?: error("The selected file cannot be written.")
                        }
                    }.fold(
                        onSuccess = { snackbars.showSnackbar("Settings and layout exported.") },
                        onFailure = { snackbars.showSnackbar("The snapshot file could not be written.") })
                }
            }
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val capturedDevice = importDevice
        val generation = importGeneration
        if (uri != null && capturedDevice != null) scope.launch {
            runCatching { readSnapshot(context, uri) }.fold(
                onSuccess = {
                    if (vm.state.value.activeDeviceId == capturedDevice && vm.state.value.sessionGeneration == generation) pendingRestore = RestoreCandidate(capturedDevice, generation, it)
                    else snackbars.showSnackbar("Portal selection changed. Choose the snapshot again.")
                },
                onFailure = { snackbars.showSnackbar("Choose a valid Immortal snapshot under 2 MB.") })
        }
    }

    LaunchedEffect(state.error) {
        state.error?.let { snackbars.showSnackbar(it.message); vm.clearError() }
    }
    LaunchedEffect(state.activeDeviceId, tab, detail, state.connected) {
        if (!state.connected) return@LaunchedEffect
        when {
            detail == CenterDetail.LAYOUT -> vm.refreshLayout()
            detail == CenterDetail.SCENES -> vm.refreshScenes()
            detail == CenterDetail.JARVIS -> vm.refreshJarvis()
            detail == CenterDetail.OPERATIONS -> vm.refreshOperations()
            tab == CenterTab.PHOTOS -> { vm.refreshSources(); vm.listPhotos() }
            tab == CenterTab.SETTINGS -> vm.refreshSettings()
            tab == CenterTab.APPS -> vm.refreshCatalog()
        }
    }
    // Work stops when the activity is backgrounded; metadata is not polled at frame rate.
    LaunchedEffect(lifecycleOwner, state.activeDeviceId, tab, detail) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                delay(if (detail == CenterDetail.OPERATIONS) 3_000 else 15_000)
                val fresh = vm.state.value
                if (fresh.connected && !fresh.busy) {
                    val request = if (detail == CenterDetail.OPERATIONS) vm.refreshOperations() else vm.refreshStatus()
                    try { request.join() } finally { request.cancel() }
                }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { vm.stopDiscovery() } }
    BackHandler(enabled = detail != CenterDetail.NONE || advancedTools) {
        if (advancedTools) advancedTools = false else detail = CenterDetail.NONE
    }
    if (advancedTools) {
        Column(Modifier.fillMaxSize()) {
            TextButton(onClick = { advancedTools = false }, modifier = Modifier.fillMaxWidth()) {
                Text("‹ Immortal control center")
            }
            Box(Modifier.weight(1f)) { MainScreen() }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column {
                    TextButton(onClick = { deviceMenu = true }) {
                        Text(state.activeDevice?.name ?: "Immortal", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.width(6.dp)); Text("⌄")
                    }
                    DropdownMenu(deviceMenu, onDismissRequest = { deviceMenu = false }) {
                        state.devices.forEach { device ->
                            DropdownMenuItem(
                                text = { Text(device.name + if (device.id == state.activeDeviceId) " · Selected" else "") },
                                onClick = { deviceMenu = false; detail = CenterDetail.NONE; vm.selectDevice(device.id) })
                        }
                        DropdownMenuItem(text = { Text("Add a Portal") },
                            onClick = { deviceMenu = false; pairDialog = true; vm.startDiscovery() })
                        state.activeDevice?.let {
                            DropdownMenuItem(text = { Text("Disconnect") },
                                onClick = { deviceMenu = false; vm.disconnect() })
                            DropdownMenuItem(text = { Text("Forget this Portal") },
                                onClick = { deviceMenu = false; forgetDevice = it.id })
                        }
                    }
                }
            }, actions = {
                TextButton(onClick = { vm.refresh() }, enabled = state.activeDeviceId != null && !state.busy) {
                    ControlGlyph("refresh", modifier = Modifier.size(22.dp))
                }
            })
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                CenterTab.entries.forEach { item ->
                    NavigationBarItem(selected = tab == item,
                        onClick = { tab = item; detail = CenterDetail.NONE },
                        icon = { ControlGlyph(item.glyph, modifier = Modifier.size(23.dp)) },
                        label = { Text(item.label) })
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ConnectionStrip(state)
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            AnimatedContent(targetState = if (detail == CenterDetail.NONE) tab.name else detail.name,
                transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "control-center") {
                val shown = it
                key(state.activeDeviceId, state.sessionGeneration) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 26.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (shown in CenterDetail.entries.filter { it != CenterDetail.NONE }.map { it.name }) {
                        item {
                            TextButton(onClick = { detail = CenterDetail.NONE }) { Text("‹ Back") }
                            when (CenterDetail.valueOf(shown)) {
                                CenterDetail.LAYOUT -> state.layout?.let { layout ->
                                    PageTitle("Home layout", "Arrange widgets and favorites on your Portal.")
                                    LayoutEditorPanel(layout, state.busy,
                                        onSave = {
                                            val candidate = JSONObject(it.toString()).apply {
                                                remove("revision"); remove("widgetKinds")
                                            }
                                            vm.updateLayout(candidate, layout.optString("revision").takeIf(String::isNotBlank))
                                        },
                                        onPreview = { vm.remoteAction("home") })
                                } ?: EmptyMessage("Layout unavailable", "Refresh your Portal connection.")
                                CenterDetail.SCENES -> {
                                    PageTitle("Scenes", "Save routines and run them on your Portal.")
                                    ScenesPanel(state.scenes, state.busy, onSave = { vm.saveScenes(it) },
                                        onRun = { vm.runScene(it) })
                                }
                                CenterDetail.JARVIS -> state.jarvis?.let {
                                    PageTitle("Jarvis", "Voice assistant and local wake options.")
                                    JarvisManagementPanel(it, state.busy,
                                        onConfigure = { body -> vm.updateJarvis(body) },
                                        onLaunch = { vm.launchApp("com.portal.assistant") })
                                } ?: EmptyMessage("Jarvis unavailable", "Refresh the Portal to check assistant support.")
                                CenterDetail.SNAPSHOTS -> {
                                    PageTitle("Snapshots", "Keep a copy of your settings and home layout.")
                                    GroupCard {
                                        Text("Snapshots include supported settings and widgets. Pairing tokens, source passwords, and API keys stay private.")
                                        ActionRow("Export snapshot", "Save a JSON file on this phone.", "backup", state.connected && !state.busy) {
                                            exportDevice = state.activeDeviceId
                                            exportGeneration = state.sessionGeneration
                                            export.launch("immortal-${System.currentTimeMillis()}.json")
                                        }
                                        ActionRow("Restore snapshot", "Review before applying to this Portal.", "backup", state.connected && !state.busy) {
                                            importDevice = state.activeDeviceId
                                            importGeneration = state.sessionGeneration
                                            import.launch(arrayOf("application/json", "text/plain", "*/*"))
                                        }
                                    }
                                }
                                CenterDetail.OPERATIONS -> {
                                    PageTitle("Activity", "Installations, routines, and retained results.")
                                    OperationsPanel(state.operations, state.busy,
                                        onRetry = { vm.retryOperation(it) }, onCancel = { vm.cancelOperation(it) })
                                }
                                CenterDetail.NONE -> Unit
                            }
                        }
                    } else when (CenterTab.valueOf(shown)) {
                        CenterTab.HOME -> item {
                            PageTitle("Your Portal", "Everything you need, within reach.")
                            if (!state.connected) {
                                GroupCard {
                                    Text(if (state.devices.isEmpty()) "Connect your first Portal" else "Choose a Portal to reconnect",
                                        style = MaterialTheme.typography.titleMedium)
                                    Text("Enable Phone remote in Immortal, then use the address and six-digit PIN shown on your Portal.")
                                    Button(onClick = { pairDialog = true; vm.startDiscovery() }) { Text("Add a Portal") }
                                    state.devices.forEach { saved ->
                                        ActionRow(saved.name, saved.endpoint, "home") { vm.selectDevice(saved.id) }
                                    }
                                }
                            } else {
                                GroupCard {
                                    Text(state.deviceName, style = MaterialTheme.typography.headlineSmall)
                                    Text(listOfNotNull(state.activeDevice?.model,
                                        state.activeDevice?.apiLevel?.let { "Android API $it" },
                                        state.info?.optJSONObject("app")?.optString("versionName"))
                                        .filter { it.isNotBlank() }.joinToString(" · "),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { vm.remoteAction("home") }, enabled = !state.busy) { Text("Home") }
                                        OutlinedButton(onClick = { vm.previewScreensaver() }, enabled = !state.busy) { Text("Slideshow") }
                                    }
                                }
                                SectionTitle("Manage")
                                GroupCard {
                                    ActionRow("Home layout", "Arrange and resize your widgets.", "layout", state.supports("layout")) { detail = CenterDetail.LAYOUT }
                                    ActionRow("Scenes", "Everyday routines and schedules.", "scenes", state.supports("scenes")) { detail = CenterDetail.SCENES }
                                    ActionRow("Jarvis", "Model and wake settings.", "jarvis", state.supports("jarvis")) { detail = CenterDetail.JARVIS }
                                    ActionRow("Snapshots", "Save and restore your setup.", "backup", state.supports("snapshot")) { detail = CenterDetail.SNAPSHOTS }
                                    ActionRow("Activity", "Progress and results.", "refresh", state.supports("operations")) { detail = CenterDetail.OPERATIONS }
                                }
                                MediaPanel(state, vm)
                            }
                        }
                        CenterTab.REMOTE -> item {
                            PageTitle("Remote", "Control the Portal without crossing the room.")
                            RemotePanel(state, vm)
                        }
                        CenterTab.PHOTOS -> item {
                            PageTitle("Photos", "A collection that stays on your Portal.")
                            if (state.connected) {
                                if (state.supports("photos")) PhotoLibraryPanel(
                                    deviceId = state.activeDeviceId.orEmpty(),
                                    files = JSONArray().apply { state.photos.forEach { put(it.raw) } },
                                    folder = state.photoFolder,
                                    working = state.busy,
                                    uploadDetail = state.photoTransfer?.let {
                                        "${it.completed} of ${it.total} uploaded · ${it.bytesSent / 1024} KB${if (it.running) " · Sending" else ""}" +
                                            if (it.failures.isNotEmpty()) " · ${it.failures.size} failed" else ""
                                    },
                                    onImport = {
                                        photoImportDevice = state.activeDeviceId
                                        photoImportGeneration = state.sessionGeneration
                                        picker.launch("image/*")
                                    },
                                    onRefresh = { vm.listPhotos() },
                                    onDelete = { vm.deletePhoto(it) },
                                    loadThumbnail = { vm.photoBytes(it) })
                                if (state.photoTransfer?.running == true) TextButton(onClick = { vm.cancelPhotoUpload() }) { Text("Cancel transfer") }
                                state.photoTransfer?.failures?.forEach { failure ->
                                    Text("${failure.name}: ${failure.error.message}", color = MaterialTheme.colorScheme.error)
                                }
                                state.photoFreeBytes?.let { Text("${it / (1024 * 1024)} MB available", style = MaterialTheme.typography.bodySmall) }
                                state.sources?.let { PhotoSourcesPanel(it, state.busy,
                                    onSave = { source -> vm.writeSources(source) }, onPreview = { vm.previewScreensaver() }) }
                                state.schema?.let { RegistrySettingsPanel(it, state.busy, "", showAdvanced,
                                    onlyDomain = "screensaver", onApply = { domain, body -> vm.writeSettings(domain, body) }) }
                            } else EmptyMessage("Connect to view photos", "Choose a paired Portal from the device menu.")
                        }
                        CenterTab.APPS -> {
                            item {
                                PageTitle("Apps", "Launch apps and manage the Immortal catalog.")
                                OutlinedTextField(appQuery, onValueChange = { appQuery = it }, label = { Text("Find an app") },
                                    singleLine = true, modifier = Modifier.fillMaxWidth())
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(!showCatalog, onClick = { showCatalog = false }, label = { Text("Installed") })
                                    FilterChip(showCatalog, onClick = { showCatalog = true; vm.refreshCatalog() }, label = { Text("Catalog") })
                                }
                            }
                            val entries = if (showCatalog) catalogItems(state.catalog) else state.apps.objects()
                            val filtered = entries.filter {
                                listOf(it.optString("name"), it.optString("label"), it.optString("id"), it.optString("packageName"))
                                    .any { text -> text.contains(appQuery, ignoreCase = true) }
                            }
                            if (filtered.isEmpty()) item {
                                EmptyMessage("No apps to show", if (state.connected) "Refresh or adjust your search." else "Connect to your Portal.")
                            }
                            items(filtered, key = { app -> app.optString("id").ifBlank { app.optString("packageName", app.optString("pkg", app.toString())) } }) { app ->
                                val packageName = app.optString("packageName").ifBlank { app.optString("pkg").ifBlank { app.optString("package") } }
                                GroupCard {
                                    Row(verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        if (packageName.isNotBlank()) AppIcon(state.activeDeviceId.orEmpty(), packageName, vm)
                                        Text(app.optString("name").ifBlank { app.optString("label", packageName) },
                                            style = MaterialTheme.typography.titleMedium)
                                    }
                                    val description = app.optString("description").ifBlank { app.optString("versionName") }
                                    if (description.isNotBlank()) Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (!showCatalog || app.optBoolean("installed")) Button(
                                            onClick = { vm.launchApp(packageName) },
                                            enabled = state.connected && !state.busy && packageName.isNotBlank()) { Text("Open") }
                                        if (showCatalog) {
                                            val compatible = app.optBoolean("compatible")
                                            OutlinedButton(onClick = {
                                                vm.startOperation(JSONObject().put("type", if (app.optBoolean("installed")) "update" else "install").put("packageName", packageName))
                                                detail = CenterDetail.OPERATIONS
                                            }, enabled = state.connected && !state.busy && state.supports("operations") && packageName.isNotBlank() && compatible
                                                && (!app.optBoolean("installed") || app.optBoolean("updateAvailable"))) {
                                                Text(if (!compatible) "Unavailable on this Portal" else if (app.optBoolean("installed") && !app.optBoolean("updateAvailable")) "Up to date"
                                                    else if (app.optBoolean("installed")) "Update" else "Install")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        CenterTab.SETTINGS -> item {
                            PageTitle("Settings", "Simple everyday controls, with more when you need them.")
                            OutlinedTextField(query, onValueChange = { query = it }, label = { Text("Search settings") },
                                singleLine = true, modifier = Modifier.fillMaxWidth())
                            GroupCard {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Advanced settings", fontWeight = FontWeight.Medium)
                                        Text("Show expert controls from the Portal registry.", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Switch(showAdvanced, onCheckedChange = { showAdvanced = it })
                                }
                            }
                            if (query.isBlank()) SettingsDomainPicker(state, settingsDomain) { settingsDomain = it }
                            state.schema?.let { RegistrySettingsPanel(it, !state.connected || state.busy, query, showAdvanced,
                                onlyDomain = if (query.isBlank()) settingsDomain else null,
                                onApply = { domain, body -> vm.writeSettings(domain, body) }) }
                                ?: EmptyMessage("Portal settings unavailable", "Connect to load its complete settings registry.")
                            SectionTitle("This phone")
                            PhonePreferences()
                            GroupCard {
                                ActionRow("Device tools", "ADB setup, screen mirror, APKs, files, backups, and diagnostics.", "settings") {
                                    advancedTools = true
                                }
                                ActionRow("Add a Portal", "Local Wi-Fi or your existing private VPN endpoint.", "remote") {
                                    pairDialog = true; vm.startDiscovery()
                                }
                            }
                        }
                    }
                }
                }
            }
        }
    }
    if (pairDialog) PairPortalDialog(state, pairAddress,
        onAddress = { pairAddress = it },
        onDismiss = { pairDialog = false; vm.stopDiscovery() },
        onPair = { endpoint, pin ->
            vm.pairFromCode(endpoint, pin.takeIf { it.isNotBlank() })
            pairDialog = false; vm.stopDiscovery()
        })
    pendingRestore?.let { candidate ->
        AlertDialog(onDismissRequest = { pendingRestore = null },
            title = { Text("Restore to ${state.deviceName}?") },
            text = { Text("This applies the supported settings and layout in the selected snapshot. Existing private credentials stay on the Portal.") },
            confirmButton = { TextButton(onClick = {
                pendingRestore = null
                scope.launch {
                    if (vm.state.value.activeDeviceId != candidate.deviceId || vm.state.value.sessionGeneration != candidate.generation) return@launch
                    val snapshot = vm.exportSnapshotResult()
                    val fresh = vm.state.value
                    if (fresh.activeDeviceId == candidate.deviceId && fresh.sessionGeneration == candidate.generation) {
                        val revision = snapshot?.optString("revision")?.takeIf(String::isNotBlank)
                        if (revision != null) vm.restoreSnapshot(candidate.snapshot, revision)
                    }
                }
            }, enabled = state.connected && !state.busy && state.activeDeviceId == candidate.deviceId &&
                state.sessionGeneration == candidate.generation) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text("Cancel") } })
    }
    forgetDevice?.let { id ->
        AlertDialog(onDismissRequest = { forgetDevice = null },
            title = { Text("Forget this Portal?") },
            text = { Text("Remove this phone's saved pairing. You can pair again using a fresh PIN.") },
            confirmButton = { TextButton(onClick = { forgetDevice = null; vm.forgetDevice(id) }) { Text("Forget") } },
            dismissButton = { TextButton(onClick = { forgetDevice = null }) { Text("Cancel") } })
    }
}

@Composable
private fun ConnectionStrip(state: ControlCenterState) {
    val label = when (state.connection) {
        CompanionConnection.UNPAIRED -> "No Portal selected"
        CompanionConnection.CONNECTING -> "Connecting…"
        CompanionConnection.CONNECTED -> "Connected"
        CompanionConnection.OFFLINE -> "Offline · Showing saved information"
        CompanionConnection.UNAUTHORIZED -> "Pairing expired · Pair again"
    }
    Text(label, modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = if (state.connection == CompanionConnection.UNAUTHORIZED) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PairPortalDialog(
    state: ControlCenterState, address: String, onAddress: (String) -> Unit,
    onDismiss: () -> Unit, onPair: (String, String) -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Pair a Portal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("In Immortal, enable Phone remote and show a pairing PIN. Paste its pairing link or enter the address below.")
                OutlinedTextField(address, onValueChange = onAddress, singleLine = true,
                    label = { Text("Address or pairing link") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(pin, onValueChange = { pin = it.filter(Char::isDigit).take(6) },
                    singleLine = true, label = { Text("Six-digit PIN") },
                    supportingText = { Text("Optional if included in the pairing link.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                if (state.discovering) Text("Looking for Portals on your network…", style = MaterialTheme.typography.bodySmall)
                state.discovered.take(5).forEach {
                    TextButton(onClick = { onAddress(it.endpoint) }) { Text(it.name) }
                }
                Text("For access away from home, use an existing VPN or private HTTPS address.",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onPair(address, pin) },
            enabled = address.isNotBlank() && (pin.length == 6 || address.contains("pin="))) { Text("Pair") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun RemotePanel(state: ControlCenterState, vm: ControlCenterViewModel) {
    var keyboard by rememberSaveable { mutableStateOf("") }
    val enabled = state.connected
    GroupCard {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.remoteAction("back") }, enabled = enabled) { Text("Back") }
            Button(onClick = { vm.remoteAction("home") }, enabled = enabled) { Text("Home") }
            OutlinedButton(onClick = { vm.previewScreensaver() }, enabled = enabled) { Text("Slideshow") }
        }
        Box(Modifier.fillMaxWidth().height(220.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp))
            .pointerInput(enabled, state.activeDeviceId) {
                if (enabled) detectDragGestures { change, drag ->
                    change.consume()
                    vm.moveCursor(drag.x, drag.y)
                }
            }
            .pointerInput(enabled, state.activeDeviceId) { if (enabled) detectTapGestures(onTap = { vm.tap() }) },
            contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ControlGlyph("remote", modifier = Modifier.size(36.dp))
                Spacer(Modifier.height(10.dp))
                Text("Touchpad")
                Text("Move to point · Tap to select", style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { vm.scroll("up") }, enabled = enabled) { Text("Scroll up") }
            TextButton(onClick = { vm.scroll("down") }, enabled = enabled) { Text("Scroll down") }
        }
        OutlinedTextField(keyboard, onValueChange = { keyboard = it }, label = { Text("Type on the Portal") },
            modifier = Modifier.fillMaxWidth(), enabled = enabled)
        Button(onClick = { vm.sendText(keyboard); keyboard = "" }, enabled = enabled && keyboard.isNotBlank()) { Text("Send text") }
    }
    MediaPanel(state, vm)
}

@Composable
private fun MediaPanel(state: ControlCenterState, vm: ControlCenterViewModel) {
    val media = state.nowPlaying
    SectionTitle("Now playing")
    GroupCard {
        Text(media?.optString("title")?.ifBlank { "Nothing playing" } ?: "Nothing playing",
            style = MaterialTheme.typography.titleMedium)
        media?.optString("artist")?.takeIf { it.isNotBlank() }?.let { Text(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { vm.media("previous") }, enabled = state.connected) { Text("Previous") }
            Button(onClick = { vm.media("playpause") },
                enabled = state.connected && media?.optBoolean("active") == true) { Text(if (media?.optBoolean("playing") == true) "Pause" else "Play") }
            TextButton(onClick = { vm.media("next") }, enabled = state.connected) { Text("Next") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.volume("down") }, enabled = state.connected) { Text("Volume −") }
            OutlinedButton(onClick = { vm.volume("up") }, enabled = state.connected) { Text("Volume +") }
        }
    }
}

@Composable
private fun AppIcon(deviceId: String, packageName: String, vm: ControlCenterViewModel) {
    val bitmap by produceState<ImageBitmap?>(null, deviceId, packageName) {
        value = withContext(Dispatchers.IO) {
            vm.appIconBytes(packageName)?.let {
                android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap()
            }
        }
    }
    Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            ?: ControlGlyph("apps")
    }
}

@Composable
private fun SettingsDomainPicker(state: ControlCenterState, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(state.settings.firstOrNull { it.id == selected }?.title ?: "Choose a category")
            Spacer(Modifier.width(10.dp)); Text("⌄")
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            state.settings.forEach { domain ->
                DropdownMenuItem(text = { Text(domain.title) },
                    onClick = { onSelect(domain.id); expanded = false })
            }
        }
    }
}

@Composable
private fun OperationsPanel(operations: JSONArray, working: Boolean, onRetry: (String) -> Unit, onCancel: (String) -> Unit) {
    if (operations.length() == 0) EmptyMessage("No recent activity", "Completed actions and errors will appear here.")
    operations.objects().forEach { operation ->
        GroupCard {
            val id = operation.optString("id")
            val status = operation.optString("state", operation.optString("status"))
            Text(operation.optString("label", operation.optString("type", "Operation")), fontWeight = FontWeight.Medium)
            Text(status.replace('_', ' '), color = MaterialTheme.colorScheme.onSurfaceVariant)
            operation.optString("error").takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            operation.optJSONArray("results").objects().forEach { result ->
                Text(result.optString("result", if (result.optBoolean("ok")) "Completed" else "Failed").replace('_', ' '),
                    color = if (result.optBoolean("ok")) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                if (result.optBoolean("backendMayContinue")) Text("The device may still be finishing this action.",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (status in setOf("running", "queued") && operation.optBoolean("cancelSupported")) {
                TextButton(onClick = { onCancel(id) }, enabled = !working) { Text("Cancel") }
            }
            if (status in setOf("failed", "cancelled", "interrupted")) TextButton(onClick = { onRetry(id) }, enabled = !working) { Text("Retry") }
        }
    }
}

@Composable
private fun PhonePreferences() {
    val context = LocalContext.current
    val store = remember(context) { AppContainer.from(context).settingsStore }
    val theme by store.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
    val scope = rememberCoroutineScope()
    GroupCard {
        Text("Appearance", fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(theme == mode, onClick = { scope.launch { store.setThemeMode(mode) } },
                    label = { Text(mode.name.lowercase().replaceFirstChar(Char::titlecase)) })
            }
        }
    }
}

private fun catalogItems(catalog: JSONObject?): List<JSONObject> =
    (catalog?.optJSONArray("apps") ?: catalog?.optJSONArray("items")
        ?: catalog?.optJSONObject("catalog")?.optJSONArray("apps")).objects()

private suspend fun readSnapshot(context: Context, uri: Uri): JSONObject = withContext(Dispatchers.IO) {
    val limit = 2 * 1024 * 1024
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            require(out.size() + read <= limit)
            out.write(buffer, 0, read)
        }
        out.toByteArray()
    } ?: error("File unavailable")
    JSONObject(bytes.toString(Charsets.UTF_8)).also {
        require(it.has("version") || it.has("schemaVersion") || it.has("protocolVersion"))
    }
}
