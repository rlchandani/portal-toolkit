/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.controlcenter

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.portalinstaller.data.companion.CompanionPlayback
import com.portalinstaller.data.companion.playbackTime
import com.portalinstaller.ui.common.JoinedAction
import com.portalinstaller.ui.common.JoinedActionGroup
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** UI callbacks are independent of transport, so gestures can be verified without a Portal. */
@Composable
internal fun PlaybackPanel(
    playback: CompanionPlayback,
    deviceKey: String,
    connected: Boolean,
    busy: Boolean,
    onMedia: (String, Long?) -> Unit,
    onRelativeVolume: (String) -> Unit,
    onSetVolume: (Int) -> Unit,
    loadArtwork: suspend (String) -> ImageBitmap?,
) {
    val artVersion = playback.artVersion
    val art by key(deviceKey, artVersion, connected) {
        produceState<ImageBitmap?>(null) {
            if (connected && artVersion != null) value = loadArtwork(artVersion)
        }
    }
    val enabled = connected && !busy
    PageSections {
        SectionTitle("Now playing")
        GroupCard(Modifier.testTag("playback-card")) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(64.dp).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center) {
                    if (art != null) Image(art!!, contentDescription = null,
                        modifier = Modifier.size(64.dp), contentScale = ContentScale.Crop)
                    else PlaybackGlyph("music", MaterialTheme.colorScheme.onSurfaceVariant, Modifier.size(28.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(playback.title, style = MaterialTheme.typography.titleMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (playback.artist.isNotBlank()) Text(playback.artist,
                        style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (playback.album.isNotBlank()) Text(playback.album,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!playback.active) Text(
                        if (connected) "Start audio on your Portal to enable playback." else "Connect to control playback.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            key(deviceKey, playback.trackKey) {
                if (playback.active && playback.durationMs > 0) {
                    var dragging by remember { mutableStateOf(false) }
                    var fraction by remember { mutableFloatStateOf(playback.positionMs.toFloat() / playback.durationMs) }
                    LaunchedEffect(playback.positionMs, busy) {
                        if (!dragging && !busy) fraction = playback.positionMs.toFloat() / playback.durationMs
                    }
                    LaunchedEffect(connected) {
                        dragging = false
                        fraction = playback.positionMs.toFloat() / playback.durationMs
                    }
                    if (playback.canSeek) ReleaseOnlySlider(
                        value = fraction.coerceIn(0f, 1f),
                        onValueChange = { dragging = true; fraction = it },
                        onCommit = {
                            if (dragging && enabled) onMedia("seek",
                                (fraction * playback.durationMs).roundToLong().coerceIn(0, playback.durationMs))
                            dragging = false
                        },
                        onCancel = {
                            dragging = false
                            fraction = playback.positionMs.toFloat() / playback.durationMs
                        },
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("playback-seek")
                            .semantics { contentDescription = "Playback position" },
                    ) else LinearProgressIndicator(
                        progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(playbackTime((fraction * playback.durationMs).roundToLong()),
                            style = MaterialTheme.typography.labelSmall)
                        Text(playbackTime(playback.durationMs), style = MaterialTheme.typography.labelSmall)
                    }
                }
                JoinedActionGroup(listOf(
                    JoinedAction("Previous", { onMedia("previous", null) }, enabled && playback.active,
                        icon = { PlaybackGlyph("previous", it) }, showLabel = false),
                    JoinedAction(if (playback.playing) "Pause" else "Play",
                        { onMedia("playpause", null) }, enabled && playback.active, emphasized = true,
                        icon = { PlaybackGlyph(if (playback.playing) "pause" else "play", it) },
                        showLabel = false),
                    JoinedAction("Next", { onMedia("next", null) }, enabled && playback.active,
                        icon = { PlaybackGlyph("next", it) }, showLabel = false),
                ))
            }
            val volume = playback.volume
            if (volume != null && volume.canSet) {
                var dragging by remember(deviceKey) { mutableStateOf(false) }
                var draft by remember(deviceKey) { mutableFloatStateOf(volume.level.toFloat()) }
                LaunchedEffect(volume.level, volume.max, busy) {
                    if (!dragging && !busy) draft = volume.level.toFloat()
                }
                LaunchedEffect(connected) {
                    dragging = false
                    draft = volume.level.toFloat()
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Volume", style = MaterialTheme.typography.labelLarge)
                    Text(if (volume.muted && !dragging) "Muted" else
                        "${(draft / volume.max * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelMedium)
                }
                ReleaseOnlySlider(
                    value = draft.coerceIn(0f, volume.max.toFloat()),
                    onValueChange = { dragging = true; draft = it },
                    onCommit = {
                        if (dragging && enabled) onSetVolume(draft.roundToInt().coerceIn(0, volume.max))
                        dragging = false
                    },
                    onCancel = {
                        dragging = false
                        draft = volume.level.toFloat()
                    },
                    valueRange = 0f..volume.max.toFloat(),
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("playback-volume")
                        .semantics { contentDescription = "Portal media volume" },
                )
            }
            JoinedActionGroup(listOf(
                JoinedAction("Volume −", { onRelativeVolume("down") },
                    enabled && (volume == null || volume.canSet)),
                JoinedAction("Volume +", { onRelativeVolume("up") },
                    enabled && (volume == null || volume.canSet)),
            ))
            if (volume != null && !volume.canSet) Text("Volume is controlled by the output device.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun PlaybackGlyph(name: String, tint: Color, modifier: Modifier = Modifier.size(28.dp)) {
    Canvas(modifier) {
        val u = size.minDimension / 24
        fun triangle(left: Float, right: Float) = Path().apply {
            moveTo(left * u, 5 * u); lineTo(right * u, 12 * u); lineTo(left * u, 19 * u); close()
        }
        when (name) {
            "play" -> drawPath(triangle(7f, 19f), tint)
            "pause" -> {
                drawRect(tint, Offset(6*u, 5*u), Size(4*u, 14*u))
                drawRect(tint, Offset(14*u, 5*u), Size(4*u, 14*u))
            }
            "next" -> {
                drawPath(triangle(5f, 17f), tint)
                drawRect(tint, Offset(17*u, 5*u), Size(2*u, 14*u))
            }
            "previous" -> {
                drawPath(triangle(19f, 7f), tint)
                drawRect(tint, Offset(5*u, 5*u), Size(2*u, 14*u))
            }
            else -> {
                drawLine(tint, Offset(9*u, 17*u), Offset(9*u, 5*u), 2*u)
                drawLine(tint, Offset(9*u, 5*u), Offset(18*u, 3*u), 2*u)
                drawLine(tint, Offset(18*u, 3*u), Offset(18*u, 15*u), 2*u)
                drawOval(tint, Offset(4*u, 15*u), Size(5*u, 4*u))
                drawOval(tint, Offset(13*u, 13*u), Size(5*u, 4*u))
            }
        }
    }
}
