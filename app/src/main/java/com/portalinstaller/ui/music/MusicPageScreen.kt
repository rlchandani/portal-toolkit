/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.music

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.portalinstaller.data.companion.CompanionClient
import com.portalinstaller.data.companion.FileCompanionSessionStore
import com.portalinstaller.data.music.MusicCopy
import com.portalinstaller.data.music.PortalMusicClient
import com.portalinstaller.data.music.PortalMusicController
import com.portalinstaller.data.music.MusicCommand
import com.portalinstaller.data.music.QuietAsk
import com.portalinstaller.data.music.QuietAction
import com.portalinstaller.data.music.RoomMusic
import com.portalinstaller.share.LastShareRoom
import com.portalinstaller.share.RoomOption
import com.portalinstaller.ui.common.JoinedAction
import com.portalinstaller.ui.common.JoinedActionGroup
import com.portalinstaller.ui.controlcenter.EmptyMessage
import com.portalinstaller.ui.controlcenter.GroupCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val monotonic: () -> Long = SystemClock::elapsedRealtime

private fun musicController(context: Context): PortalMusicController {
    val app = context.applicationContext
    val store = FileCompanionSessionStore.create(app)
    return PortalMusicController(PortalMusicClient(CompanionClient(), monotonic),
        sessions = { withContext(Dispatchers.IO) { store.load() } },
        rememberRoom = { LastShareRoom.set(app, it) })
}

/**
 * Home › Music: the chosen Portal(s)' YouTube player: now playing with live progress, transport,
 * speaker volume and the queue. Polls only while visible (stops when the app is in the background).
 */
@Composable
fun MusicPageScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val controller = remember { musicController(context) }
    val state by controller.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val snackbars = remember { SnackbarHostState() }
    LaunchedEffect(controller) { controller.load(LastShareRoom.get(context)) }
    LaunchedEffect(controller, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                delay(PortalMusicController.POLL_MS)
                controller.poll()
            }
        }
    }
    LaunchedEffect(state.notice?.seq) { state.notice?.let { snackbars.showSnackbar(it.text) } }
    val send: (MusicCommand, List<String>) -> Unit = { command, ids -> scope.launch { controller.command(command, ids) } }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "back") {
                TextButton(onClick = onBack) { Text("‹ Music", style = MaterialTheme.typography.titleLarge) }
            }
            item(key = "intro") {
                Note("YouTube on your Portals: what’s playing, the volume and what’s up next.")
            }
            val options = state.options
            when {
                options == null -> item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                options.isEmpty() -> item(key = "empty") { EmptyMessage("No Portals yet", MusicCopy.NO_ROOMS) }
                else -> {
                    item(key = "rooms") { RoomChips(options, state.selected, controller::select) }
                    val targets = state.targets
                    if (targets.size > 1) item(key = "everywhere") {
                        Everywhere(enabled = targets.none { it.id in state.pending },
                            onPlay = { send(MusicCommand.Play, targets.map { it.id }) },
                            onPause = { send(MusicCommand.Pause, targets.map { it.id }) })
                    }
                    targets.forEach { room ->
                        item(key = "room-${room.id}") {
                            MusicRoomCard(room.name, state.rooms[room.id] ?: RoomMusic.Loading, room.id in state.pending, monotonic,
                                onCommand = { send(it, listOf(room.id)) },
                                onVolume = { change -> scope.launch { controller.volume(room.id, change) } })
                        }
                    }
                    val single = targets.singleOrNull()
                    val player = (single?.let { state.rooms[it.id] } as? RoomMusic.Ready)?.player
                    if (single != null && player != null) {
                        musicQueue(player, enabled = single.id !in state.pending) { send(it, listOf(single.id)) }
                    }
                }
            }
        }
        SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter))
    }
    state.ask?.let { ask ->
        QuietDialog(ask, onYes = { scope.launch { controller.answerQuiet(true) } },
            onNo = { scope.launch { controller.answerQuiet(false) } }, onDismiss = controller::dismissQuiet)
    }
}

/** Kitchen · Den · Both rooms (the share sheet's room options). */
@Composable
private fun RoomChips(options: List<RoomOption>, selected: String?, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(selected = option.key == selected, onClick = { onSelect(option.key) }, label = { Text(option.title) })
        }
    }
}

@Composable
private fun Everywhere(enabled: Boolean, onPlay: () -> Unit, onPause: () -> Unit) {
    GroupCard {
        Text("Every chosen room", style = MaterialTheme.typography.titleMedium)
        JoinedActionGroup(listOf(
            JoinedAction("Play all", onPlay, enabled, emphasized = true),
            JoinedAction("Pause all", onPause, enabled),
        ))
    }
}

/** Mirrors the share sheet's quiet-hours question. */
@Composable
private fun QuietDialog(ask: QuietAsk, onYes: () -> Unit, onNo: () -> Unit, onDismiss: () -> Unit) {
    val volume = ask.action is QuietAction.Volume
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Quiet hours") },
        text = { Text(ask.message) },
        confirmButton = { TextButton(onClick = onYes) { Text(if (volume) "Turn it up" else "Play anyway, quietly") } },
        dismissButton = { TextButton(onClick = onNo) { Text("Not now") } },
        modifier = Modifier.padding(horizontal = 8.dp))
}
