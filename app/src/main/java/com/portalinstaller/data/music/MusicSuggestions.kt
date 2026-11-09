/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.music

import java.text.Normalizer

/**
 * Pure rules for the search field: what to ask the Portal for, how the phone's recent searches
 * match what's typed, and how they merge with the Portal's (local-only, no quota) suggestions.
 */
internal object MusicSuggestions {
    /** Wait this long after the last keystroke before asking the Portal. */
    const val DEBOUNCE_MS = 200L
    /** Longer queries are cut to this before being sent anywhere. */
    const val MAX_QUERY = 100
    private val SPACES = Regex("\\s+")
    private val MARKS = Regex("\\p{Mn}+")

    /** The tidy form of what was typed: trimmed, single-spaced, capped; empty means "nothing". */
    fun clean(raw: String): String = raw.trim().replace(SPACES, " ").take(MAX_QUERY)

    /** The query to send to `/suggest` for [raw], or null when there's nothing to ask about. */
    fun remoteQuery(raw: String): String? = clean(raw).ifEmpty { null }

    /** Case- and accent-insensitive form used for matching and de-duplicating. */
    fun fold(text: String): String = MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase()

    /** How well [candidate] matches [query]: lower is better, null is no match. */
    fun rank(candidate: String, query: String): Int? {
        val c = fold(candidate)
        val q = fold(clean(query))
        if (q.isEmpty()) return null
        return when {
            c.startsWith(q) -> RANK_PREFIX
            c.split(' ').any { it.startsWith(q) } || c.contains(" $q") -> RANK_WORD_START
            c.contains(q) -> RANK_CONTAINS
            else -> null
        }
    }

    /** Recent searches that match [query], best match first, newest first within a rank. */
    fun matchingRecent(recent: List<String>, query: String, limit: Int = MusicLibraryParser.MAX_SUGGESTIONS): List<String> =
        recent.withIndex()
            .mapNotNull { (index, text) -> rank(text, query)?.let { Triple(it, index, text) } }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .map { it.third }
            .take(limit)

    /**
     * What shows under the field: the phone's matching recent searches first, then the Portal's
     * suggestions, without repeats, at most [limit]. With nothing typed: just recent searches.
     */
    fun merge(
        query: String,
        recent: List<String>,
        remote: List<Suggestion>,
        limit: Int = MusicLibraryParser.MAX_SUGGESTIONS,
    ): List<Suggestion> {
        val local = if (clean(query).isEmpty()) recent.take(limit) else matchingRecent(recent, query, limit)
        val all = local.map { Suggestion(it, SuggestionKind.SEARCH) } + remote
        return all.distinctBy { s -> s.song?.videoId?.let { "song:$it" } ?: "text:${fold(s.text)}" }.take(limit)
    }

    /** Tapping a song plays it now; tapping a search or an artist runs the YouTube search. */
    fun pick(suggestion: Suggestion): SuggestionPick {
        val song = suggestion.song
        return if (suggestion.kind == SuggestionKind.SONG && song != null) SuggestionPick.Play(song)
        else SuggestionPick.Search(suggestion.text)
    }

    private const val RANK_PREFIX = 0
    private const val RANK_WORD_START = 1
    private const val RANK_CONTAINS = 2
}

internal sealed interface SuggestionPick {
    data class Play(val song: SongRef) : SuggestionPick
    data class Search(val query: String) : SuggestionPick
}

/** The phone's own recent searches (last [MAX]), newest first. Pure: returns new lists. */
internal object RecentSearches {
    const val MAX = 20

    /** [query] moved (or added) to the front; repeats differing only in case/accents collapse. */
    fun add(recent: List<String>, query: String): List<String> {
        val clean = MusicSuggestions.clean(query)
        if (clean.isEmpty()) return recent
        val key = MusicSuggestions.fold(clean)
        return (listOf(clean) + recent.filter { MusicSuggestions.fold(it) != key }).take(MAX)
    }

    /** Stored text is untrusted: tidy, de-duplicate and cap it. */
    fun sanitize(stored: List<String>): List<String> =
        stored.map(MusicSuggestions::clean).filter { it.isNotEmpty() }.distinctBy(MusicSuggestions::fold).take(MAX)
}
