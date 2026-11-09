/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.music

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicLibraryParserTest {
    private fun row(id: String, title: String, at: Long = 1_000, room: String = "Kitchen") = JSONObject()
        .put("videoId", id).put("title", title).put("artist", "Artist").put("thumb", "https://i.ytimg.com/vi/$id/hq.jpg")
        .put("playedAtMs", at).put("room", room)

    @Test fun historyIsNewestFirstAndKeepsOnlyTheNewestPlayOfASong() {
        val json = JSONObject().put("ok", true).put("total", 3).put("history", JSONArray()
            .put(row("aaa", "Old A", at = 100)).put(row("bbb", "B", at = 300)).put(row("aaa", "New A", at = 500)))
        val h = MusicLibraryParser.history(json)
        assertEquals(listOf("New A", "B"), h.entries.map { it.song.title })
        assertEquals("Kitchen", h.entries.first().room)
        assertEquals(3, h.total)
    }

    @Test fun historyDropsMalformedRowsAndUnsafeThumbs() {
        val json = JSONObject().put("history", JSONArray()
            .put(row("bad id!", "Bad id")).put(row("ok1", "No time", at = 0))
            .put(row("ok2", "Good").put("thumb", "http://insecure/x.jpg")).put("not an object"))
        val h = MusicLibraryParser.history(json)
        assertEquals(listOf("Good"), h.entries.map { it.song.title })
        assertNull(h.entries.single().song.thumb)
        assertEquals(1, h.total)
    }

    @Test fun suggestionsKeepKnownKindsAndCapAtEight() {
        val array = JSONArray()
            .put(JSONObject().put("text", "Hello").put("kind", "song").put("videoId", "vid1").put("artist", "Adele"))
            .put(JSONObject().put("text", "Adele").put("kind", "artist"))
            .put(JSONObject().put("text", "hello world").put("kind", "search"))
            .put(JSONObject().put("text", "Odd").put("kind", "playlist"))
            .put(JSONObject().put("text", "Song without id").put("kind", "song"))
        repeat(10) { array.put(JSONObject().put("text", "more $it").put("kind", "search")) }
        val list = MusicLibraryParser.suggestions(JSONObject().put("suggestions", array))
        assertEquals(MusicLibraryParser.MAX_SUGGESTIONS, list.size)
        assertEquals(SongRef("vid1", "Hello", "Adele", null), list[0].song)
        assertEquals(listOf(SuggestionKind.SONG, SuggestionKind.ARTIST, SuggestionKind.SEARCH), list.take(3).map { it.kind })
        assertTrue(list.none { it.text == "Odd" || it.text == "Song without id" })
    }

    @Test fun searchReadsTracksQuotaAndDuration() {
        val json = JSONObject().put("ok", true).put("quotaLeft", 4).put("tracks", JSONArray()
            .put(JSONObject().put("videoId", "v1").put("title", "One").put("artist", "A").put("durationMs", 180_000))
            .put(JSONObject().put("videoId", "v1").put("title", "Dupe"))
            .put(JSONObject().put("videoId", "v2").put("title", "Two").put("durationMs", JSONObject.NULL)))
        val found = MusicLibraryParser.search(json)
        assertEquals(listOf("One", "Two"), found.tracks.map { it.title })
        assertEquals(180_000L, found.tracks[0].durationMs)
        assertNull(found.tracks[1].durationMs)
        assertEquals(4, found.quotaLeft)
        assertNull(MusicLibraryParser.search(JSONObject().put("tracks", JSONArray())).quotaLeft)
    }

    @Test fun playerReadsSleepTimerAndNightCap() {
        val json = JSONObject().put("player", JSONObject().put("state", "idle").put("sleepUntilMs", 5_000)
            .put("sleepEndOfSong", false).put("nightCap", JSONObject().put("active", true).put("maxLevel", 6)))
        val p = PortalMusicParser.player(json, 0)
        assertTrue(p.sleepSupported)
        assertEquals(5_000L, p.sleepUntilMs)
        assertEquals(NightCap(active = true, maxLevel = 6), p.nightCap)
    }

    @Test fun olderPlayerHasNoSleepTimerAndBadNightCapIsIgnored() {
        val older = PortalMusicParser.player(JSONObject().put("player", JSONObject().put("state", "idle")), 0)
        assertFalse(older.sleepSupported)
        assertNull(older.sleepUntilMs)
        assertNull(older.nightCap)
        val odd = PortalMusicParser.player(JSONObject().put("player", JSONObject().put("sleepUntilMs", JSONObject.NULL)
            .put("nightCap", JSONObject().put("active", true).put("maxLevel", -3))), 0)
        assertTrue(odd.sleepSupported)
        assertNull(odd.sleepUntilMs)
        assertNull(odd.nightCap)
    }

    @Test fun trackCommandsCarryTheSongAndPosition() {
        val song = SongRef("vid1", "Hello", "Adele", null)
        val play = MusicCommand.PlayTrack(song).body(playAnyway = true)
        assertEquals("playtrack", play.getString("cmd"))
        assertEquals("vid1", play.getString("videoId"))
        assertEquals("Adele", play.getString("artist"))
        assertFalse(play.has("thumb"))
        assertTrue(play.getBoolean("playAnyway"))
        assertTrue(MusicCommand.PlayTrack(song).startsPlayback)
        val next = MusicCommand.QueueTrack(song, next = true)
        assertEquals("next", next.body().getString("position"))
        assertEquals("end", MusicCommand.QueueTrack(song, next = false).body().getString("position"))
        assertFalse(next.startsPlayback)
    }

    @Test fun sleepCommandUsesTheWireMinutes() {
        assertEquals(listOf(0, 15, 30, 60, -1), SleepChoice.entries.map { MusicCommand.Sleep(it).body().getInt("minutes") })
        assertEquals("sleep", MusicCommand.Sleep(SleepChoice.OFF).body().getString("cmd"))
    }
}
