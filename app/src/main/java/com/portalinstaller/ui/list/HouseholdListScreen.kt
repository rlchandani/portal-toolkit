/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.portalinstaller.data.list.HouseholdListController
import com.portalinstaller.data.list.HouseholdListRowText
import com.portalinstaller.data.list.HouseholdListUiState
import com.portalinstaller.data.list.StatusKind
import com.portalinstaller.viewmodel.HouseholdListViewModel

/** Toolkit's List tab (README Q2): the shared household list, editable offline. */
@Composable
fun HouseholdListScreen(modifier: Modifier = Modifier, viewModel: HouseholdListViewModel = viewModel()) {
    val controller = viewModel.controller
    val state by controller.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(controller) { controller.sync(); onPauseOrDispose { } }
    HouseholdListContent(state, controller, modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HouseholdListContent(state: HouseholdListUiState, controller: HouseholdListController, modifier: Modifier = Modifier) {
    val snackbar = remember { SnackbarHostState() }
    val rowText = remember { HouseholdListRowText() }
    var sheetId by rememberSaveable { mutableStateOf<String?>(null) }
    var doneOpen by rememberSaveable { mutableStateOf(false) }
    val now = remember(state) { System.currentTimeMillis() }
    SnackEffect(state, controller, snackbar)
    val rows = RowActions(controller::toggle, { sheetId = it }, controller::remove, controller::move)
    val meta = { item: com.portalinstaller.data.list.ListItem ->
        rowText.meta(item, item.id in state.view.waiting, item.id in state.dismissedNotes, now)
    }
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().imePadding()) {
            TopBar(onRefresh = controller::sync)
            PullToRefreshBox(isRefreshing = state.syncing, onRefresh = controller::sync, modifier = Modifier.weight(1f)) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { StatusLineRow(state) }
                    statusBanner(state.status.kind)?.let { text -> item { Banner(text) } }
                    val open = state.view.open; val done = state.view.done
                    when {
                        open.isEmpty() && done.isEmpty() -> item { EmptyState("Nothing on the list", "Add something and it shows up on both Portals.") }
                        open.isEmpty() -> item { EmptyState("Everything’s ticked off", null) }
                        else -> item { OpenItemsCard(open, meta, rows) }
                    }
                    if (done.isNotEmpty()) {
                        item {
                            Row(Modifier.fillMaxWidth().clickable { doneOpen = !doneOpen }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                ListGlyph(if (doneOpen) "down" else "chevron", MaterialTheme.colorScheme.onSurfaceVariant, Modifier.size(18.dp))
                                Spacer(Modifier.size(10.dp))
                                Text("Done (${done.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        if (doneOpen) {
                            item { DoneItemsCard(done, meta, rows) }
                            item { FilledTonalButton(onClick = controller::clearDone) { Text("Clear done") } }
                        }
                    }
                }
            }
            ListAddBar(state.usedBefore, controller::add)
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 92.dp))
    }
    sheetId?.let { id ->
        val item = state.view.find(id)
        val close = { sheetId = null }
        ItemSheet(item, item?.let { rowText.sheetLines(it, id in state.view.waiting, now) }.orEmpty(), id in state.dismissedNotes,
            SheetActions(onRename = controller::rename, onMoveTop = { controller.moveToTop(it); close() },
                onRemove = { controller.remove(it); close() }, onUseAlt = { controller.useAlt(it); close() },
                onDismissNote = controller::dismissNote, onClose = close))
    }
}

@Composable
private fun SnackEffect(state: HouseholdListUiState, controller: HouseholdListController, host: SnackbarHostState) {
    val snack = state.snack
    LaunchedEffect(snack?.key) {
        if (snack == null) return@LaunchedEffect
        val result = host.showSnackbar(snack.text, actionLabel = if (snack.undoable) "Undo" else null, duration = SnackbarDuration.Short)
        if (result == SnackbarResult.ActionPerformed) controller.undo() else controller.consumeSnack()
    }
}

@Composable
private fun TopBar(onRefresh: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(start = 22.dp, end = 8.dp).height(72.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("Household list", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        IconButton(onClick = onRefresh, modifier = Modifier.semantics { contentDescription = "Sync now" }) { ListGlyph("refresh") }
    }
}

@Composable
private fun StatusLineRow(state: HouseholdListUiState) {
    val warn = state.status.warning
    val tint = if (warn) WaitAmber else TickGreen
    Row(verticalAlignment = Alignment.CenterVertically) {
        ListGlyph(if (warn) "wifioff" else "check", tint, Modifier.size(20.dp))
        Spacer(Modifier.size(10.dp))
        Text(if (state.syncing && !warn) "Syncing…" else state.status.text, style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun statusBanner(kind: StatusKind): String? = when (kind) {
    StatusKind.AWAY -> "You’re not on home Wi-Fi. New items and ticks wait on this phone and go to both Portals when you’re back."
    StatusKind.OFFLINE -> "No connection. You can keep editing; changes send when you’re back on home Wi-Fi."
    else -> null
}

@Composable
private fun Banner(text: String) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(22.dp)).padding(18.dp)) {
        ListGlyph("info", WaitAmber)
        Spacer(Modifier.size(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun EmptyState(title: String, detail: String?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ListGlyph("list", MaterialTheme.colorScheme.onSurfaceVariant, Modifier.size(36.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        detail?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
