/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.portalinstaller.data.companion.playbackTime
import com.portalinstaller.data.music.MusicCommand
import com.portalinstaller.data.music.PortalPlayer
import com.portalinstaller.data.music.PortalTrack
import com.portalinstaller.share.music.MusicArt
import com.portalinstaller.ui.controlcenter.SectionTitle

private val ROW_ART = 44.dp
private val ROW_HEIGHT = 56.dp
private const val UNPLAYABLE_ALPHA = 0.5f

/** Up next on one Portal: tap to play now, or ⋯ for Play next / Remove. */
internal fun LazyListScope.musicQueue(player: PortalPlayer, enabled: Boolean, onCommand: (MusicCommand) -> Unit) {
    if (player.current == null) return
    item(key = "queue-title") { SectionTitle("Up next") }
    if (player.upNext.isEmpty()) {
        item(key = "queue-empty") { Note("Nothing else is queued. Share songs from YouTube to add more.") }
        return
    }
    items(player.upNext, key = { "queue-${it.entryId}" }) { track ->
        QueueRow(track, enabled, onCommand)
    }
    val more = player.upNextTotal - player.upNext.size
    if (more > 0) item(key = "queue-more") { Note("And $more more on the Portal.") }
}

@Composable
private fun QueueRow(track: PortalTrack, enabled: Boolean, onCommand: (MusicCommand) -> Unit) {
    var menu by remember(track.entryId) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = ROW_HEIGHT)
        .clickable(enabled = enabled, role = Role.Button, onClickLabel = "Play now") { onCommand(MusicCommand.Jump(track.entryId)) }
        .padding(vertical = 6.dp)
        .alpha(if (track.unplayable == null) 1f else UNPLAYABLE_ALPHA),
        verticalAlignment = Alignment.CenterVertically) {
        MusicArt(track.thumb, ROW_ART)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(track.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detail = listOfNotNull(track.artist.takeIf { it.isNotBlank() }, track.durationMs?.let(::playbackTime),
                track.unplayable?.let { "Can’t play here: $it" }).joinToString(" · ")
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box {
            TextButton(onClick = { menu = true }, enabled = enabled) { Text("⋯") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Play now") }, onClick = { menu = false; onCommand(MusicCommand.Jump(track.entryId)) })
                DropdownMenuItem(text = { Text("Play next") }, onClick = { menu = false; onCommand(MusicCommand.PlayNext(track.entryId)) })
                DropdownMenuItem(text = { Text("Remove from queue") }, onClick = { menu = false; onCommand(MusicCommand.Remove(track.entryId)) })
            }
        }
    }
}
