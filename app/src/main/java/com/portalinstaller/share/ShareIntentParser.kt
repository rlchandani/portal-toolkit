/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.share

/** What another app handed us through Android's share sheet, validated and size-limited. */
sealed interface SharePayload {
    data class Images(val uris: List<String>) : SharePayload
    data class Note(val text: String) : SharePayload
    data class Link(val url: String, val title: String?) : SharePayload
}

sealed interface ShareParse {
    data class Ok(val payload: SharePayload) : ShareParse
    data class Rejected(val message: String) : ShareParse
}

/** Pure: callers pass the intent's action, MIME type, text/subject extras and stream URIs as strings. */
object ShareIntentParser {
    const val ACTION_SEND = "android.intent.action.SEND"
    const val ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"
    const val MAX_IMAGES = 50
    const val MAX_NOTE_CHARS = 1_000
    const val MAX_URL_CHARS = 2_048
    const val MAX_TITLE_CHARS = 120
    private const val OWN_AUTHORITY_PREFIX = "com.portalinstaller"
    private val URL = Regex("https?://[^\\s<>\"]+", RegexOption.IGNORE_CASE)

    fun parse(action: String?, type: String?, text: CharSequence?, subject: String?, streams: List<String>): ShareParse {
        if (action != ACTION_SEND && action != ACTION_SEND_MULTIPLE) return ShareParse.Rejected("Nothing to share.")
        val mime = type.orEmpty().lowercase()
        if (mime.startsWith("image/")) {
            // Only content:// URIs: a file:// URI would let another app make Toolkit read its own private files.
            val uris = streams.filter { it.startsWith("content://") && !it.startsWith("content://$OWN_AUTHORITY_PREFIX") }.distinct()
            if (uris.isEmpty()) return ShareParse.Rejected("No photos were shared.")
            if (uris.size > MAX_IMAGES) return ShareParse.Rejected("Share at most $MAX_IMAGES photos at a time.")
            return ShareParse.Ok(SharePayload.Images(uris))
        }
        if (action != ACTION_SEND || !mime.startsWith("text/")) return ShareParse.Rejected("Calder Pocket can share photos, text and links.")
        val body = text?.toString()?.replace("\u0000", "")?.trim().orEmpty()
        if (body.isEmpty()) return ShareParse.Rejected("The shared text is empty.")
        link(body, subject)?.let { return ShareParse.Ok(it) }
        if (body.length > MAX_NOTE_CHARS) return ShareParse.Rejected("Notes can be at most $MAX_NOTE_CHARS characters.")
        return ShareParse.Ok(SharePayload.Note(body))
    }

    /** A link is a lone URL, or one URL at the end of a short caption ("Pasta recipe https://…"). */
    fun link(body: String, subject: String?): SharePayload.Link? {
        val matches = URL.findAll(body).toList()
        if (matches.size != 1) return null
        val match = matches.single()
        if (match.range.last != body.lastIndex) return null
        val url = match.value.trimEnd('.', ',', ')', ']')
        if (!validUrl(url)) return null
        val caption = body.substring(0, match.range.first).trim().trimEnd(':', '-', '·').trim()
        if (caption.length > MAX_TITLE_CHARS) return null
        val title = subject?.trim()?.takeIf { it.isNotEmpty() } ?: caption.takeIf { it.isNotEmpty() }
        return SharePayload.Link(url, title?.take(MAX_TITLE_CHARS))
    }

    fun validUrl(url: String): Boolean {
        if (url.length > MAX_URL_CHARS || url.any { it.isWhitespace() || it.isISOControl() }) return false
        val rest = url.substringAfter("://", "")
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        return (url.startsWith("http://", true) || url.startsWith("https://", true)) &&
            authority.isNotEmpty() && '@' !in authority
    }
}
