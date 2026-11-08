/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.music

import com.portalinstaller.data.companion.PlaybackVolume
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalMusicModelsTest {
    private fun entry(id: Long, title: String) = JSONObject().put("entryId", id).put("videoId", "vid0000000$id")
        .put("title", title).put("artist", "Artist").put("thumb", "https://i.ytimg.com/vi/x/hq.jpg")
        .put("durationMs", 200_000).put("unplayable", JSONObject.NULL)

    private fun playing() = JSONObject().put("ok", true).put("player", JSONObject()
        .put("state", "playing").put("onScreen", true).put("ad", false)
        .put("current", entry(2, "Song 2")).put("positionMs", 10_000).put("durationMs", 200_000)
        .put("upNext", JSONArray().put(entry(3, "Song 3"))).put("upNextTotal", 7)
        .put("label", "Playlist · Road trip").put("halt", JSONObject.NULL)
        .put("signedIn", true).put("account", "Rohit").put("quiet", false).put("quietUntil", JSONObject.NULL)
        .put("volume", JSONObject().put("level", 7).put("max", 15).put("muted", false).put("canSet", true)))

    @Test fun parsesAPlayingPortal() {
        val p = PortalMusicParser.player(playing(), receivedAtMs = 1_000)
        assertEquals(PlayerState.PLAYING, p.state)
        assertEquals("Song 2", p.current?.title)
        assertEquals(2L, p.current?.entryId)
        assertEquals(listOf("Song 3"), p.upNext.map { it.title })
        assertEquals(7, p.upNextTotal)
        assertEquals("Rohit", p.account)
        assertEquals(PlaybackVolume(7, 15, muted = false, canSet = true), p.volume)
        assertTrue(p.onScreen)
    }

    @Test fun positionTicksOnlyWhilePlayingAndStopsAtTheEnd() {
        val p = PortalMusicParser.player(playing(), receivedAtMs = 1_000)
        assertEquals(13_000, p.livePositionMs(4_000))
        assertEquals(200_000, p.livePositionMs(10_000_000))
        val paused = p.copy(state = PlayerState.PAUSED)
        assertEquals(10_000, paused.livePositionMs(60_000))
        assertEquals(10_000, p.copy(ad = true).livePositionMs(60_000))
    }

    @Test fun idlePortalIsNothingPlayingButKeepsVolume() {
        val json = JSONObject().put("ok", true).put("player", JSONObject().put("state", "idle")
            .put("current", JSONObject.NULL).put("upNext", JSONArray())
            .put("volume", JSONObject().put("level", 3).put("max", 15).put("muted", true).put("canSet", true)))
        val p = PortalMusicParser.player(json, 0)
        assertEquals(PlayerState.IDLE, p.state)
        assertNull(p.current)
        assertTrue(p.volume!!.muted)
    }

    @Test fun refusesNonHttpsArtAndBadEntries() {
        val json = playing()
        json.getJSONObject("player").getJSONObject("current").put("thumb", "http://evil/x.jpg")
        json.getJSONObject("player").put("upNext", JSONArray().put(JSONObject().put("entryId", 0).put("title", "x")))
        val p = PortalMusicParser.player(json, 0)
        assertNull(p.current?.thumb)
        assertTrue(p.upNext.isEmpty())
    }

    @Test fun malformedVolumeIsDropped() {
        val json = playing()
        json.getJSONObject("player").put("volume", JSONObject().put("level", 99).put("max", 15))
        assertNull(PortalMusicParser.player(json, 0).volume)
    }

    @Test fun commandAnswersMapToResults() {
        assertEquals(CommandResult.Done, PortalMusicParser.command(JSONObject().put("status", "done"), "Kitchen"))
        assertEquals(CommandResult.Opening, PortalMusicParser.command(JSONObject().put("status", "opening"), "Kitchen"))
        assertEquals(CommandResult.Quiet("7:00 AM"),
            PortalMusicParser.command(JSONObject().put("status", "quiet").put("quietUntil", "7:00 AM"), "Kitchen"))
        assertEquals(CommandResult.Declined("Kitchen is on a call. Nothing was played."),
            PortalMusicParser.command(JSONObject().put("status", "declined").put("reason", "Kitchen is on a call. Nothing was played."), "Kitchen"))
        assertTrue(PortalMusicParser.command(JSONObject(), "Kitchen") is CommandResult.Failed)
    }

    @Test fun commandBodiesMatchThePortalWire() {
        val play = MusicCommand.Play.body(playAnyway = true)
        assertEquals("play", play.getString("cmd"))
        assertTrue(play.getBoolean("playAnyway"))
        assertEquals(0L, MusicCommand.Seek(-5).body().getLong("positionMs"))
        assertEquals(4L, MusicCommand.Jump(4).body().getLong("entryId"))
        assertEquals("playnext", MusicCommand.PlayNext(4).body().getString("cmd"))
        assertEquals("set", VolumeChange.Set(5).body().getString("dir"))
        assertEquals(5, VolumeChange.Set(5).body().getInt("level"))
        assertEquals("mute", VolumeChange.ToggleMute.body().getString("dir"))
    }

    @Test fun onlyLouderChangesRaiseTheVolume() {
        val v = PlaybackVolume(5, 15, muted = false, canSet = true)
        assertTrue(VolumeChange.Up.raises(v))
        assertFalse(VolumeChange.Down.raises(v))
        assertTrue(VolumeChange.Set(6).raises(v))
        assertFalse(VolumeChange.Set(5).raises(v))
        assertFalse(VolumeChange.ToggleMute.raises(v))
        assertTrue("unmuting is louder", VolumeChange.ToggleMute.raises(v.copy(muted = true)))
        assertTrue(VolumeChange.Set(1).raises(v.copy(muted = true)))
    }

    @Test fun expectedVolumeShowsTheChangeRightAway() {
        val v = PlaybackVolume(5, 15, muted = true, canSet = true)
        assertEquals(PlaybackVolume(9, 15, muted = false, canSet = true), VolumeChange.Set(9).expected(v))
        assertEquals(15, VolumeChange.Set(99).expected(v)?.level)
        assertEquals(false, VolumeChange.ToggleMute.expected(v)?.muted)
        assertEquals(v, VolumeChange.Up.expected(v))
    }

    @Test fun legacyVolumeComesFromNowPlaying() {
        val json = JSONObject().put("ok", true).put("np", JSONObject().put("active", false)
            .put("volume", JSONObject().put("level", 4).put("max", 15).put("muted", false).put("canSet", true)))
        assertEquals(4, PortalMusicParser.nowPlayingVolume(json)?.level)
    }
}
