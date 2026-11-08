/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.share.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every link shape YouTube and YouTube Music hand to the share sheet. */
class MusicLinkTest {
    private val id = "dQw4w9WgXcQ"
    private val pl = "PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG"

    private fun video(text: String) = MusicLinks.find(text) as MusicLink.Video

    @Test fun musicWatch_isMusic_andTrackingIsStripped() {
        val v = video("https://music.youtube.com/watch?v=$id&si=AbCdEf123&feature=share")
        assertEquals(id, v.videoId)
        assertTrue(v.isMusic)
        assertEquals("https://music.youtube.com/watch?v=$id", v.url)
    }

    @Test fun youtubeWatch_mobile_shortLinks_andShorts_areVideos() {
        listOf("https://www.youtube.com/watch?v=$id", "https://youtube.com/watch?feature=x&v=$id&t=42s",
            "https://m.youtube.com/watch?v=$id", "https://youtu.be/$id?si=xyz", "https://youtu.be/$id",
            "https://www.youtube.com/shorts/$id?feature=share", "http://youtube.com/shorts/$id/").forEach { url ->
            val v = video(url)
            assertEquals(url, id, v.videoId)
            assertFalse(url, v.isMusic)
            assertEquals("https://www.youtube.com/watch?v=$id", v.url)
        }
    }

    @Test fun extraWordsAroundTheUrl_areIgnored() {
        assertEquals(id, video("Check out Saltwater hours on YouTube Music: https://music.youtube.com/watch?v=$id&si=q. Enjoy!").videoId)
        assertEquals(id, video("look (https://youtu.be/$id)").videoId)
    }

    @Test fun playlistsAndAlbums() {
        val p = MusicLinks.find("https://music.youtube.com/playlist?list=$pl&si=x") as MusicLink.Playlist
        assertEquals(pl, p.listId)
        assertFalse(p.isAlbum)
        assertEquals("https://www.youtube.com/playlist?list=$pl", p.url)
        val album = MusicLinks.find("https://music.youtube.com/playlist?list=OLAK5uy_kNbMC4n5UVbm2GdMEpT_cZ3NsL8vJxz5k") as MusicLink.Playlist
        assertTrue(album.isAlbum)
        assertTrue(album.isMusic)
    }

    @Test fun aSongSharedFromInsideAPlaylist_isTheSong() {
        assertEquals(id, video("https://music.youtube.com/watch?v=$id&list=$pl").videoId)
    }

    @Test fun mixes_offerTheSeedSongWhenThereIsOne() {
        listOf("RDAMVM$id", "RD$id", "RDCLAK5uy_lBNUteBRencHzKelu5iDHwLF6mYqjL-JU").forEach { mix ->
            val m = MusicLinks.find("https://music.youtube.com/watch?v=$id&list=$mix") as MusicLink.Mix
            assertEquals(id, m.seedVideoId)
            assertEquals(MusicLink.Video(id, true, "https://music.youtube.com/watch?v=$id"), m.seed())
        }
        val bare = MusicLinks.find("https://music.youtube.com/playlist?list=RDCLAK5uy_lBNUteBRencHzKelu5iDHwLF6mYqjL-JU") as MusicLink.Mix
        assertNull(bare.seedVideoId)
        assertNull(bare.seed())
    }

    @Test fun junk_isNotAMusicLink() {
        listOf(null, "", "just words", "https://example.com/watch?v=$id", "https://youtube.com.evil.example/watch?v=$id",
            "https://www.youtube.com/watch?v=short", "https://www.youtube.com/watch?v=$id'x",
            "https://www.youtube.com/channel/UC123", "https://music.youtube.com/browse/MPREb_abc",
            "https://user@youtube.com/watch?v=$id", "ftp://youtube.com/watch?v=$id",
            "https://www.youtube.com/playlist?list=short").forEach { assertNull(it, MusicLinks.find(it)) }
    }

    @Test fun theFirstRecognisableLinkWins() {
        assertEquals(id, video("https://example.com and https://youtu.be/$id").videoId)
    }
}
