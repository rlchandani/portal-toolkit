/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.share.music

import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Why a link can't play on a Portal, caught before Send. */
enum class Unplayable { EMBED, PRIVATE, AGE }

/** What YouTube's public oEmbed says about an item (no API key). */
data class MusicMeta(val title: String, val author: String, val thumbnail: String?)

sealed interface LinkCheck {
    data class Ok(val meta: MusicMeta) : LinkCheck
    data class Blocked(val reason: Unplayable) : LinkCheck
    /** Couldn't ask (offline, YouTube hiccup): the user may still send; the Portal has the final say. */
    data object Unknown : LinkCheck
}

/**
 * `https://www.youtube.com/oembed?url=<watch or playlist url>&format=json`. 401 means the owner
 * disabled embedding; 400/404 mean private or removed. Age restriction can't be seen here; the
 * Portal's player reports it after Send.
 */
object MusicOEmbed {
    const val ENDPOINT = "https://www.youtube.com/oembed"
    const val MAX_TITLE = 120
    const val MAX_AUTHOR = 80
    private val THUMB_HOSTS = setOf("i.ytimg.com", "yt3.ggpht.com", "lh3.googleusercontent.com")

    fun url(link: MusicLink): String = "$ENDPOINT?url=${URLEncoder.encode(link.url, "UTF-8")}&format=json"

    fun map(status: Int, body: String?): LinkCheck = when (status) {
        200 -> parse(body)?.let { LinkCheck.Ok(it) } ?: LinkCheck.Unknown
        401, 403 -> LinkCheck.Blocked(Unplayable.EMBED)
        400, 404 -> LinkCheck.Blocked(Unplayable.PRIVATE)
        else -> LinkCheck.Unknown
    }

    private fun parse(body: String?): MusicMeta? {
        val json = runCatching { JSONObject(body.orEmpty()) }.getOrNull() ?: return null
        val title = clean(json.optString("title"), MAX_TITLE)
        if (title.isEmpty()) return null
        return MusicMeta(title, cleanAuthor(json.optString("author_name")), safeThumb(json.optString("thumbnail_url")))
    }

    /** YouTube Music auto-channels are "Artist - Topic"; the Portal shows just the artist. */
    private fun cleanAuthor(raw: String): String = clean(raw, MAX_AUTHOR).removeSuffix(" - Topic").trim()

    fun clean(raw: String?, max: Int): String =
        raw.orEmpty().filterNot { it.isISOControl() }.replace(Regex("\\s+"), " ").trim().take(max).trim()

    /** Https thumbnails from YouTube's image hosts only; anything else is dropped. */
    fun safeThumb(raw: String?): String? {
        val url = raw?.trim().orEmpty()
        if (!url.startsWith("https://") || url.length > 512 || url.any { it.isWhitespace() }) return null
        val authority = url.removePrefix("https://").substringBefore('/').substringBefore('?')
        return url.takeIf { '@' !in authority && ':' !in authority && authority.lowercase() in THUMB_HOSTS }
    }
}

/** Fetches oEmbed over OkHttp; [fetch] is the seam tests replace. */
class MusicLinkChecker(
    private val fetch: suspend (String) -> Pair<Int, String?> = ::okHttpGet,
) {
    suspend fun check(link: MusicLink): LinkCheck = try {
        val (status, body) = fetch(MusicOEmbed.url(link))
        MusicOEmbed.map(status, body)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: IOException) { LinkCheck.Unknown }

    companion object {
        private val http by lazy {
            OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS)
                .callTimeout(6, TimeUnit.SECONDS).followRedirects(false).build()
        }

        private suspend fun okHttpGet(url: String): Pair<Int, String?> = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
                r.code to r.body?.let { b -> if (b.contentLength() in 0..65_536 || b.contentLength() == -1L) b.string().take(65_536) else null }
            }
        }

        /** Album art for the sheet, allowlisted hosts only; null on any failure. */
        suspend fun artBytes(url: String?): ByteArray? {
            val safe = MusicOEmbed.safeThumb(url) ?: return null
            return withContext(Dispatchers.IO) {
                runCatching {
                    http.newCall(Request.Builder().url(safe).get().build()).execute().use { r ->
                        if (!r.isSuccessful) null else r.body?.bytes()?.takeIf { it.size <= 2_000_000 }
                    }
                }.getOrNull()
            }
        }
    }
}
