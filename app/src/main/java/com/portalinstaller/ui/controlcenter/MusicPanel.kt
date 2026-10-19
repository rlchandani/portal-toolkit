/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.controlcenter

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.portalinstaller.data.companion.CompanionMusic
import com.portalinstaller.data.companion.CompanionMusicAction
import com.portalinstaller.data.companion.commandBody
import com.portalinstaller.data.companion.playbackTime
import com.portalinstaller.ui.common.JoinedAction
import com.portalinstaller.ui.common.JoinedActionGroup
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private enum class MusicSheet(val title: String) {
    Queue("Queue"), Speakers("Speakers"), Lyrics("Lyrics"),
}

/** Keep the reviewed group observation while unrelated position/volume polls arrive. */
private data class MusicRoomChange(
    val observed: CompanionMusic,
    val roomId: String,
    val wasInGroup: Boolean,
) {
    fun matches(current: CompanionMusic?): Boolean = current != null &&
        observed.sourceRevision == current.sourceRevision && observed.sourceId == current.sourceId &&
        observed.controlPlayerId == current.controlPlayerId &&
        current.rooms.firstOrNull { it.id == roomId }?.inCurrentGroup == wasInGroup
}

/** Presentation only: callbacks retain the snapshot whose player/queue bindings were checked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MusicPanel(
    music: CompanionMusic?,
    deviceKey: String,
    connected: Boolean,
    fresh: Boolean,
    sending: Boolean,
    feedback: String?,
    onAction: (CompanionMusic, CompanionMusicAction) -> Unit,
    onRefresh: () -> Unit,
) {
    // A source generation can change while every visible ID stays the same.
    // Dispose dialogs, lazy-list positions and unfinished gestures for either kind of switch.
    key(deviceKey, music?.sourceRevision, music?.sourceId, music?.controlPlayerId, music?.queueId) {
        var sheet by remember { mutableStateOf<MusicSheet?>(null) }
        var roomChange by remember { mutableStateOf<MusicRoomChange?>(null) }
        val connection = music?.connection.orEmpty()
        val playing = music?.playback == "playing"
        val outcome = music?.lastCommand?.outcome
        val ready = connection == "ready"
        val enabled = connected && fresh && ready && !sending && music?.pending == null
        val track = if (music?.audioOwnedByOther == true) null else music?.track
        fun allowed(action: CompanionMusicAction): Boolean =
            enabled && music?.commandBody(action) != null
        fun dispatch(action: CompanionMusicAction) {
            if (allowed(action) && music != null) onAction(music, action)
        }
        val supportsSpeakers = music != null &&
            (music.capabilities.rooms || music.capabilities.selectPlayer || music.capabilities.volume)
        val supportedSheet = when (sheet) {
            MusicSheet.Queue -> music?.capabilities?.queue == true
            MusicSheet.Speakers -> supportsSpeakers
            MusicSheet.Lyrics -> music?.capabilities?.lyrics == true && track != null
            null -> false
        }
        // Capability flags are command admission, not read permission. Pending readback
        // withdraws every command while the current source's data is still readable.
        val readableSheet = supportedSheet || (music?.pending != null && when (sheet) {
            MusicSheet.Queue, MusicSheet.Speakers -> true
            MusicSheet.Lyrics -> track != null
            null -> false
        })
        LaunchedEffect(connected, ready, readableSheet) {
            if (!connected || !ready || !readableSheet) {
                sheet = null
                roomChange = null
            }
        }
        PageSections {
            SectionTitle("Music")
            GroupCard(Modifier.testTag("music-card")) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(64.dp).clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center) {
                        PlaybackGlyph("music", MaterialTheme.colorScheme.onSurfaceVariant,
                            Modifier.size(28.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            when {
                                !connected -> "Music is offline"
                                music == null -> "Loading music…"
                                music.audioOwnedByOther -> "Audio in another app"
                                !ready -> when (connection) {
                                    "not_configured" -> "Music isn’t configured"
                                    "connecting" -> "Connecting to music…"
                                    "auth_required" -> "Music needs sign-in"
                                    "incompatible" -> "Music needs an update"
                                    else -> "Music is unavailable"
                                }
                                track == null -> "Nothing playing"
                                else -> track.title.ifBlank { "Untitled track" }
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold, maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (connected && ready && track != null) {
                            if (track.artist.isNotBlank()) Text(track.artist,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (track.album.isNotBlank()) MusicDetail(track.album)
                        } else MusicDetail(when {
                            !connected -> "Connect to your Portal to control music."
                            music == null -> "Waiting for your Portal."
                            music.audioOwnedByOther -> "Music controls are paused while another app plays audio."
                            connection == "not_configured" ->
                                "Set up your music source on the Portal, then refresh."
                            connection == "auth_required" ->
                                "Sign in to your music source on the Portal, then refresh."
                            connection == "incompatible" ->
                                "Update your music source on the Portal, then refresh."
                            ready && track == null -> if (music.capabilities.queue)
                                "Start music on your Portal or choose an item in Queue."
                                else "Start music on your Portal."
                            else -> "Check your music source, then refresh."
                        })
                    }
                }
                if (connected && ready && music != null && track != null) {
                    val duration = track.durationMs
                    val position = music.positionMs
                    if (track.isLive) MusicDetail("Live")
                    else if (duration != null && duration > 0 && position != null) {
                        key(track.id) {
                            var dragging by remember { mutableStateOf(false) }
                            var fraction by remember { mutableFloatStateOf(position.toFloat() / duration) }
                            LaunchedEffect(position, duration, enabled) {
                                if (!dragging || !enabled) {
                                    dragging = false
                                    fraction = position.toFloat() / duration
                                }
                            }
                            if (music.capabilities.seek) ReleaseOnlySlider(
                                value = fraction.coerceIn(0f, 1f),
                                onValueChange = { dragging = true; fraction = it },
                                onCommit = {
                                    if (dragging) dispatch(CompanionMusicAction.Seek(
                                        (fraction * duration).roundToLong().coerceIn(0L, duration)))
                                    dragging = false
                                },
                                onCancel = {
                                    dragging = false
                                    fraction = position.toFloat() / duration
                                },
                                enabled = allowed(CompanionMusicAction.Seek(position)),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .testTag("music-seek")
                                    .semantics { contentDescription = "Music position" },
                            ) else LinearProgressIndicator(
                                progress = { fraction.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().testTag("music-progress"),
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(playbackTime((fraction.coerceIn(0f, 1f) * duration).roundToLong()),
                                    style = MaterialTheme.typography.labelSmall)
                                Text(playbackTime(duration), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                if (music != null && ready && !music.audioOwnedByOther) {
                    val transport = buildList {
                        if (music.capabilities.previous) add(musicTransport("Previous", "previous",
                            allowed(CompanionMusicAction.Transport("previous")), ::dispatch))
                        val command = if (playing) "pause" else "play"
                        val supported = if (playing) music.capabilities.pause else music.capabilities.play
                        if (supported) add(musicTransport(if (playing) "Pause" else "Play", command,
                            allowed(CompanionMusicAction.Transport(command)), ::dispatch, emphasized = true))
                        if (music.capabilities.next) add(musicTransport("Next", "next",
                            allowed(CompanionMusicAction.Transport("next")), ::dispatch))
                    }
                    JoinedActionGroup(transport, Modifier.testTag("music-transport"))
                }
                val status = when {
                    sending || music?.pending != null -> "Waiting for Portal confirmation…"
                    !feedback.isNullOrBlank() -> feedback
                    outcome in listOf("timed_out", "disconnected") ->
                        "Couldn’t confirm the change. Refresh before trying again."
                    outcome == "failed" ->
                        "The music change failed. Refresh to check the player."
                    connected && music != null && !fresh -> "Music status is out of date. Refresh to update it."
                    connected && ready && music != null && track == null && !music.audioOwnedByOther ->
                        "Choose a player or start music on your Portal."
                    else -> null
                }
                if (status != null) Text(status, style = MaterialTheme.typography.bodySmall,
                    color = if (outcome == "failed")
                        MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("music-feedback")
                        .semantics { liveRegion = LiveRegionMode.Polite })
                val navigation = buildList {
                    if (music?.capabilities?.queue == true) add(JoinedAction("Queue",
                        { sheet = MusicSheet.Queue }, connected && fresh && ready))
                    if (supportsSpeakers) add(JoinedAction("Speakers",
                        { sheet = MusicSheet.Speakers }, connected && fresh && ready))
                    if (music?.capabilities?.lyrics == true) add(JoinedAction("Lyrics",
                        { sheet = MusicSheet.Lyrics }, connected && fresh && ready && track != null))
                }
                JoinedActionGroup(navigation, Modifier.testTag("music-sections"))
                if (!connected || music == null || !ready || !fresh || status != null) {
                    JoinedActionGroup(listOf(JoinedAction("Refresh music", onRefresh,
                        connected && !sending)), Modifier.testTag("music-refresh"))
                }
            }
        }
        if (sheet != null && readableSheet && connected && ready && music != null) {
            val currentSheet = sheet!!
            ModalBottomSheet(
                onDismissRequest = { sheet = null; roomChange = null },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                modifier = Modifier.testTag("music-sheet"),
            ) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(currentSheet.title, style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        IconButton(onClick = { sheet = null; roomChange = null },
                            modifier = Modifier.semantics {
                                contentDescription = "Close ${currentSheet.title.lowercase(Locale.ROOT)}"
                            }) { Text("×", style = MaterialTheme.typography.headlineSmall) }
                    }
                    val message = when {
                        sending -> feedback ?: "Sending change…"
                        music.pending != null -> "Waiting for Portal confirmation…"
                        !feedback.isNullOrBlank() -> feedback
                        !fresh -> "Refresh to check the latest music state."
                        else -> null
                    }
                    if (message != null) Text(message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("music-sheet-feedback")
                            .semantics { liveRegion = LiveRegionMode.Polite })
                    if (!fresh && !sending && music.pending == null) TextButton(onClick = onRefresh) {
                        Text("Refresh music")
                    }
                    val listModifier = Modifier.fillMaxWidth()
                        .heightIn(max = (LocalConfiguration.current.screenHeightDp * .55f).dp)
                    when (currentSheet) {
                        MusicSheet.Queue -> {
                            if (music.queue.isEmpty()) MusicDetail("Your queue is empty.")
                            else LazyColumn(listModifier.testTag("music-queue-list")) {
                                items(music.queue) { item ->
                                    val action = CompanionMusicAction.QueueItem(item.id)
                                    val canSelect = item.available && item.canSelect && allowed(action)
                                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable(enabled = canSelect, role = Role.Button) {
                                            if (canSelect) {
                                                dispatch(action)
                                                sheet = null
                                            }
                                        }.testTag("music-queue-${item.id}")
                                        .padding(horizontal = 12.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        PlaybackGlyph("music", MaterialTheme.colorScheme.onSurfaceVariant,
                                            Modifier.size(24.dp))
                                        Column(Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                            Text(item.title.ifBlank { "Untitled track" },
                                                style = MaterialTheme.typography.bodyLarge,
                                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            if (item.artist.isNotBlank()) MusicDetail(item.artist)
                                            if (!item.available) MusicDetail("Unavailable")
                                            else if (item.id == track?.id) MusicDetail("Now playing")
                                        }
                                        if (canSelect) ControlGlyph("chevron", modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        }
                        MusicSheet.Speakers -> {
                            MusicDetail(if (music.capabilities.rooms && music.capabilities.selectPlayer)
                                "Choose a player, or join speakers to the current music."
                                else if (music.capabilities.selectPlayer) "Choose a player for music."
                                else if (music.capabilities.rooms) "Join speakers to the current music."
                                else "Adjust each speaker’s volume.")
                            if (music.rooms.isEmpty()) MusicDetail("No speakers available.")
                            else LazyColumn(listModifier.testTag("music-room-list"),
                                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(music.rooms) { room ->
                                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .testTag("music-room-${room.id}").padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(room.name, style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold, maxLines = 2,
                                            overflow = TextOverflow.Ellipsis)
                                        MusicDetail(when {
                                            !room.available -> "Offline"
                                            room.id == music.controlPlayerId -> "Current player"
                                            room.inCurrentGroup -> "In the current group"
                                            else -> "Available"
                                        })
                                        val roomActions = buildList {
                                            if (music.capabilities.selectPlayer && room.canSelect &&
                                                room.id != music.controlPlayerId) {
                                                val action = CompanionMusicAction.SelectPlayer(room.id)
                                                add(JoinedAction("Choose player", {
                                                    if (room.available && allowed(action)) {
                                                        dispatch(action)
                                                        sheet = null
                                                    }
                                                }, room.available && allowed(action)))
                                            }
                                            if (music.capabilities.rooms &&
                                                (if (room.inCurrentGroup) room.canRemove else room.canJoin)) {
                                                val action = CompanionMusicAction.ToggleRoom(room.id)
                                                add(JoinedAction(
                                                    if (room.inCurrentGroup) "Remove from group" else "Join group",
                                                    {
                                                        if (room.available && allowed(action))
                                                            roomChange = MusicRoomChange(music, room.id, room.inCurrentGroup)
                                                    },
                                                    room.available && allowed(action),
                                                ))
                                            }
                                        }
                                        JoinedActionGroup(roomActions)
                                        val volume = room.volume
                                        if (music.capabilities.volume && room.canSetVolume && volume != null) {
                                            key(room.id) {
                                                var dragging by remember { mutableStateOf(false) }
                                                var draft by remember { mutableFloatStateOf(volume.toFloat()) }
                                                val volumeEnabled = room.available && allowed(
                                                    CompanionMusicAction.Volume(room.id, volume))
                                                LaunchedEffect(volume, volumeEnabled) {
                                                    if (!dragging || !volumeEnabled) {
                                                        dragging = false
                                                        draft = volume.toFloat()
                                                    }
                                                }
                                                Row(Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween) {
                                                    Text("Volume", style = MaterialTheme.typography.labelLarge)
                                                    Text(if (room.muted == true && !dragging) "Muted" else
                                                        "${draft.roundToInt()}%",
                                                        style = MaterialTheme.typography.labelMedium)
                                                }
                                                ReleaseOnlySlider(
                                                    value = draft.coerceIn(0f, 100f),
                                                    onValueChange = { dragging = true; draft = it },
                                                    onCommit = {
                                                        if (dragging && room.available) dispatch(
                                                            CompanionMusicAction.Volume(room.id,
                                                                draft.roundToInt().coerceIn(0, 100)))
                                                        dragging = false
                                                    },
                                                    onCancel = { dragging = false; draft = volume.toFloat() },
                                                    valueRange = 0f..100f,
                                                    enabled = volumeEnabled,
                                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                                        .testTag("music-volume-${room.id}")
                                                        .semantics {
                                                            contentDescription = "${room.name} volume"
                                                        },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        MusicSheet.Lyrics -> {
                            if (music.lyrics.isEmpty()) MusicDetail("No lyrics for this track.")
                            else {
                                val currentLine = music.positionMs?.let { position ->
                                    music.lyrics.indexOfLast { it.timeMs <= position }
                                }
                                LazyColumn(listModifier.testTag("music-lyrics-list"),
                                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    items(music.lyrics.size) { index ->
                                        Text(music.lyrics[index].text,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = if (index == currentLine) FontWeight.SemiBold
                                                else FontWeight.Normal,
                                            color = if (index == currentLine) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        val change = roomChange
        val room = change?.let { candidate ->
            candidate.observed.rooms.firstOrNull { it.id == candidate.roomId }
        }
        if (change != null && room != null && sheet == MusicSheet.Speakers) {
            val removing = change.wasInGroup
            val action = CompanionMusicAction.ToggleRoom(room.id)
            val canConfirm = change.matches(music) && allowed(action)
            AlertDialog(
                onDismissRequest = { roomChange = null },
                title = { Text(if (removing) "Remove ${room.name}?" else "Join ${room.name}?") },
                text = { Text(if (removing) "Remove this speaker from the current music group."
                    else "Add this speaker to the current music group.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (canConfirm) {
                                roomChange = null
                                onAction(change.observed, action)
                            }
                        },
                        enabled = canConfirm,
                    ) { Text(if (removing) "Remove speaker" else "Join speaker") }
                },
                dismissButton = {
                    TextButton(onClick = { roomChange = null }) { Text("Cancel") }
                },
            )
        }
    }
}

private fun musicTransport(
    label: String,
    command: String,
    enabled: Boolean,
    dispatch: (CompanionMusicAction) -> Unit,
    emphasized: Boolean = false,
) = JoinedAction(label, { dispatch(CompanionMusicAction.Transport(command)) },
    enabled, emphasized, icon = { PlaybackGlyph(command, it) }, showLabel = false)

@Composable
private fun MusicDetail(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3,
        overflow = TextOverflow.Ellipsis)
}
