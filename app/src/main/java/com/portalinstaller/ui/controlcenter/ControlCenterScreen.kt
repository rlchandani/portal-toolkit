/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.portalinstaller.data.AppContainer
import com.portalinstaller.data.companion.CompanionPlayback
import com.portalinstaller.data.companion.CompanionConnection
import com.portalinstaller.data.companion.ControlCenterState
import com.portalinstaller.data.companion.SettingsDomain
import com.portalinstaller.data.companion.CompanionTargets
import com.portalinstaller.data.store.ThemeMode
import com.portalinstaller.ui.MainScreen
import com.portalinstaller.ui.common.ChoiceDropdown
import com.portalinstaller.ui.common.ChoiceOption
import com.portalinstaller.ui.common.JoinedAction
import com.portalinstaller.ui.common.JoinedActionGroup
import com.portalinstaller.viewmodel.ControlCenterViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

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
    var pairAddress by rememberSaveable { mutableStateOf("") }
    var pendingRestore by remember { mutableStateOf<RestoreCandidate?>(null) }
    var exportDevice by rememberSaveable { mutableStateOf<String?>(null) }
    var importDevice by rememberSaveable { mutableStateOf<String?>(null) }
    var exportGeneration by rememberSaveable { mutableStateOf(-1L) }
    var importGeneration by rememberSaveable { mutableStateOf(-1L) }
    var photoImportTargets by remember { mutableStateOf<CompanionTargets?>(null) }
    var settingsDomain by rememberSaveable { mutableStateOf("immortal") }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var appQuery by rememberSaveable { mutableStateOf("") }
    var showCatalog by rememberSaveable { mutableStateOf(false) }
    var forgetDevice by remember { mutableStateOf<String?>(null) }
    val visibleSettingsDomains = state.settings.filter { domain ->
        domain.controls.any { showAdvanced || !it.advanced }
    }
    val shownSettingsDomain = settingsDomain.takeIf { selected ->
        visibleSettingsDomains.any { it.id == selected }
    } ?: visibleSettingsDomains.firstOrNull { it.id == "immortal" }?.id
        ?: visibleSettingsDomains.firstOrNull()?.id ?: settingsDomain
    val pageRefreshes = visiblePageRefreshes(tab, detail, showCatalog)
    var toolbarRefresh by remember { mutableStateOf<Job?>(null) }
    val selectedDeviceId = state.activeDeviceId
    val selectedGeneration = state.sessionGeneration
    val connected = state.connected
    val photoLibraryAvailable = state.supports("photoLibrary")
    val operationsVisible = detail == CenterDetail.OPERATIONS
    val controlCenterVisible = !advancedTools
    val pairingVisible = pairDialog && controlCenterVisible

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) {
        if (it.isNotEmpty()) {
            val targets = photoImportTargets
            photoImportTargets = null
            if (targets != null) vm.uploadToTargets(context, it, targets)
            else scope.launch { snackbars.showSnackbar("Photo target capture expired. Select the photos again.") }
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
    LaunchedEffect(vm, lifecycleOwner, selectedDeviceId, selectedGeneration, pageRefreshes, connected, controlCenterVisible,
        photoLibraryAvailable) {
        if (!connected || !controlCenterVisible) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val fresh = vm.state.value
            if (fresh.activeDeviceId == selectedDeviceId && fresh.sessionGeneration == selectedGeneration && fresh.connected) {
                val request = vm.refreshPage(pageRefreshes)
                try { request.join() } finally { request.cancel() }
            }
        }
    }
    // Work stops when the activity is backgrounded; metadata is not polled at frame rate.
    LaunchedEffect(vm, lifecycleOwner, selectedDeviceId, selectedGeneration, operationsVisible, controlCenterVisible) {
        if (!controlCenterVisible) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                delay(when {
                    vm.state.value.connection == CompanionConnection.OFFLINE -> 30_000
                    operationsVisible -> 3_000
                    else -> 15_000
                })
                val fresh = vm.state.value
                if (selectedDeviceId != null && fresh.activeDeviceId == selectedDeviceId &&
                    fresh.sessionGeneration == selectedGeneration && !fresh.busy &&
                    fresh.connection in setOf(CompanionConnection.CONNECTED, CompanionConnection.OFFLINE)) {
                    val request = if (fresh.connected && operationsVisible)
                        vm.refreshOperations() else vm.refreshStatus()
                    try { request.join() } finally { request.cancel() }
                }
            }
        }
    }
    DisposableEffect(vm, lifecycleOwner, pairingVisible) {
        var scanning = false
        fun syncDiscovery() {
            val shouldScan = pairingVisible &&
                lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (shouldScan != scanning) {
                scanning = shouldScan
                if (shouldScan) vm.startDiscovery() else vm.stopDiscovery()
            }
        }
        val observer = LifecycleEventObserver { _, _ -> syncDiscovery() }
        lifecycleOwner.lifecycle.addObserver(observer)
        syncDiscovery()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (scanning) vm.stopDiscovery()
        }
    }
    DisposableEffect(lifecycleOwner, selectedDeviceId, selectedGeneration,
        pageRefreshes, controlCenterVisible) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) toolbarRefresh?.cancel()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            toolbarRefresh?.cancel()
        }
    }
    BackHandler(enabled = detail != CenterDetail.NONE || advancedTools) {
        if (advancedTools) advancedTools = false else detail = CenterDetail.NONE
    }
    if (advancedTools) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TextButton(onClick = { advancedTools = false }, modifier = Modifier.fillMaxWidth()) {
                Text("‹ Immortal control center")
            }
            Box(Modifier.weight(1f)) { MainScreen() }
        }
        return
    }

    Scaffold(
        topBar = {
            CompactPortalHeader(
                state = state,
                refreshing = toolbarRefresh?.isActive == true,
                onSelectDevice = { detail = CenterDetail.NONE; vm.selectDevice(it) },
                onSelectTargets = { vm.selectTargets(it) },
                onAddPortal = { pairDialog = true },
                onDisconnect = { vm.disconnect() },
                onForgetPortal = { forgetDevice = it },
                onRefresh = {
                    if (toolbarRefresh?.isActive != true) {
                        val request = vm.refreshPage(pageRefreshes, includeStatus = true)
                        toolbarRefresh = scope.launch {
                            try { request.join() } finally {
                                request.cancel()
                                if (toolbarRefresh === currentCoroutineContext()[Job]) toolbarRefresh = null
                            }
                        }
                    }
                },
            )
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
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (detail == CenterDetail.NONE && tab in listOf(CenterTab.SETTINGS, CenterTab.PHOTOS)) {
                ManagePortalsBar(state, photos = tab == CenterTab.PHOTOS)
            }
            AnimatedContent(targetState = if (detail == CenterDetail.NONE) tab.name else detail.name,
                transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "control-center") {
                val shown = it
                key(state.activeDeviceId, state.sessionGeneration) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 26.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (shown == CenterDetail.OPERATIONS.name) {
                        item { TextButton(onClick = { detail = CenterDetail.NONE }) { Text("‹ Back") } }
                        item { PageTitle("Activity", "Installations, routines, and retained results.") }
                        activityRows(state.operations, state.busy,
                            onRetry = { vm.retryOperation(it) }, onCancel = { vm.cancelOperation(it) })
                    } else if (shown in CenterDetail.entries.filter { it != CenterDetail.NONE }.map { it.name }) {
                        item {
                            PageSections {
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
                                        if (!state.scenesLoaded) EmptyMessage("Loading scenes", "Your Portal's scenes will appear here.")
                                        else ScenesPanel(state.scenes, state.busy, onSave = { vm.saveScenes(it) },
                                            onRun = { vm.runScene(it) }, revision = state.scenesRevision,
                                            onSaveWithRevision = { candidate, capturedRevision -> vm.saveScenes(candidate, capturedRevision) },
                                            onReload = { vm.refreshScenes() })
                                    }
                                    CenterDetail.JARVIS -> state.jarvis?.let {
                                        PageTitle("Jarvis", "Voice assistant and local wake options.")
                                        JarvisManagementPanel(it, state.busy,
                                            onConfigure = { body -> vm.updateJarvis(body) },
                                            onLaunch = { vm.launchApp("com.portal.assistant") })
                                    } ?: EmptyMessage("Jarvis unavailable", "Refresh the Portal to check assistant support.")
                                    CenterDetail.SNAPSHOTS -> {
                                        PageTitle("Snapshots", "Active Portal only: ${state.deviceName}. Keep a copy of its settings and home layout.")
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
                                    CenterDetail.OPERATIONS -> Unit
                                    CenterDetail.NONE -> Unit
                                }
                            }
                        }
                    } else when (CenterTab.valueOf(shown)) {
                        CenterTab.HOME -> item {
                            PageSections {
                                PageTitle("Your Portal", "Everything you need, within reach.")
                                if (state.connection == CompanionConnection.CONNECTING) {
                                    GroupCard {
                                        Text("Connecting to ${state.activeDevice?.name ?: "your Portal"}",
                                            style = MaterialTheme.typography.titleMedium)
                                        LinearProgressIndicator(Modifier.fillMaxWidth())
                                    }
                                } else if (state.connection == CompanionConnection.OFFLINE && state.activeDevice != null) {
                                    GroupCard {
                                        Text("${state.deviceName} is offline",
                                            style = MaterialTheme.typography.titleMedium)
                                        Text("Your pairing is saved. Check the Portal's power and Wi-Fi. This app retries while it is open.")
                                        Button(onClick = { vm.refreshStatus() }, enabled = !state.busy) {
                                            Text("Retry connection")
                                        }
                                    }
                                } else if (!state.connected) {
                                    GroupCard {
                                        Text(if (state.devices.isEmpty()) "Connect your first Portal" else "Choose a Portal to reconnect",
                                            style = MaterialTheme.typography.titleMedium)
                                        Text("Enable Phone remote in Immortal, then use the address and six-digit PIN shown on your Portal.")
                                        Button(onClick = { pairDialog = true }) { Text("Add a Portal") }
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
                                        JoinedActionGroup(listOf(
                                            JoinedAction("Home", { vm.remoteAction("home") },
                                                enabled = !state.busy, emphasized = true),
                                            JoinedAction("Slideshow", { vm.previewScreensaver() },
                                                enabled = !state.busy),
                                        ))
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
                        }
                        CenterTab.REMOTE -> item {
                            PageSections {
                                PageTitle("Remote", "Active Portal only: ${state.deviceName}. Control it without crossing the room.")
                                RemotePanel(state, vm)
                            }
                        }
                        CenterTab.PHOTOS -> item {
                            PageSections {
                                PageTitle("Photos")
                                Text("Browsing, deletion and slideshow sources: ${state.deviceName} only.", style = MaterialTheme.typography.bodySmall)
                                GroupProgressPanel(state.groupOperations, { vm.retryGroupOperation(it) }, { vm.cancelGroupOperation(it) }, { vm.dismissGroupOperation(it) })
                                Button(onClick = {
                                    photoImportTargets = vm.capturePhotoTargets()
                                    picker.launch("image/*")
                                }, enabled = state.selectedTargets.isNotEmpty() && !state.targetSelectionSaving) { Text("Add photos") }
                                if (state.connected) {
                                    if (state.supports("photos")) PhotoLibraryPanel(
                                        deviceId = state.activeDeviceId.orEmpty(),
                                        files = JSONArray().apply { state.photos.forEach { put(it.raw) } },
                                        folder = state.photoFolder,
                                        working = state.busy,
                                        uploadDetail = "Imports use each Portal's saved folder.",
                                        onImport = {
                                            photoImportTargets = vm.capturePhotoTargets()
                                            picker.launch("image/*")
                                        },
                                        onRefresh = { vm.listPhotos() },
                                        onChangeFolder = { vm.listPhotos(it) },
                                        onActivateFolder = { vm.writeSources(JSONObject().put("folderPath",
                                            if (state.photoListingAll) "/sdcard" else state.photoFolder)) },
                                        onDelete = { vm.deletePhoto(it) },
                                        loadThumbnail = { vm.photoBytes(it) },
                                        allPhotos = if (state.photoListingLoaded) state.photoListingAll
                                            else state.photoBrowseAll && photoLibraryAvailable,
                                        canBrowseAll = state.supports("photoLibrary"),
                                        folders = state.photoFolders,
                                        listingLoaded = state.photoListingLoaded,
                                        truncated = state.photoListingTruncated,
                                        listingError = state.photoListingError,
                                        onAllPhotos = { vm.listAllPhotos() }, showImport = false)
                                    else GroupCard {
                                        Text("Allow photo imports on the Portal", fontWeight = FontWeight.Medium)
                                        Text("Open Control from your phone on the Portal, then choose Allow photo imports. Return here and refresh after granting file access.")
                                        OutlinedButton(onClick = { vm.refreshStatus() }, enabled = !state.busy) {
                                            Text("Check file access")
                                        }
                                    }
                                    state.photoFreeBytes?.let { Text("${it / (1024 * 1024)} MB available", style = MaterialTheme.typography.bodySmall) }
                                    state.sources?.let { PhotoSourcesPanel(it, state.busy,
                                        onSave = { source -> vm.writeSources(source) }, onPreview = { vm.previewScreensaver() }) }
                                    state.schema?.let { RegistrySettingsPanel(it, state.busy || state.selectedTargets.isEmpty(), "", showAdvanced,
                                        onlyDomain = "screensaver", onApply = { domain, body -> vm.writeSettings(domain, body) },
                                        activePortalName = state.deviceName, activeWorking = state.busy || !state.connected,
                                        onActiveApply = { domain, body -> vm.writeActiveSettings(domain, body) }) }
                                } else EmptyMessage("Connect to view photos", "Choose a paired Portal from the device menu.")
                            }
                        }
                        CenterTab.APPS -> {
                            item {
                                PageSections {
                                    PageTitle("Apps", "Launch apps and manage the Immortal catalog.")
                                    OutlinedTextField(appQuery, onValueChange = { appQuery = it }, label = { Text("Find an app") },
                                        singleLine = true, modifier = Modifier.fillMaxWidth())
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        FilterChip(!showCatalog, onClick = { showCatalog = false }, label = { Text("Installed") })
                                        FilterChip(showCatalog, onClick = { showCatalog = true }, label = { Text("Catalog") })
                                    }
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
                                            style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                        if (!showCatalog) Button(
                                            onClick = { vm.launchApp(packageName) },
                                            enabled = state.connected && !state.busy && packageName.isNotBlank()) {
                                            Text("Open")
                                        }
                                    }
                                    val description = app.optString("description").ifBlank { app.optString("versionName") }
                                    if (description.isNotBlank()) Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (showCatalog) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        if (showCatalog && app.optBoolean("installed")) Button(
                                            onClick = { vm.launchApp(packageName) },
                                            enabled = state.connected && !state.busy && packageName.isNotBlank()) { Text("Open") }
                                        if (showCatalog) {
                                            val action = catalogAction(app)
                                            OutlinedButton(onClick = {
                                                vm.startOperation(JSONObject().put("type", action.operation).put("packageName", packageName))
                                                detail = CenterDetail.OPERATIONS
                                            }, enabled = state.connected && !state.busy && state.supports("operations") && packageName.isNotBlank() && action.enabled) {
                                                Text(action.label)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        CenterTab.SETTINGS -> item {
                            SettingsPageContent(query, { query = it }, showAdvanced, { showAdvanced = it },
                                domainPicker = {
                                    SettingsDomainPicker(visibleSettingsDomains, shownSettingsDomain) { settingsDomain = it }
                                },
                                portalSettings = {
                                    GroupProgressPanel(state.groupOperations, { vm.retryGroupOperation(it) }, { vm.cancelGroupOperation(it) }, { vm.dismissGroupOperation(it) })
                                    state.schema?.let { RegistrySettingsPanel(it, state.busy || state.selectedTargets.isEmpty(), query, showAdvanced,
                                        onlyDomain = if (query.isBlank()) shownSettingsDomain else null,
                                        onApply = { domain, body -> vm.writeSettings(domain, body) },
                                        activePortalName = state.deviceName, activeWorking = !state.connected || state.busy,
                                        onActiveApply = { domain, body -> vm.writeActiveSettings(domain, body) }) }
                                        ?: EmptyMessage("Portal settings unavailable", "Connect to load its complete settings registry.")
                                },
                                phonePreferences = { PhonePreferences() },
                                onTools = { advancedTools = true }, onPair = { pairDialog = true })
                        }
                    }
                }
                }
            }
        }
    }
    if (pairDialog) PairPortalDialog(state, pairAddress,
        onAddress = { pairAddress = it },
        onDismiss = { pairDialog = false },
        onPair = { endpoint, pin ->
            vm.pairFromCode(endpoint, pin.takeIf { it.isNotBlank() })
            pairDialog = false
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
private fun PairPortalDialog(
    state: ControlCenterState, address: String, onAddress: (String) -> Unit,
    onDismiss: () -> Unit, onPair: (String, String) -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Pair a Portal") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
    RemoteControlsPanel(
        deviceKey = "${state.activeDeviceId}:${state.sessionGeneration}",
        enabled = state.connected,
        onNavigate = { vm.remoteAction(it) },
        onSlideshow = { vm.previewScreensaver() },
        onMove = { x, y -> vm.moveCursor(x, y) },
        onTap = { vm.tap() },
        onScroll = { vm.scroll(it) },
        textController = vm.remoteText,
        playback = { MediaPanel(state, vm) },
    )
}

@Composable
private fun MediaPanel(state: ControlCenterState, vm: ControlCenterViewModel) {
    val playback = remember(state.nowPlaying) { CompanionPlayback.fromJson(state.nowPlaying) }
    PlaybackPanel(
        playback = playback,
        deviceKey = "${state.activeDeviceId}:${state.sessionGeneration}",
        connected = state.connected,
        busy = state.busy,
        onMedia = { action, position -> vm.media(action, position) },
        onRelativeVolume = { vm.volume(it) },
        onSetVolume = { vm.setVolume(it) },
        loadArtwork = { version ->
            withContext(Dispatchers.IO) { vm.mediaArtBytes(version)?.let(::decodePlaybackArtwork) }
        },
    )
}

/** Decode small covers off the UI thread; compressed-byte and decoded-pixel bounds are independent. */
internal fun decodePlaybackArtwork(bytes: ByteArray): ImageBitmap? {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth !in 1..8192 || bounds.outHeight !in 1..8192) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 256) sample *= 2
    val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
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
private fun SettingsDomainPicker(domains: List<SettingsDomain>, selected: String, onSelect: (String) -> Unit) {
    ChoiceDropdown(
        value = selected,
        options = domains.map { ChoiceOption(it.id, it.title) },
        onSelect = onSelect,
        modifier = Modifier.fillMaxWidth(),
        placeholder = "Choose a category",
        accessibilityLabel = "Settings category",
        matchAnchorWidth = true,
    )
}

@Composable
private fun PhonePreferences() {
    val context = LocalContext.current
    val store = remember(context) { AppContainer.from(context).settingsStore }
    val theme by store.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
    val scope = rememberCoroutineScope()
    PhoneAppearanceSection(theme) { mode -> scope.launch { store.setThemeMode(mode) } }
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
