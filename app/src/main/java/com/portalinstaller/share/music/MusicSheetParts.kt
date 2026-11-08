/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.share.music

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.portalinstaller.share.ConfirmedGreen
import com.portalinstaller.share.RoomOption
import com.portalinstaller.share.UnconfirmedAmber

private val CardShape = RoundedCornerShape(20.dp)

/** "Play on a Portal" with the music tile and a one-line source ("Song from YouTube Music"). */
@Composable
internal fun MusicSheetHead(title: String, subtitle: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center) {
            Text("♫", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.padding(start = 14.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** Album art from YouTube's image hosts (allowlisted); a quiet tile until it loads or if it can't. */
@Composable
internal fun MusicArt(url: String?, size: Dp, modifier: Modifier = Modifier) {
    val art by produceState<ImageBitmap?>(null, url) {
        value = MusicLinkChecker.artBytes(url)?.let { bytes ->
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
        }
    }
    val shape = RoundedCornerShape(12.dp)
    Box(modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant, shape)) {
        art?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size)) }
    }
}

/** The song or playlist being shared. [note] is e.g. "The song only, not the mix". */
@Composable
internal fun MusicItemCard(thumb: String?, title: String, subtitle: String?, note: String? = null, dim: Boolean = false) {
    Surface(Modifier.fillMaxWidth().padding(top = 16.dp), shape = CardShape, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            MusicArt(thumb, 64.dp, Modifier.alpha(if (dim) 0.45f else 1f))
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
internal fun ShuffleRow(on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp)
        .selectable(on, role = Role.Switch) { onChange(!on) }.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("Shuffle", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        Switch(on, onCheckedChange = null)
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
}

/** Family Area / Living Area / Both rooms, each with its live status line and dot. */
@Composable
internal fun MusicRoomList(options: List<RoomOption>, selected: String?, statuses: Map<String, RoomStatus>,
    lastUsed: String?, onSelect: (String) -> Unit) {
    Surface(shape = CardShape, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Column {
            options.forEachIndexed { index, option ->
                if (index > 0) HorizontalDivider()
                val single = option.rooms.singleOrNull()
                val status = single?.let { statuses[it.id] }
                Row(Modifier.fillMaxWidth().selectable(option.key == selected, role = Role.RadioButton) { onSelect(option.key) }
                    .heightIn(min = 64.dp).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(option.key == selected, onClick = null)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(option.title, style = MaterialTheme.typography.titleMedium)
                        val sub = if (single == null) option.subtitle else MusicShareRules.roomSubtitle(status)
                        sub?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    if (option.key == lastUsed) Text("Last used", style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp))
                    if (single != null) {
                        Spacer(Modifier.size(12.dp))
                        Box(Modifier.size(10.dp).background(dotColor(status), CircleShape))
                    }
                }
            }
        }
    }
}

@Composable
private fun dotColor(status: RoomStatus?) = when (status?.state) {
    RoomState.IDLE, RoomState.PLAYING -> ConfirmedGreen
    RoomState.QUIET, RoomState.ROUTINE -> UnconfirmedAmber
    else -> MaterialTheme.colorScheme.outline
}

private val ACTIONS = listOf(MusicAction.NOW to "Play now", MusicAction.NEXT to "Play next", MusicAction.END to "Add to queue")

@Composable
internal fun MusicActionPicker(selected: MusicAction, onSelect: (MusicAction) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        ACTIONS.forEachIndexed { i, (action, label) ->
            SegmentedButton(selected == action, onClick = { onSelect(action) },
                shape = SegmentedButtonDefaults.itemShape(i, ACTIONS.size)) { Text(label, maxLines = 1) }
        }
    }
}

/** A tinted box with a bold line and an explanation (quiet hours, offline, mix, can't play). */
@Composable
internal fun MusicNotice(title: String, body: String) {
    Surface(Modifier.fillMaxWidth().padding(top = 16.dp), shape = RoundedCornerShape(16.dp),
        color = UnconfirmedAmber.copy(alpha = 0.14f)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** The green tick, heading and line of the confirmation. */
@Composable
internal fun MusicDoneHead(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(56.dp).background(ConfirmedGreen, CircleShape), contentAlignment = Alignment.Center) {
            Text("✓", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.surface)
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}
