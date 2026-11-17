/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.chores

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
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
import com.portalinstaller.data.chores.ChoreBoard
import com.portalinstaller.data.chores.ChoreCommand
import com.portalinstaller.data.chores.ChoreCopy
import com.portalinstaller.data.chores.ChoreDraft
import com.portalinstaller.data.chores.ChoreLimits
import com.portalinstaller.data.chores.ChoresRepository
import com.portalinstaller.data.chores.KidDraft
import com.portalinstaller.ui.controlcenter.EmptyMessage
import com.portalinstaller.ui.music.Note
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The Portals are read again this often while the page is visible (ticks arrive from kids). */
private const val POLL_MS = 10_000L

/** Which editor sheet is open. */
private sealed interface Editing {
    data class Kid(val draft: KidDraft) : Editing
    data class Chore(val draft: ChoreDraft) : Editing
}

/**
 * Home › Manage › Chores: OK the stars kids are waiting for, see each kid's week toward their
 * goal (and the last four), and manage kids, rewards and chores. Everything shows on every Portal.
 */
@Composable
fun ChoresPageScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { ChoresRepository.create(context) }
    val state by repository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val snackbars = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<Editing?>(null) }
    LaunchedEffect(repository, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) { repository.refresh(); delay(POLL_MS) }
        }
    }
    LaunchedEffect(state.notice?.seq) { state.notice?.let { snackbars.showSnackbar(it.text) } }
    val today = ChoresRepository.today()
    val board = state.board
    val send: (ChoreCommand) -> Unit = { command -> scope.launch { if (repository.command(command)) editing = null } }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "back") { TextButton(onClick = onBack) { Text("‹ Chores", style = MaterialTheme.typography.titleLarge) } }
            item(key = "intro") { Note("Kids tick chores on any Portal and earn stars toward a weekly goal.") }
            when {
                !state.loaded -> item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                state.portals == 0 -> item(key = "none") { EmptyMessage("No Portals yet", "Pair a Portal to set up chores.") }
                state.notOnHomeWifi -> item(key = "away") { EmptyMessage("Can’t reach your Portals", ChoreCopy.OFFLINE) }
                state.needsUpdate -> item(key = "update") {
                    EmptyMessage("Update Hearthpane", "Your Portals need the latest Hearthpane for chores.")
                }
                else -> {
                    val pending = ChoreBoard.pending(board)
                    if (pending.isNotEmpty()) item(key = "pending") {
                        PendingCard(board, pending, today, !state.sending, onApprove = { send(ChoreCommand.Approve(it.id)) },
                            onDecline = { send(ChoreCommand.Decline(it.id)) })
                    }
                    if (board.liveKids.isNotEmpty()) item(key = "week") {
                        WeekCard(board, today) { editing = Editing.Kid(KidDraft.of(it)) }
                    }
                    item(key = "today") { DoneTodayCard(board, today, !state.sending) { send(ChoreCommand.TakeBack(it.id)) } }
                    item(key = "kids") {
                        KidsCard(board.liveKids, canAdd = board.liveKids.size < ChoreLimits.MAX_KIDS,
                            onEdit = { editing = Editing.Kid(KidDraft.of(it)) },
                            onAdd = { editing = Editing.Kid(KidDraft.next(board.liveKids.size)) })
                    }
                    if (board.liveKids.isNotEmpty()) item(key = "chores") {
                        ChoreListCard(board, today, canAdd = board.liveChores.size < ChoreLimits.MAX_CHORES,
                            onEdit = { editing = Editing.Chore(ChoreDraft.of(it)) },
                            onAdd = { editing = Editing.Chore(ChoreDraft(day = today)) })
                    }
                }
            }
        }
        SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter))
    }
    when (val e = editing) {
        is Editing.Kid -> KidEditorSheet(e.draft, state.sending, onDismiss = { editing = null },
            onSave = { send(ChoreCommand.SaveKid(it)) },
            onRemove = e.draft.id?.let { id -> { send(ChoreCommand.DeleteKid(id)) } })
        is Editing.Chore -> ChoreEditorSheet(e.draft, board.liveKids, today, state.sending, onDismiss = { editing = null },
            onSave = { send(ChoreCommand.SaveChore(it)) },
            onRemove = e.draft.id?.let { id -> { send(ChoreCommand.DeleteChore(id)) } })
        null -> Unit
    }
}
