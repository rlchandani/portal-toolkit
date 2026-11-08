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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.portalinstaller.data.music.SongRef
import com.portalinstaller.share.music.MusicArt

private val ROW_ART = 44.dp
private val ROW_HEIGHT = 56.dp

/** One entry of a song row's ⋯ menu. */
internal data class SongAction(val title: String, val run: () -> Unit)

/** A search result or a recently played song: tap plays it now, ⋯ opens [actions]. */
@Composable
internal fun SongRow(song: SongRef, detail: String, enabled: Boolean, onPlay: () -> Unit, actions: List<SongAction>) {
    var menu by remember(song.videoId) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = ROW_HEIGHT)
        .clickable(enabled = enabled, role = Role.Button, onClickLabel = "Play now", onClick = onPlay)
        .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        MusicArt(song.thumb, ROW_ART)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(song.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box {
            TextButton(onClick = { menu = true }, enabled = enabled,
                modifier = Modifier.semantics { contentDescription = "More for ${song.title}" }) { Text("⋯") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                actions.forEach { action ->
                    DropdownMenuItem(text = { Text(action.title) }, onClick = { menu = false; action.run() })
                }
            }
        }
    }
}
