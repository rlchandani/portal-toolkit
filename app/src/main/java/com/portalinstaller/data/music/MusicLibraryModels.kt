/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.music

import org.json.JSONArray
import org.json.JSONObject

/** A YouTube song the phone can ask a Portal to play or queue (a search result or a history row). */
internal data class SongRef(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumb: String?,
    val durationMs: Long? = null,
) {
    /** Adds the song fields the Portal's `playtrack`/`queuetrack` commands expect. */
    fun into(body: JSONObject): JSONObject = body.put("videoId", videoId).put("title", title)
        .put("artist", artist).putOpt("thumb", thumb)
}

/** One "Recently played" row. [playedAtMs] is the Portal's wall-clock time; [room] where it played. */
internal data class HistoryEntry(val song: SongRef, val playedAtMs: Long, val room: String)

internal data class MusicHistory(val entries: List<HistoryEntry>, val total: Int)

internal enum class SuggestionKind { SEARCH, SONG, ARTIST }

/** One line under the search field. [song] is set for song suggestions (tap plays it now). */
internal data class Suggestion(val text: String, val kind: SuggestionKind, val song: SongRef? = null)

internal data class SearchFound(val tracks: List<SongRef>, val quotaLeft: Int?)

/** The Portal's answer to a library call (`/remote/ytmusic/{history,suggest,search}`). */
internal sealed interface LibraryResult<out T> {
    data class Ok<T>(val value: T) : LibraryResult<T>
    /** The Portal's Hearthpane predates this route (404). */
    data object Legacy : LibraryResult<Nothing>
    /** Today's YouTube searches are used up on this Portal (429). */
    data object Quota : LibraryResult<Nothing>
    data class Failed(val message: String) : LibraryResult<Nothing>
}

/** Validates library answers: anything malformed is dropped, never shown. */
internal object MusicLibraryParser {
    const val MAX_HISTORY = 200
    const val MAX_SUGGESTIONS = 8
    private const val MAX_TRACKS = 50
    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{1,64}")

    fun history(json: JSONObject): MusicHistory {
        val entries = list(json.optJSONArray("history"), MAX_HISTORY) { item ->
            val song = song(item, "title") ?: return@list null
            val playedAt = item.optLong("playedAtMs", 0).takeIf { it > 0 } ?: return@list null
            HistoryEntry(song, playedAt, PortalMusicParser.text(item, "room").orEmpty())
        }.sortedByDescending { it.playedAtMs }.distinctBy { it.song.videoId } // the newest play of a song wins
        return MusicHistory(entries, json.optInt("total", entries.size).coerceAtLeast(entries.size))
    }

    fun suggestions(json: JSONObject): List<Suggestion> =
        list(json.optJSONArray("suggestions"), MAX_SUGGESTIONS) { item ->
            val text = PortalMusicParser.text(item, "text") ?: return@list null
            when (item.optString("kind")) {
                "song" -> song(item, "text")?.let { Suggestion(text, SuggestionKind.SONG, it) }
                "artist" -> Suggestion(text, SuggestionKind.ARTIST)
                "search" -> Suggestion(text, SuggestionKind.SEARCH)
                else -> null
            }
        }

    fun search(json: JSONObject): SearchFound = SearchFound(
        tracks = list(json.optJSONArray("tracks"), MAX_TRACKS) { song(it, "title") }.distinctBy { it.videoId },
        quotaLeft = if (json.has("quotaLeft") && !json.isNull("quotaLeft")) json.optInt("quotaLeft").coerceAtLeast(0) else null,
    )

    private fun song(json: JSONObject, titleKey: String): SongRef? {
        val id = json.optString("videoId").takeIf { VIDEO_ID.matches(it) } ?: return null
        val duration = if (json.isNull("durationMs")) null
            else json.optLong("durationMs", 0).takeIf { it in 1..PortalMusicParser.MAX_MS }
        return SongRef(id, PortalMusicParser.text(json, titleKey) ?: "YouTube video",
            PortalMusicParser.text(json, "artist").orEmpty(),
            PortalMusicParser.text(json, "thumb")?.takeIf { it.startsWith("https://") }, duration)
    }

    private fun <T> list(array: JSONArray?, max: Int, item: (JSONObject) -> T?): List<T> =
        if (array == null) emptyList()
        else (0 until array.length()).asSequence().mapNotNull { array.optJSONObject(it)?.let(item) }.take(max).toList()
}
