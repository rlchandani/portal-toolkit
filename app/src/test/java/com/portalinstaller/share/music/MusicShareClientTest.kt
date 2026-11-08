/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.share.music

import com.portalinstaller.data.companion.CompanionClient
import com.portalinstaller.data.companion.CompanionDevice
import com.portalinstaller.data.companion.CompanionRequest
import com.portalinstaller.data.companion.CompanionResponse
import com.portalinstaller.data.companion.CompanionSession
import com.portalinstaller.data.companion.CompanionTransport
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `/remote/musicshare` on each Portal: payload, answers, and the 3.5 s "isn't answering" rule. */
class MusicShareClientTest {
    private val la = CompanionSession(CompanionDevice("la", "Living Area", "http://la:8723"), "t")
    private val request = MusicSendRequest("video", "dQw4w9WgXcQ", MusicAction.NEXT, false, "Rohit's phone",
        "Saltwater hours", "Marrow & Vale", "https://i.ytimg.com/vi/x/hq.jpg", false, "s1abc")
    private val calls = mutableListOf<CompanionRequest>()
    private fun client(reply: suspend (CompanionRequest) -> CompanionResponse) = MusicShareClient(
        CompanionClient(CompanionTransport { r -> calls += r; reply(r) }), sessionFor = { if (it == "la") la else null })
    private fun ok(json: JSONObject) = CompanionResponse(200, json.put("ok", true).toString())

    @Test fun send_postsThePayloadToTheRoute() = runTest {
        val result = client { ok(JSONObject().put("status", "queued").put("action", "next").put("before", "Moss")) }.send("la", request)
        assertEquals(SendResult.Played(MusicAction.NEXT, false, null, "Moss", 0, null), result)
        assertEquals("http://la:8723/remote/musicshare", calls.single().url)
        val body = JSONObject(calls.single().body!!)
        assertEquals("video", body.getString("kind"))
        assertEquals("next", body.getString("action"))
        assertEquals("Rohit's phone", body.getString("from"))
        assertEquals("s1abc", body.getString("shareId"))
    }

    @Test fun send_mapsQuietDeclinedOfflineAndOldPortals() = runTest {
        assertEquals(SendResult.Quiet("7:00 AM"), client { ok(JSONObject().put("status", "quiet").put("quietUntil", "7:00 AM")) }.send("la", request))
        assertEquals(SendResult.Declined("busy"), client { ok(JSONObject().put("status", "declined").put("reason", "busy")) }.send("la", request))
        assertEquals(SendResult.Offline, client { throw IOException("no route") }.send("la", request))
        assertEquals(SendResult.Failed("Update Hearthpane on Living Area to play shared music."),
            client { CompanionResponse(404, """{"ok":false,"error":"not_found"}""") }.send("la", request))
        assertEquals(SendResult.Failed(MusicShareClient.UNPAIRED), client { error("unused") }.send("fa", request))
    }

    @Test fun aRoomThatTakesLongerThan3point5s_isNotAnswering() = runTest {
        assertEquals(SendResult.Offline, client { delay(10_000); ok(JSONObject().put("status", "playing")) }.send("la", request))
        assertEquals(RoomState.OFFLINE, client { delay(10_000); ok(JSONObject()) }.status("la").state)
    }

    @Test fun status_parsesTheRoom() = runTest {
        val s = client { ok(JSONObject().put("state", "playing").put("nowPlaying", JSONObject().put("title", "Moss")).put("upNext", 2)) }.status("la")
        assertEquals(RoomStatus(RoomState.PLAYING, "Moss", 2, null), s)
        assertEquals(RoomState.UNSUPPORTED, client { CompanionResponse(404, """{"ok":false,"error":"not_found"}""") }.status("la").state)
        assertEquals(RoomStatus(RoomState.QUIET, null, 0, "7:00 AM"),
            client { ok(JSONObject().put("state", "quiet").put("nowPlaying", JSONObject.NULL).put("quietUntil", "7:00 AM")) }.status("la"))
    }

    @Test fun followUp_matchesTheShareId() = runTest {
        val last = JSONObject().put("shareId", "s1abc").put("count", 24).put("skipped", "The owner doesn’t allow playing it here")
        val c = client { ok(JSONObject().put("state", "playing").put("lastShare", last)) }
        assertEquals(ShareFollowUp(24, "The owner doesn’t allow playing it here"), c.followUp("la", "s1abc"))
        assertNull(c.followUp("la", "other"))
    }
}
