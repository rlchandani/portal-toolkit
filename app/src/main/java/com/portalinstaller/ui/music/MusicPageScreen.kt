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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import com.portalinstaller.data.companion.CompanionClient
import com.portalinstaller.data.companion.FileCompanionSessionStore
import com.portalinstaller.data.music.MusicCommand
import com.portalinstaller.data.music.MusicCopy
import com.portalinstaller.data.music.MusicLibraryClient
import com.portalinstaller.data.music.MusicSearchController
import com.portalinstaller.data.music.MusicSuggestions
import com.portalinstaller.data.music.PortalMusicClient
import com.portalinstaller.data.music.PortalMusicController
import com.portalinstaller.data.music.PrefsRecentSearchStore
import com.portalinstaller.data.music.QuietAction
import com.portalinstaller.data.music.QuietAsk
import com.portalinstaller.data.music.RoomMusic
import com.portalinstaller.data.music.SuggestionPick
import com.portalinstaller.share.LastShareRoom
import com.portalinstaller.share.RoomOption
import com.portalinstaller.ui.common.JoinedAction
import com.portalinstaller.ui.common.JoinedActionGroup
import com.portalinstaller.ui.controlcenter.EmptyMessage
import com.portalinstaller.ui.controlcenter.GroupCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val monotonic: () -> Long = SystemClock::elapsedRealtime
/** "2 h ago" lines re-check once a minute. */
private const val AGO_TICK_MS = 60_000L

private fun musicController(context: Context): PortalMusicController {
    val app = context.applicationContext
    val store = FileCompanionSessionStore.create(app)
    return PortalMusicController(PortalMusicClient(CompanionClient(), monotonic),
        sessions = { withContext(Dispatchers.IO) { store.load() } },
        rememberRoom = { LastShareRoom.set(app, it) })
}

private fun searchController(context: Context, music: PortalMusicController, scope: CoroutineScope) =
    MusicSearchController(MusicLibraryClient(CompanionClient()), music::session,
        PrefsRecentSearchStore(context), scope)

/**
 * Home › Music: the chosen Portal(s)' YouTube player: now playing with live progress, transport,
 * speaker volume and the queue; with one room chosen, YouTube search and "Recently played". Polls only while visible (stops when the app is in the background).
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
    val search = remember { searchController(context, controller, scope) }
    val found by search.state.collectAsStateWithLifecycle()
    val single = state.targets.singleOrNull()
    val singleMusic = single?.let { state.rooms[it.id] }
    LaunchedEffect(single?.id) { search.attach(single?.id) }
    LaunchedEffect(single?.id, (singleMusic as? RoomMusic.Ready)?.player?.current?.videoId) {
        search.playingChanged((singleMusic as? RoomMusic.Ready)?.player?.current?.videoId)
    }
    LaunchedEffect(found.notice?.seq) { found.notice?.let { snackbars.showSnackbar(it.text) } }
    val wallNow by produceState(System.currentTimeMillis()) { while (true) { delay(AGO_TICK_MS); value = System.currentTimeMillis() } }
    var showAllHistory by remember(single?.id) { mutableStateOf(false) }
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
                    val searchRoom = single?.takeIf { singleMusic !is RoomMusic.Legacy }
                    when {
                        targets.size > 1 -> item(key = "pick-one") { Note(MusicCopy.PICK_ONE_ROOM) }
                        searchRoom == null -> Unit
                        found.unsupported -> item(key = "search-update") { Note(MusicCopy.update(searchRoom.name)) }
                        else -> {
                            item(key = "search") {
                                MusicSearchBox(found, onType = search::type, onSubmit = search::submit, onClear = search::clear,
                                    onPick = { suggestion ->
                                        when (val pick = MusicSuggestions.pick(suggestion)) {
                                            is SuggestionPick.Play -> { search.hideSuggestions(); send(MusicCommand.PlayTrack(pick.song), listOf(searchRoom.id)) }
                                            is SuggestionPick.Search -> search.submit(pick.query)
                                        }
                                    })
                            }
                            musicSearchResults(found.results, searchRoom.name, enabled = searchRoom.id !in state.pending,
                                onPlay = { send(MusicCommand.PlayTrack(it), listOf(searchRoom.id)) },
                                onQueue = { song, next -> send(MusicCommand.QueueTrack(song, next), listOf(searchRoom.id)) })
                        }
                    }
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
                    val player = (singleMusic as? RoomMusic.Ready)?.player
                    if (single != null && player != null) {
                        musicQueue(player, enabled = single.id !in state.pending) { send(it, listOf(single.id)) }
                    }
                    if (searchRoom != null && !found.unsupported) {
                        musicHistory(found.history, wallNow, enabled = searchRoom.id !in state.pending, showAll = showAllHistory,
                            actions = HistoryActions(
                                onPlay = { send(MusicCommand.PlayTrack(it), listOf(searchRoom.id)) },
                                onPlayNext = { send(MusicCommand.QueueTrack(it, next = true), listOf(searchRoom.id)) },
                                onRemove = { search.remove(it.videoId) },
                                onClear = search::askClear,
                                onShowAll = { showAllHistory = true }))
                    }
                }
            }
        }
        SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter))
    }
    if (found.confirmClear && single != null) {
        ClearHistoryDialog(single.name, onConfirm = search::confirmClear, onDismiss = search::cancelClear)
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
