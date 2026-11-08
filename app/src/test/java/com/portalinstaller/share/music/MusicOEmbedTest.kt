/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.share.music

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** oEmbed answers → what the sheet says before Send. */
class MusicOEmbedTest {
    private val link = MusicLink.Video("dQw4w9WgXcQ", true, "https://music.youtube.com/watch?v=dQw4w9WgXcQ")
    private fun body(title: String = "Saltwater hours", author: String = "Marrow & Vale - Topic",
        thumb: String = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg") =
        JSONObject().put("title", title).put("author_name", author).put("thumbnail_url", thumb).toString()

    @Test fun url_encodesTheCleanLink() {
        assertEquals("https://www.youtube.com/oembed?url=https%3A%2F%2Fmusic.youtube.com%2Fwatch%3Fv%3DdQw4w9WgXcQ&format=json",
            MusicOEmbed.url(link))
    }

    @Test fun ok_givesTitleArtistAndSafeArt() {
        assertEquals(LinkCheck.Ok(MusicMeta("Saltwater hours", "Marrow & Vale", "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg")),
            MusicOEmbed.map(200, body()))
        val evil = MusicOEmbed.map(200, body(thumb = "http://evil.example/x.jpg")) as LinkCheck.Ok
        assertNull(evil.meta.thumbnail)
    }

    @Test fun statusCodes_mapToSpecificReasons() {
        assertEquals(LinkCheck.Blocked(Unplayable.EMBED), MusicOEmbed.map(401, ""))
        assertEquals(LinkCheck.Blocked(Unplayable.EMBED), MusicOEmbed.map(403, ""))
        assertEquals(LinkCheck.Blocked(Unplayable.PRIVATE), MusicOEmbed.map(404, "Not Found"))
        assertEquals(LinkCheck.Blocked(Unplayable.PRIVATE), MusicOEmbed.map(400, "Bad Request"))
        assertEquals(LinkCheck.Unknown, MusicOEmbed.map(500, ""))
        assertEquals(LinkCheck.Unknown, MusicOEmbed.map(429, ""))
        assertEquals(LinkCheck.Unknown, MusicOEmbed.map(200, "not json"))
        assertEquals(LinkCheck.Unknown, MusicOEmbed.map(200, body(title = "  ")))
    }

    @Test fun networkErrors_letTheUserStillSend() = runTest {
        assertEquals(LinkCheck.Unknown, MusicLinkChecker { throw IOException("offline") }.check(link))
        assertEquals(LinkCheck.Blocked(Unplayable.EMBED), MusicLinkChecker { 401 to null }.check(link))
    }

    @Test fun text_isCleanedAndCapped() {
        val ok = MusicOEmbed.map(200, body(title = "A\u0000 \n B" + "x".repeat(300))) as LinkCheck.Ok
        assertEquals(MusicOEmbed.MAX_TITLE, ok.meta.title.length)
        assertEquals("A B", ok.meta.title.take(3))
    }
}
