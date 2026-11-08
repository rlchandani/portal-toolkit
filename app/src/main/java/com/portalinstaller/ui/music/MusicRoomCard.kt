/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.portalinstaller.data.companion.playbackTime
import com.portalinstaller.data.music.MusicCommand
import com.portalinstaller.data.music.MusicCopy
import com.portalinstaller.data.music.MusicTimeText
import com.portalinstaller.data.music.PortalPlayer
import com.portalinstaller.data.music.RoomMusic
import com.portalinstaller.data.music.VolumeChange
import com.portalinstaller.share.music.MusicArt
import com.portalinstaller.ui.common.JoinedAction
import com.portalinstaller.ui.common.JoinedActionGroup
import com.portalinstaller.ui.controlcenter.GroupCard
import com.portalinstaller.ui.controlcenter.PlaybackGlyph
import com.portalinstaller.ui.controlcenter.ReleaseOnlySlider
import kotlin.math.roundToLong
import kotlinx.coroutines.delay

private const val TICK_MS = 1_000L
private val ART_SIZE = 72.dp
private val CONTROL_HEIGHT = 48.dp

/** One room: now playing, live progress, transport and volume. Volume works whatever is playing. */
@Composable
internal fun MusicRoomCard(
    name: String,
    music: RoomMusic,
    pending: Boolean,
    clock: () -> Long,
    onCommand: (MusicCommand) -> Unit,
    onVolume: (VolumeChange) -> Unit,
    wallClock: () -> Long = System::currentTimeMillis,
) {
    GroupCard(Modifier.testTag("music-room-card")) {
        Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        when (music) {
            RoomMusic.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            RoomMusic.Offline -> Note(MusicCopy.offline(name))
            is RoomMusic.Failed -> Note(music.message)
            is RoomMusic.Legacy -> Note(MusicCopy.update(name))
            is RoomMusic.Ready -> NowPlaying(music.player, pending, clock, onCommand)
        }
        if (music is RoomMusic.Ready && music.player.sleepSupported && music.player.current != null) {
            SleepTimerControl(music.player, enabled = !pending, wallClock = wallClock) { onCommand(MusicCommand.Sleep(it)) }
        }
        if (music is RoomMusic.Ready || music is RoomMusic.Legacy) {
            MusicVolumeControls(name, music.volume, enabled = !pending, onVolume = onVolume)
        }
        if (music is RoomMusic.Ready) MusicTimeText.nightCap(music.player.nightCap)?.let { Note(it) }
    }
}

@Composable
private fun NowPlaying(player: PortalPlayer, pending: Boolean, clock: () -> Long, onCommand: (MusicCommand) -> Unit) {
    val track = player.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        MusicArt(track?.thumb, ART_SIZE)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(track?.title ?: MusicCopy.NOTHING_PLAYING, style = MaterialTheme.typography.titleMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            track?.artist?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(statusLine(player), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    player.halt?.let { Note(it) }
    if (track != null && !player.onScreen && !player.playing) Note(MusicCopy.NOT_ON_SCREEN)
    if (player.ad) Note("An ad is playing on the Portal. Your song starts after it.")
    if (track != null && player.durationMs > 0) Progress(player, enabled = !pending && !player.ad, clock, onCommand)
    val enabled = track != null && !pending && !player.ad
    JoinedActionGroup(listOf(
        JoinedAction("Previous", { onCommand(MusicCommand.Previous) }, enabled,
            icon = { PlaybackGlyph("previous", it) }, showLabel = false),
        JoinedAction(if (player.playing) "Pause" else "Play",
            { onCommand(if (player.playing) MusicCommand.Pause else MusicCommand.Play) }, enabled, emphasized = true,
            icon = { PlaybackGlyph(if (player.playing) "pause" else "play", it) }, showLabel = false),
        JoinedAction("Next", { onCommand(MusicCommand.Next) }, enabled,
            icon = { PlaybackGlyph("next", it) }, showLabel = false),
    ))
}

private fun statusLine(player: PortalPlayer): String {
    val what = when {
        player.current == null -> "Share a song from YouTube to start."
        player.playing -> "Playing"
        else -> "Paused"
    }
    val extras = listOfNotNull(player.label.takeIf { it.isNotBlank() && player.current != null },
        player.account?.let { "Signed in as $it" }, player.quietUntil?.takeIf { player.quiet }?.let { "Quiet hours until $it" })
    return (listOf(what) + extras).joinToString(" · ")
}

/** Ticks locally from the last answer; a drag seeks on release only. */
@Composable
private fun Progress(player: PortalPlayer, enabled: Boolean, clock: () -> Long, onCommand: (MusicCommand) -> Unit) {
    val now by produceState(clock(), player.receivedAtMs, player.playing) {
        while (true) { value = clock(); delay(TICK_MS) }
    }
    val length = player.durationMs
    var drag by remember(player.current?.entryId) { mutableStateOf<Float?>(null) }
    val live = player.livePositionMs(now)
    val fraction = drag ?: (live.toFloat() / length)
    ReleaseOnlySlider(
        value = fraction.coerceIn(0f, 1f),
        onValueChange = { drag = it },
        onCommit = {
            drag?.let { onCommand(MusicCommand.Seek((it * length).roundToLong().coerceIn(0, length))) }
            drag = null
        },
        onCancel = { drag = null },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = CONTROL_HEIGHT).semantics { contentDescription = "Song position" },
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(playbackTime((fraction.coerceIn(0f, 1f) * length).roundToLong()), style = MaterialTheme.typography.labelSmall)
        Text(playbackTime(length), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
