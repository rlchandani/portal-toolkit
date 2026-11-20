/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.reminders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.portalinstaller.data.reminders.Reminder
import com.portalinstaller.data.reminders.ReminderBoard
import com.portalinstaller.data.reminders.ReminderCommand
import com.portalinstaller.data.reminders.ReminderCopy
import com.portalinstaller.data.reminders.ReminderDraft
import com.portalinstaller.data.reminders.RemindersRepository
import com.portalinstaller.ui.chores.ConfirmRemoveDialog
import com.portalinstaller.ui.controlcenter.EmptyMessage
import com.portalinstaller.ui.music.Note
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The Portals are read again this often while the page is visible (others tick reminders off). */
private const val POLL_MS = 10_000L

/** What the list callbacks do. */
private class ReminderActions(val toggle: (Reminder) -> Unit, val edit: (Reminder) -> Unit, val delete: (Reminder) -> Unit)

/**
 * Home › Manage › Reminders: the household's shared reminders, open first, then done. Private
 * ones never leave that person's Home; the phone only sees how many there are.
 */
@Composable
fun RemindersPageScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { RemindersRepository.create(context) }
    val state by repository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val snackbars = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<ReminderDraft?>(null) }
    var deleting by remember { mutableStateOf<Reminder?>(null) }
    LaunchedEffect(repository, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) { repository.refresh(); delay(POLL_MS) }
        }
    }
    LaunchedEffect(state.notice?.seq) { state.notice?.let { snackbars.showSnackbar(it.text) } }
    val now = System.currentTimeMillis()
    val zone = ZoneId.systemDefault()
    val send: (ReminderCommand) -> Unit = { command -> scope.launch { if (repository.command(command)) editing = null } }
    val board = state.board
    val actions = ReminderActions(toggle = { send(ReminderCommand.toggle(it)) },
        edit = { r -> board?.let { editing = ReminderDraft.of(r, it) } }, delete = { deleting = it })
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "back") { TextButton(onClick = onBack) { Text("‹ Reminders", style = MaterialTheme.typography.titleLarge) } }
            item(key = "intro") { Note("Reminders show on every Portal. Private ones stay on that person’s Home.") }
            when {
                !state.loaded -> item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                state.portals == 0 -> item(key = "none") { EmptyMessage("No Portals yet", "Pair a Portal to add reminders.") }
                state.notOnHomeWifi -> item(key = "away") { EmptyMessage("Can’t reach your Portals", ReminderCopy.OFFLINE) }
                state.needsUpdate || board == null -> item(key = "update") {
                    EmptyMessage("Update Hearthpane", "Your Portals need the latest Hearthpane for reminders.")
                }
                state.notReady -> item(key = "key") {
                    EmptyMessage("Set up your household first", "Reminders are locked with your household key. " +
                        "Open Settings and pair your Portals so they share it.")
                }
                else -> boardItems(board, now, zone, !state.sending, actions) { editing = ReminderDraft() }
            }
        }
        SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter))
    }
    editing?.let { draft ->
        board?.let { b -> ReminderEditorSheet(draft, b, now, zone, state.sending, onDismiss = { editing = null },
            onSave = { send(ReminderCommand.save(it)) }) }
    }
    deleting?.let { r ->
        ConfirmRemoveDialog("Delete “${r.title}”?", "It leaves every Portal.", onDismiss = { deleting = null }) {
            deleting = null
            send(ReminderCommand.Delete(r.id, r.seq))
        }
    }
}

private fun LazyListScope.boardItems(board: ReminderBoard, now: Long, zone: ZoneId, enabled: Boolean,
                                     actions: ReminderActions, onAdd: () -> Unit) {
    item(key = "add") { Button(onClick = onAdd, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Add reminder") } }
    val open = board.open
    val done = board.finished
    if (open.isEmpty() && done.isEmpty()) item(key = "empty") {
        EmptyMessage("No reminders yet", "Add one and it shows on every Portal${board.portal.takeIf { it.isNotEmpty() }?.let { ", like $it" }.orEmpty()}.")
    }
    if (open.isNotEmpty()) item(key = "open") {
        ReminderListCard("To do", open, now, zone, enabled, actions.toggle, actions.edit, actions.delete)
    }
    if (done.isNotEmpty()) item(key = "done") {
        ReminderListCard("Done", done, now, zone, enabled, actions.toggle, actions.edit, actions.delete)
    }
    if (board.privateCounts.isNotEmpty()) item(key = "private") { PrivateCountsCard(board.privateCounts) }
}
