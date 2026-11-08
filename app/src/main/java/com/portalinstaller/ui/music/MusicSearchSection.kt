/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.portalinstaller.data.companion.playbackTime
import com.portalinstaller.data.music.MusicCopy
import com.portalinstaller.data.music.MusicSearchState
import com.portalinstaller.data.music.SearchResults
import com.portalinstaller.data.music.SongRef
import com.portalinstaller.data.music.Suggestion
import com.portalinstaller.data.music.SuggestionKind
import com.portalinstaller.ui.controlcenter.SectionTitle

private val SUGGESTION_HEIGHT = 44.dp
/** Below this many searches left today, the page says how many remain. */
private const val QUOTA_WARN_AT = 10

/** The search field for the selected room, with suggestions under it while it has focus. */
@Composable
internal fun MusicSearchBox(
    state: MusicSearchState,
    onType: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onClear: () -> Unit,
    onPick: (Suggestion) -> Unit,
) {
    val focus = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onType,
            singleLine = true,
            placeholder = { Text(MusicCopy.SEARCH_HINT) },
            trailingIcon = if (state.query.isEmpty()) null else {
                { TextButton(onClick = onClear, modifier = Modifier.semantics { contentDescription = "Clear search" }) { Text("×") } }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); onSubmit(state.query) }),
            modifier = Modifier.fillMaxWidth().testTag("music-search").onFocusChanged {
                focused = it.isFocused
                if (it.isFocused && state.query.isEmpty()) onType("")
            },
        )
        if (focused && state.suggestions.isNotEmpty()) {
            if (state.query.isBlank()) Text("Recent searches", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            state.suggestions.forEach { suggestion ->
                SuggestionRow(suggestion) { focus.clearFocus(); onPick(suggestion) }
            }
        }
    }
}

@Composable
private fun SuggestionRow(suggestion: Suggestion, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = SUGGESTION_HEIGHT)
        .clickable(role = Role.Button, onClickLabel = if (suggestion.kind == SuggestionKind.SONG) "Play now" else "Search",
            onClick = onClick).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(suggestion.text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
        val kind = when (suggestion.kind) {
            SuggestionKind.SONG -> suggestion.song?.artist?.ifBlank { null } ?: "Song"
            SuggestionKind.ARTIST -> "Artist"
            SuggestionKind.SEARCH -> null
        }
        kind?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** Search results: tap plays now on the room (quiet hours ask first), ⋯ = Play next / Add to queue. */
internal fun LazyListScope.musicSearchResults(
    results: SearchResults,
    room: String,
    enabled: Boolean,
    onPlay: (SongRef) -> Unit,
    onQueue: (SongRef, Boolean) -> Unit,
) {
    when (results) {
        SearchResults.Idle -> Unit
        is SearchResults.Searching -> item(key = "search-busy") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        is SearchResults.Message -> item(key = "search-message") { Note(results.text) }
        is SearchResults.Found -> {
            item(key = "search-title") { SectionTitle("Results for “${results.query}”") }
            items(results.tracks, key = { "search-${it.videoId}" }) { song ->
                SongRow(song, listOfNotNull(song.artist.ifBlank { null }, song.durationMs?.let(::playbackTime)).joinToString(" · "),
                    enabled, onPlay = { onPlay(song) }, actions = listOf(
                        SongAction("Play now") { onPlay(song) },
                        SongAction("Play next") { onQueue(song, true) },
                        SongAction("Add to queue") { onQueue(song, false) },
                    ))
            }
            results.quotaLeft?.takeIf { it < QUOTA_WARN_AT }?.let { left ->
                item(key = "search-quota") { Note(MusicCopy.quotaLeft(room, left)) }
            }
        }
    }
}
