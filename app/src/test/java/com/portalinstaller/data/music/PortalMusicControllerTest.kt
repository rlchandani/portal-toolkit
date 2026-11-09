/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.music

import com.portalinstaller.data.companion.CompanionClient
import com.portalinstaller.data.companion.CompanionDevice
import com.portalinstaller.data.companion.CompanionRequest
import com.portalinstaller.data.companion.CompanionResponse
import com.portalinstaller.data.companion.CompanionSession
import com.portalinstaller.data.companion.CompanionTransport
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalMusicControllerTest {
    private val kitchen = CompanionSession(CompanionDevice("k-id", "Kitchen", "http://kitchen:8723"), "t1")
    private val den = CompanionSession(CompanionDevice("d-id", "Den", "http://den:8723"), "t2")

    private class Http(val handler: (CompanionRequest) -> CompanionResponse) : CompanionTransport {
        val calls = mutableListOf<CompanionRequest>()
        override suspend fun execute(request: CompanionRequest): CompanionResponse { calls += request; return handler(request) }
        fun posts(route: String) = calls.filter { it.method == "POST" && it.url.endsWith("/remote/$route") }
    }

    private fun player(quiet: Boolean = false, level: Int = 5) = CompanionResponse(200, JSONObject().put("ok", true)
        .put("player", JSONObject().put("state", "paused").put("onScreen", false)
            .put("current", JSONObject().put("entryId", 1).put("videoId", "vid00000001").put("title", "Song 1"))
            .put("upNext", JSONArray()).put("quiet", quiet).put("quietUntil", if (quiet) "7:00 AM" else JSONObject.NULL)
            .put("volume", JSONObject().put("level", level).put("max", 15).put("muted", false).put("canSet", true))).toString())

    private fun ok(status: String = "done") = CompanionResponse(200, JSONObject().put("ok", true).put("status", status).toString())
    private fun notFound() = CompanionResponse(404, JSONObject().put("ok", false).put("error", "not_found").toString())

    private fun controller(http: Http, vararg rooms: CompanionSession) =
        PortalMusicController(PortalMusicClient(CompanionClient(http)) { 0L }, { rooms.toList() }, settleMs = 0)

    @Test fun loadsRoomsAndTheirPlayers() = runTest {
        val http = Http { player() }
        val c = controller(http, kitchen, den)
        c.load(lastUsed = null)
        val s = c.state.value
        assertEquals(listOf("k-id", "d-id", "all"), s.options?.map { it.key })
        assertEquals("k-id", s.selected)
        assertTrue(s.rooms.values.all { it is RoomMusic.Ready })
    }

    @Test fun olderPortalAsksForAnUpdateButKeepsVolume() = runTest {
        val http = Http { r ->
            if (r.url.endsWith("/remote/ytmusic")) notFound()
            else CompanionResponse(200, JSONObject().put("ok", true).put("np", JSONObject()
                .put("volume", JSONObject().put("level", 4).put("max", 15).put("muted", false).put("canSet", true))).toString())
        }
        val c = controller(http, kitchen)
        c.load(null)
        val room = c.state.value.rooms["k-id"] as RoomMusic.Legacy
        assertEquals(4, room.volume?.level)
        assertEquals("Update Hearthpane on Kitchen to control music from your phone.", MusicCopy.update("Kitchen"))
    }

    @Test fun offlinePortalIsReportedOffline() = runTest {
        val c = controller(Http { throw IOException("down") }, kitchen)
        c.load(null)
        assertEquals(RoomMusic.Offline, c.state.value.rooms["k-id"])
    }

    @Test fun playInQuietHoursAsksFirstThenSendsPlayAnyway() = runTest {
        val http = Http { r -> if (r.method == "POST") ok() else player(quiet = true) }
        val c = controller(http, kitchen)
        c.load(null)
        c.command(MusicCommand.Play, listOf("k-id"))
        assertTrue(http.posts("ytmusic").isEmpty())
        val ask = c.state.value.ask!!
        assertEquals("It’s quiet hours in Kitchen until 7:00 AM. Play anyway, quietly?", ask.message)
        c.answerQuiet(yes = true)
        assertNull(c.state.value.ask)
        val body = JSONObject(http.posts("ytmusic").single().body!!)
        assertEquals("play", body.getString("cmd"))
        assertTrue(body.getBoolean("playAnyway"))
    }

    @Test fun declineInQuietHoursPlaysOnlyInTheOtherRooms() = runTest {
        val http = Http { r -> if (r.method == "POST") ok() else if (r.url.startsWith("http://kitchen")) player(quiet = true) else player() }
        val c = controller(http, kitchen, den)
        c.load(null)
        c.command(MusicCommand.Play, listOf("k-id", "d-id"))
        c.answerQuiet(yes = false)
        val post = http.posts("ytmusic").single()
        assertTrue(post.url.startsWith("http://den"))
    }

    @Test fun dismissingTheQuietQuestionSendsNothing() = runTest {
        val http = Http { r -> if (r.method == "POST") ok() else player(quiet = true) }
        val c = controller(http, kitchen)
        c.load(null)
        c.command(MusicCommand.Play, listOf("k-id"))
        c.dismissQuiet()
        assertNull(c.state.value.ask)
        assertTrue(http.calls.none { it.method == "POST" })
    }

    @Test fun serverQuietAnswerAlsoAsks() = runTest {
        val http = Http { r -> if (r.method == "POST") ok("quiet") else player(quiet = false) }
        val c = controller(http, kitchen)
        c.load(null)
        c.command(MusicCommand.Jump(1), listOf("k-id"))
        assertNotNull(c.state.value.ask)
    }

    @Test fun pauseIsNeverGatedByQuietHours() = runTest {
        val http = Http { r -> if (r.method == "POST") ok() else player(quiet = true) }
        val c = controller(http, kitchen)
        c.load(null)
        c.command(MusicCommand.Pause, listOf("k-id"))
        assertNull(c.state.value.ask)
        assertEquals("pause", JSONObject(http.posts("ytmusic").single().body!!).getString("cmd"))
    }

    @Test fun openingAndFailuresShowANotice() = runTest {
        val http = Http { r ->
            if (r.method != "POST") player()
            else CompanionResponse(409, JSONObject().put("ok", false).put("error", "nothing_queued").toString())
        }
        val c = controller(http, kitchen)
        c.load(null)
        c.command(MusicCommand.Play, listOf("k-id"))
        assertEquals(MusicCopy.nothingQueued("Kitchen"), c.state.value.notice?.text)
        assertTrue(c.state.value.pending.isEmpty())
    }

    @Test fun volumeUpInQuietHoursAsksButDownDoesNot() = runTest {
        val http = Http { r -> if (r.method == "POST") CompanionResponse(200, """{"ok":true,"dir":"x"}""") else player(quiet = true) }
        val c = controller(http, kitchen)
        c.load(null)
        c.volume("k-id", VolumeChange.Down)
        assertEquals("down", JSONObject(http.posts("volume").single().body!!).getString("dir"))
        c.volume("k-id", VolumeChange.Set(9))
        assertEquals("It’s quiet hours in Kitchen until 7:00 AM. Turn it up anyway?", c.state.value.ask?.message)
        assertEquals(1, http.posts("volume").size)
        c.answerQuiet(yes = true)
        assertEquals(9, JSONObject(http.posts("volume").last().body!!).getInt("level"))
    }

    @Test fun volumeWorksWithNothingPlaying() = runTest {
        val idle = CompanionResponse(200, JSONObject().put("ok", true).put("player", JSONObject().put("state", "idle")
            .put("current", JSONObject.NULL).put("volume", JSONObject().put("level", 2).put("max", 15).put("muted", false).put("canSet", true))).toString())
        val http = Http { r -> if (r.method == "POST") CompanionResponse(200, """{"ok":true}""") else idle }
        val c = controller(http, kitchen)
        c.load(null)
        c.volume("k-id", VolumeChange.Up)
        assertEquals(1, http.posts("volume").size)
        assertNull(c.state.value.notice)
    }

    @Test fun fixedVolumeExplainsWhy() = runTest {
        val http = Http { r ->
            if (r.method == "POST") CompanionResponse(409, """{"ok":false,"error":"volume_fixed"}""") else player()
        }
        val c = controller(http, kitchen)
        c.load(null)
        c.volume("k-id", VolumeChange.Down)
        assertEquals(MusicCopy.volumeFixed("Kitchen"), c.state.value.notice?.text)
    }

    @Test fun selectingARoomIsRememberedAndPollReadsOnlyIt() = runTest {
        val http = Http { player() }
        val remembered = mutableListOf<String>()
        val c = PortalMusicController(PortalMusicClient(CompanionClient(http)) { 0L }, { listOf(kitchen, den) },
            rememberRoom = { remembered += it }, settleMs = 0)
        c.load("d-id")
        assertEquals("d-id", c.state.value.selected)
        c.select("k-id")
        assertEquals(listOf("k-id"), remembered)
        http.calls.clear()
        c.poll()
        assertTrue(http.calls.all { it.url.startsWith("http://kitchen") })
        c.select("nope")
        assertEquals("k-id", c.state.value.selected)
    }

    @Test fun playingASearchResultInQuietHoursAsksFirst() = runTest {
        val http = Http { r -> if (r.method == "POST") ok() else player(quiet = true) }
        val c = controller(http, kitchen)
        c.load(null)
        val song = SongRef("vid00000009", "Hello", "Adele", null)
        c.command(MusicCommand.PlayTrack(song), listOf("k-id"))
        assertTrue(http.posts("ytmusic").isEmpty())
        c.answerQuiet(yes = true)
        val body = JSONObject(http.posts("ytmusic").single().body!!)
        assertEquals("playtrack", body.getString("cmd"))
        assertEquals("vid00000009", body.getString("videoId"))
        assertTrue(body.getBoolean("playAnyway"))
    }

    @Test fun queueingASongIsConfirmedAndNeverAsks() = runTest {
        val http = Http { r -> if (r.method == "POST") ok() else player(quiet = true) }
        val c = controller(http, kitchen)
        c.load(null)
        c.command(MusicCommand.QueueTrack(SongRef("vid00000009", "Hello", "Adele", null), next = true), listOf("k-id"))
        assertNull(c.state.value.ask)
        assertEquals(MusicCopy.queued("Kitchen", next = true), c.state.value.notice?.text)
    }

    @Test fun sleepTimerAndNewCommandsOnAnOlderPortalAskForAnUpdate() = runTest {
        val http = Http { r -> if (r.method == "POST") notFound() else player() }
        val c = controller(http, kitchen)
        c.load(null)
        c.command(MusicCommand.Sleep(SleepChoice.MIN_30), listOf("k-id"))
        assertEquals(30, JSONObject(http.posts("ytmusic").single().body!!).getInt("minutes"))
        assertEquals(MusicCopy.update("Kitchen"), c.state.value.notice?.text)
    }

    @Test fun requestsNeverCarryTheTokenInTheUrl() = runTest {
        val http = Http { player() }
        controller(http, kitchen).load(null)
        assertTrue(http.calls.none { it.url.contains("t1") })
    }
}
