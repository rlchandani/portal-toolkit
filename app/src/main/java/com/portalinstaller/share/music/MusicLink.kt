/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.share.music

import java.net.URI
import java.net.URLDecoder

/** A YouTube / YouTube Music link found in shared text, reduced to ids (tracking parameters dropped). */
sealed interface MusicLink {
    /** The clean link (no `si`, `feature`, `pp`, …) used for the oEmbed check and "Send as a link". */
    val url: String
    /** music.youtube.com links (and every playlist) count as music; plain youtube.com videos may not be. */
    val isMusic: Boolean

    data class Video(val videoId: String, override val isMusic: Boolean, override val url: String) : MusicLink

    data class Playlist(val listId: String, override val url: String) : MusicLink {
        override val isMusic: Boolean get() = true
        /** YouTube Music album playlists (`OLAK5uy_…`). */
        val isAlbum: Boolean get() = listId.startsWith(ALBUM_PREFIX)
    }

    /** A personal mix/radio (`list=RD…`): only its seed song, if any, can play on a Portal. */
    data class Mix(val listId: String, val seedVideoId: String?, override val url: String) : MusicLink {
        override val isMusic: Boolean get() = true
        fun seed(): Video? = seedVideoId?.let { Video(it, true, MusicLinks.watchUrl(it, music = true)) }
    }

    companion object {
        const val ALBUM_PREFIX = "OLAK5uy_"
    }
}

/** Pure parser. Accepts youtube.com (www/m), music.youtube.com and youtu.be, including Shorts. */
object MusicLinks {
    private val URL = Regex("https?://[^\\s<>\"]+", RegexOption.IGNORE_CASE)
    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
    private val LIST_ID = Regex("[A-Za-z0-9_-]{10,64}")
    private const val MIX_PREFIX = "RD"
    private const val MAX_TEXT = 4_000

    fun isVideoId(id: String?): Boolean = id != null && VIDEO_ID.matches(id)
    fun isListId(id: String?): Boolean = id != null && LIST_ID.matches(id)

    /** The first recognisable link in [text] ("Check out … on YouTube Music https://…" works too). */
    fun find(text: String?): MusicLink? {
        val body = text?.take(MAX_TEXT) ?: return null
        return URL.findAll(body).mapNotNull { parse(it.value.trimEnd('.', ',', ')', ']', '!', '?')) }.firstOrNull()
    }

    fun parse(raw: String): MusicLink? {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.rawUserInfo != null) return null
        val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return null
        val path = uri.rawPath.orEmpty().trimEnd('/')
        val query = params(uri.rawQuery)
        return when (host) {
            "youtu.be" -> fromIds(path.removePrefix("/").substringBefore('/'), query["list"], music = false)
            "youtube.com" -> fromPath(path, query, music = false)
            "music.youtube.com" -> fromPath(path, query, music = true)
            else -> null
        }
    }

    private fun fromPath(path: String, query: Map<String, String>, music: Boolean): MusicLink? = when {
        path == "/watch" -> fromIds(query["v"], query["list"], music)
        path == "/playlist" -> fromIds(null, query["list"], music)
        path.startsWith("/shorts/") -> fromIds(path.removePrefix("/shorts/").substringBefore('/'), null, music)
        else -> null
    }

    /** A `v=` alongside a normal playlist is the song that was shared; `RD…` lists are mixes. */
    private fun fromIds(videoId: String?, listId: String?, music: Boolean): MusicLink? {
        val video = videoId?.takeIf(::isVideoId)
        val list = listId?.takeIf(::isListId)
        return when {
            list != null && list.startsWith(MIX_PREFIX) -> MusicLink.Mix(list, video, mixUrl(list, video))
            video != null -> MusicLink.Video(video, music, watchUrl(video, music))
            list != null -> MusicLink.Playlist(list, playlistUrl(list))
            else -> null
        }
    }

    fun watchUrl(videoId: String, music: Boolean): String =
        "https://${if (music) "music.youtube.com" else "www.youtube.com"}/watch?v=$videoId"

    fun playlistUrl(listId: String): String = "https://www.youtube.com/playlist?list=$listId"

    private fun mixUrl(listId: String, videoId: String?): String =
        if (videoId == null) "https://music.youtube.com/playlist?list=$listId"
        else "https://music.youtube.com/watch?v=$videoId&list=$listId"

    private fun params(raw: String?): Map<String, String> = raw.orEmpty().split('&').mapNotNull { pair ->
        val key = pair.substringBefore('=', "")
        val value = pair.substringAfter('=', "")
        if (key.isEmpty() || value.isEmpty()) null
        else key to runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }.distinctBy { it.first }.toMap()
}
