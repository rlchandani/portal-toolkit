/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.dinner

import com.portalinstaller.data.companion.CompanionClient
import com.portalinstaller.data.companion.CompanionDevice
import com.portalinstaller.data.companion.CompanionRequest
import com.portalinstaller.data.companion.CompanionResponse
import com.portalinstaller.data.companion.CompanionSession
import com.portalinstaller.data.companion.CompanionSessionStore
import com.portalinstaller.data.companion.CompanionTransport
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Rooms come from the paired Portals; a send goes to one Portal, which relays to the others. */
class DinnerRepositoryTest {
    private val fa = CompanionSession(CompanionDevice("legacy:fa", "Family Area", "http://fa:8723"), "t1")
    private val la = CompanionSession(CompanionDevice("la-id", "Living Area", "http://la:8723"), "t2")
    private val store = object : CompanionSessionStore {
        var sessions = listOf(fa, la)
        override suspend fun load() = sessions
        override suspend fun save(sessions: List<CompanionSession>) {}
    }

    private class Http(val handler: (CompanionRequest) -> CompanionResponse) : CompanionTransport {
        val calls = mutableListOf<CompanionRequest>()
        override suspend fun execute(request: CompanionRequest): CompanionResponse { calls += request; return handler(request) }
    }

    private fun snapshot(id: String, name: String, quiet: Boolean = false, sends: JSONArray = JSONArray()) = CompanionResponse(200,
        JSONObject().put("ok", true).put("ready", true).put("self", JSONObject().put("id", id).put("name", name).put("quiet", quiet).put("receive", true))
            .put("rooms", JSONArray()).put("sends", sends).toString())

    private fun sendJson(pending: Boolean, reply: Any? = JSONObject.NULL) = JSONObject().put("id", "a1b2c3d4").put("text", "Dinner’s ready")
        .put("pending", pending).put("rows", JSONArray()
            .put(JSONObject().put("room", "fa-id").put("name", "Family Area").put("status", "spoken").put("text", "").put("retry", false).put("reply", JSONObject.NULL))
            .put(JSONObject().put("room", "la-id").put("name", "Living Area").put("status", if (pending) "sending" else "spoken").put("text", "").put("retry", false).put("reply", reply)))

    @Test fun loadsRoomsWithTheirTrueIdsAndQuietState() = runTest {
        val http = Http { r -> if (r.url.startsWith("http://fa")) snapshot("fa-id", "Family Area") else snapshot("la-id", "Living Area", quiet = true) }
        val repo = DinnerRepository(CompanionClient(http), store, "Rohit’s Pixel")
        repo.refresh()
        val rooms = repo.state.value.rooms
        assertEquals(listOf("fa-id", "la-id"), rooms.map { it.id })
        assertTrue(rooms.all { it.online })
        assertTrue(rooms[1].quiet)
        assertEquals("Living Area is in quiet hours, so no sound there.", repo.subtitle())
    }

    @Test fun offlinePortalStaysListedButCannotRelay() = runTest {
        val http = Http { r ->
            if (r.url.startsWith("http://fa")) throw IOException("unreachable")
            if (r.method == "POST") CompanionResponse(200, JSONObject().put("ok", true).put("send", sendJson(pending = true)).toString())
            else snapshot("la-id", "Living Area")
        }
        val repo = DinnerRepository(CompanionClient(http), store, "Rohit’s Pixel")
        repo.refresh()
        assertFalse(repo.state.value.rooms.first { it.name == "Family Area" }.online)
        repo.send(PhoneMessage.Preset("dinner"))
        val post = http.calls.last { it.method == "POST" }
        assertTrue("relay is the reachable Portal", post.url.startsWith("http://la"))
        val body = JSONObject(post.body!!)
        assertEquals("Rohit’s Pixel", body.getString("sender"))
        assertEquals(2, body.getJSONArray("rooms").length())
        assertNotNull(repo.state.value.result)
    }

    @Test fun pollPicksUpResultsAndRepliesThenTheBannerClears() = runTest {
        var reply: Any? = JSONObject.NULL
        val http = Http { r ->
            if (r.method == "POST") CompanionResponse(200, JSONObject().put("ok", true).put("send", sendJson(pending = true)).toString())
            else snapshot(if (r.url.startsWith("http://fa")) "fa-id" else "la-id", "x", sends = JSONArray().put(sendJson(false, reply)))
        }
        val repo = DinnerRepository(CompanionClient(http), store, "P")
        repo.refresh()
        repo.send(PhoneMessage.Preset("dinner"))
        assertTrue(repo.state.value.result!!.pending)
        repo.poll()
        assertFalse(repo.state.value.result!!.pending)
        assertNull(repo.state.value.replyBanner)
        reply = "On my way"
        repo.poll()
        assertEquals("On my way", repo.state.value.replyBanner!!.reply)
        repo.dismissBanner()
        assertNull(repo.state.value.replyBanner)
    }

    @Test fun noReachablePortalMeansNotSentAndABanner() = runTest {
        val http = Http { throw IOException("away") }
        val repo = DinnerRepository(CompanionClient(http), store, "P")
        repo.refresh()
        repo.send(PhoneMessage.Ping)
        val state = repo.state.value
        assertTrue(state.notOnHomeWifi)
        assertTrue(state.result!!.rows.all { it.status == DinnerRowStatus.NOT_SENT })
        assertEquals("Not delivered", DinnerCopy.title(state.result!!))
    }

    @Test fun targetsAndRetry() = runTest {
        val http = Http { r ->
            if (r.method == "POST") CompanionResponse(200, JSONObject().put("ok", true).put("send", sendJson(pending = false)).toString())
            else snapshot(if (r.url.startsWith("http://fa")) "fa-id" else "la-id", "x")
        }
        val repo = DinnerRepository(CompanionClient(http), store, "P")
        repo.refresh()
        repo.selectTarget("la-id")
        repo.send(PhoneMessage.Preset("bed"))
        val first = JSONObject(http.calls.last { it.method == "POST" }.body!!)
        assertEquals(listOf("la-id"), (0 until first.getJSONArray("rooms").length()).map { first.getJSONArray("rooms").getString(it) })
        assertTrue("prefer a Portal that is itself a target", http.calls.last { it.method == "POST" }.url.startsWith("http://la"))
        repo.retry("la-id")
        val retry = JSONObject(http.calls.last { it.method == "POST" }.body!!)
        assertEquals("a1b2c3d4", retry.getJSONObject("retry").getString("id"))
    }
}
