/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.music

import com.portalinstaller.data.companion.CompanionSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal sealed interface SearchResults {
    data object Idle : SearchResults
    data class Searching(val query: String) : SearchResults
    data class Found(val query: String, val tracks: List<SongRef>, val quotaLeft: Int?) : SearchResults
    data class Message(val text: String) : SearchResults
}

internal sealed interface HistoryView {
    data object Loading : HistoryView
    data class Ready(val history: MusicHistory) : HistoryView
    data class Message(val text: String) : HistoryView
}

/** Search and "Recently played" for one room, replaced (never mutated) on every change. */
internal data class MusicSearchState(
    val roomId: String? = null,
    val query: String = "",
    val suggestions: List<Suggestion> = emptyList(),
    val results: SearchResults = SearchResults.Idle,
    val history: HistoryView = HistoryView.Loading,
    /** This room's Hearthpane has no search/history routes yet (404). */
    val unsupported: Boolean = false,
    val confirmClear: Boolean = false,
    val notice: MusicNotice? = null,
)

/**
 * The Music page's search field and "Recently played" for the one selected room. Suggestions ask
 * the Portal [debounceMs] after the last keystroke (local data there, no YouTube quota); only
 * [submit] spends a YouTube search. Answers for an older query or another room are dropped.
 */
internal class MusicSearchController(
    private val library: MusicLibraryClient,
    private val session: (String) -> CompanionSession?,
    private val recentStore: RecentSearchStore,
    private val scope: CoroutineScope,
    private val debounceMs: Long = MusicSuggestions.DEBOUNCE_MS,
) {
    private val mutable = MutableStateFlow(MusicSearchState())
    val state: StateFlow<MusicSearchState> = mutable.asStateFlow()
    private var recent: List<String> = emptyList()
    private var suggestJob: Job? = null
    private var searchJob: Job? = null
    private var playing: String? = null
    private var playingKnown = false

    /** Points search and history at [roomId] (null: several or no rooms chosen). */
    fun attach(roomId: String?) {
        if (roomId == state.value.roomId) return
        suggestJob?.cancel()
        searchJob?.cancel()
        recent = recentStore.load()
        playingKnown = false
        mutable.value = MusicSearchState(roomId = roomId, notice = state.value.notice)
        if (roomId != null) refreshHistory()
    }

    /** The field changed: local recent searches show at once, the Portal's suggestions after a pause. */
    fun type(text: String) {
        mutable.update { it.copy(query = text, suggestions = MusicSuggestions.merge(text, recent, emptyList())) }
        suggestJob?.cancel()
        val room = state.value.roomId ?: return
        val q = MusicSuggestions.remoteQuery(text) ?: return
        if (state.value.unsupported) return
        suggestJob = scope.launch {
            delay(debounceMs)
            val portal = session(room) ?: return@launch
            val remote = when (val answer = library.suggest(portal, q)) {
                is LibraryResult.Ok -> answer.value
                LibraryResult.Legacy -> { markUnsupported(room); return@launch }
                // Suggestions are a convenience: on a miss the phone's own recent searches still show.
                LibraryResult.Quota, is LibraryResult.Failed -> return@launch
            }
            mutable.update { s ->
                if (s.roomId != room || s.query != text) s
                else s.copy(suggestions = MusicSuggestions.merge(text, recent, remote))
            }
        }
    }

    /** Runs the YouTube search (Enter, or a search/artist suggestion). */
    fun submit(text: String = state.value.query) {
        val q = MusicSuggestions.clean(text)
        val room = state.value.roomId ?: return
        if (q.isEmpty()) return
        val portal = session(room) ?: return
        suggestJob?.cancel()
        searchJob?.cancel()
        recent = RecentSearches.add(recent, q)
        recentStore.save(recent)
        mutable.update { it.copy(query = q, suggestions = emptyList(), results = SearchResults.Searching(q)) }
        searchJob = scope.launch {
            val results = when (val answer = library.search(portal, q)) {
                is LibraryResult.Ok -> if (answer.value.tracks.isEmpty()) SearchResults.Message(MusicCopy.noResults(q))
                    else SearchResults.Found(q, answer.value.tracks, answer.value.quotaLeft)
                LibraryResult.Quota -> SearchResults.Message(MusicCopy.quota(portal.device.name))
                LibraryResult.Legacy -> { markUnsupported(room); SearchResults.Idle }
                is LibraryResult.Failed -> SearchResults.Message(answer.message)
            }
            mutable.update { s -> if (s.roomId != room || s.query != q) s else s.copy(results = results) }
        }
    }

    /** Empties the field and the results; recent searches show again. */
    fun clear() {
        suggestJob?.cancel()
        searchJob?.cancel()
        mutable.update { it.copy(query = "", suggestions = emptyList(), results = SearchResults.Idle) }
    }

    /** Hides the suggestion list (the field lost focus) without touching the query. */
    fun hideSuggestions() = mutable.update { it.copy(suggestions = emptyList()) }

    /** The room's current song (from polling); a new one means a new "Recently played" row. */
    fun playingChanged(videoId: String?) {
        if (playingKnown && videoId != null && videoId != playing) refreshHistory()
        playing = videoId
        playingKnown = true
    }

    fun refreshHistory() {
        val room = state.value.roomId ?: return
        val portal = session(room) ?: return
        scope.launch {
            val view = when (val answer = library.history(portal)) {
                is LibraryResult.Ok -> HistoryView.Ready(answer.value)
                LibraryResult.Legacy -> { markUnsupported(room); return@launch }
                LibraryResult.Quota -> HistoryView.Message(MusicCopy.failed(portal.device.name))
                is LibraryResult.Failed -> HistoryView.Message(answer.message)
            }
            mutable.update { s -> if (s.roomId != room) s else s.copy(history = view) }
        }
    }

    fun remove(videoId: String) {
        val room = state.value.roomId ?: return
        val portal = session(room) ?: return
        mutable.update { s -> s.copy(history = withoutSong(s.history, videoId)) }
        scope.launch { finish(room, library.removeFromHistory(portal, videoId), success = null) }
    }

    fun askClear() = mutable.update { it.copy(confirmClear = it.roomId != null) }
    fun cancelClear() = mutable.update { it.copy(confirmClear = false) }

    fun confirmClear() {
        val room = state.value.roomId ?: return
        val portal = session(room) ?: return
        mutable.update { it.copy(confirmClear = false) }
        scope.launch { finish(room, library.clearHistory(portal), success = MusicCopy.HISTORY_CLEARED) }
    }

    private fun finish(room: String, result: LibraryResult<Unit>, success: String?) {
        when (result) {
            is LibraryResult.Ok -> success?.let(::notify)
            LibraryResult.Legacy -> markUnsupported(room)
            LibraryResult.Quota -> Unit
            is LibraryResult.Failed -> notify(result.message)
        }
        refreshHistory()
    }

    private fun markUnsupported(room: String) = mutable.update { s ->
        if (s.roomId != room) s else s.copy(unsupported = true, suggestions = emptyList(), results = SearchResults.Idle)
    }

    private fun withoutSong(view: HistoryView, videoId: String): HistoryView {
        if (view !is HistoryView.Ready) return view
        val kept = view.history.entries.filter { it.song.videoId != videoId }
        val gone = view.history.entries.size - kept.size
        return HistoryView.Ready(MusicHistory(kept, (view.history.total - gone).coerceAtLeast(kept.size)))
    }

    private fun notify(text: String) = mutable.update { it.copy(notice = MusicNotice(text, (it.notice?.seq ?: 0) + 1)) }
}
