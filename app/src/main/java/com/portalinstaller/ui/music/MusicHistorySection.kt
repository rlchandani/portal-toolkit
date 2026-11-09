/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.music

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.portalinstaller.data.music.HistoryView
import com.portalinstaller.data.music.MusicCopy
import com.portalinstaller.data.music.MusicTimeText
import com.portalinstaller.data.music.SongRef
import com.portalinstaller.ui.controlcenter.SectionTitle

/** Rows shown before "Show all": the rest are one tap away. */
internal const val HISTORY_SHOWN = 20

/** What a "Recently played" row can do. */
internal data class HistoryActions(
    val onPlay: (SongRef) -> Unit,
    val onPlayNext: (SongRef) -> Unit,
    val onRemove: (SongRef) -> Unit,
    val onClear: () -> Unit,
    val onShowAll: () -> Unit,
)

/** "Recently played" on the selected room: tap = play now, ⋯ = Play next / Remove. */
internal fun LazyListScope.musicHistory(
    view: HistoryView,
    nowMs: Long,
    enabled: Boolean,
    showAll: Boolean,
    actions: HistoryActions,
) {
    val ready = view as? HistoryView.Ready
    item(key = "history-title") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Recently played", Modifier.weight(1f))
            if (ready != null && ready.history.entries.isNotEmpty()) {
                TextButton(onClick = actions.onClear, enabled = enabled) { Text("Clear history") }
            }
        }
    }
    when (view) {
        HistoryView.Loading -> item(key = "history-loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        is HistoryView.Message -> item(key = "history-message") { Note(view.text) }
        is HistoryView.Ready -> {
            val entries = view.history.entries
            if (entries.isEmpty()) {
                item(key = "history-empty") { Note(MusicCopy.HISTORY_EMPTY) }
                return
            }
            val shown = if (showAll) entries else entries.take(HISTORY_SHOWN)
            items(shown, key = { "history-${it.song.videoId}" }) { entry ->
                SongRow(entry.song, MusicTimeText.historyDetail(entry, nowMs), enabled,
                    onPlay = { actions.onPlay(entry.song) }, actions = listOf(
                        SongAction("Play now") { actions.onPlay(entry.song) },
                        SongAction("Play next") { actions.onPlayNext(entry.song) },
                        SongAction("Remove from history") { actions.onRemove(entry.song) },
                    ))
            }
            if (shown.size < entries.size) item(key = "history-more") {
                TextButton(onClick = actions.onShowAll) { Text("Show all ${entries.size}") }
            }
        }
    }
}

/** "Clear history" asks first: it can't be undone. */
@Composable
internal fun ClearHistoryDialog(room: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Clear history?") },
        text = { Text(MusicCopy.clearHistoryAsk(room)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Clear history") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        modifier = Modifier.padding(horizontal = 8.dp))
}
