/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.music

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicTimeTextTest {
    private val min = 60_000L
    private val hour = 60 * min
    private val day = 24 * hour

    @Test fun agoReadsNaturally() {
        val now = 100 * day
        assertEquals("Just now", MusicTimeText.ago(now - 30_000, now))
        assertEquals("Just now", MusicTimeText.ago(now + 5_000, now))
        assertEquals("5 min ago", MusicTimeText.ago(now - 5 * min, now))
        assertEquals("2 h ago", MusicTimeText.ago(now - 2 * hour - 10 * min, now))
        assertEquals("Yesterday", MusicTimeText.ago(now - 30 * hour, now))
        assertEquals("3 days ago", MusicTimeText.ago(now - 3 * day, now))
        assertEquals("Over a week ago", MusicTimeText.ago(now - 8 * day, now))
    }

    @Test fun historyDetailNamesTheRoom() {
        val entry = HistoryEntry(SongRef("v", "T", "Adele", null), playedAtMs = 0, room = "Living Area")
        assertEquals("Adele · Living Area · 2 h ago", MusicTimeText.historyDetail(entry, 2 * hour))
        assertEquals("2 h ago", MusicTimeText.historyDetail(entry.copy(song = entry.song.copy(artist = ""), room = ""), 2 * hour))
    }

    private fun player(until: Long?, endOfSong: Boolean = false) = PortalMusicParser.player(JSONObject().put("player",
        JSONObject().put("sleepUntilMs", until ?: JSONObject.NULL).put("sleepEndOfSong", endOfSong)), 0)

    @Test fun sleepLineRoundsUpToWholeMinutes() {
        assertEquals("Stops in 23 min", MusicTimeText.sleepLine(player(until = 1_000 + 22 * min + 1), 1_000))
        assertEquals("Stops in 1 min", MusicTimeText.sleepLine(player(until = 1_010), 1_000))
        assertNull(MusicTimeText.sleepLine(player(until = 1_000), 2_000))
        assertNull(MusicTimeText.sleepLine(player(until = null), 0))
        assertEquals("Stops after this song", MusicTimeText.sleepLine(player(until = null, endOfSong = true), 0))
    }

    @Test fun nightCapNoteOnlyWhileActive() {
        assertEquals("Quieter at night: max 6", MusicTimeText.nightCap(NightCap(active = true, maxLevel = 6)))
        assertNull(MusicTimeText.nightCap(NightCap(active = false, maxLevel = 6)))
        assertNull(MusicTimeText.nightCap(null))
    }
}
