/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.house

import com.portalinstaller.data.companion.CompanionClient
import com.portalinstaller.data.companion.CompanionDevice
import com.portalinstaller.data.companion.CompanionRequest
import com.portalinstaller.data.companion.CompanionResponse
import com.portalinstaller.data.companion.CompanionSession
import com.portalinstaller.data.companion.CompanionSessionStore
import com.portalinstaller.data.companion.CompanionTransport
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Polls through the viewed Portal (falling over to another), sends deltas, runs the confirm flow. */
class HouseRepositoryTest {
    private val kitchen = CompanionSession(CompanionDevice("kitchen", "Kitchen", "http://kitchen:8723"), "t1")
    private val den = CompanionSession(CompanionDevice("den", "Den", "http://den:8723"), "t2")

    private fun store(vararg s: CompanionSession, active: String? = null) = object : CompanionSessionStore {
        override suspend fun load() = s.toList()
        override suspend fun save(sessions: List<CompanionSession>) {}
        override suspend fun loadActiveId() = active
    }

    private class Http(val handler: (CompanionRequest) -> CompanionResponse) : CompanionTransport {
        val calls = mutableListOf<CompanionRequest>()
        override suspend fun execute(request: CompanionRequest): CompanionResponse { calls += request; return handler(request) }
    }

    private fun state(rev: Long, phase: String = "live") = CompanionResponse(200, JSONObject().put("ok", true).put("rev", rev).put("full", true)
        .put("minute", 1).put("conn", JSONObject().put("phase", phase)).put("hasPlan", false)
        .put("entities", JSONArray().put(JSONObject().put("id", "lock.front").put("s", "locked").put("n", "Front door"))).toString())

    private fun repo(http: Http, store: CompanionSessionStore, now: Long = 1_000) = HouseRepository(CompanionClient(http), store, { now })

    @Test fun noPairedPortal() = runTest {
        val r = repo(Http { error("no calls") }, store())
        r.poll()
        assertEquals(HouseReach.NO_PORTAL, r.state.value.reach)
        assertEquals(HouseStatus.NO_PORTAL, HouseStatusRules.status(r.state.value.snapshot, r.state.value.reach, 0, 0))
    }

    @Test fun viewedPortalFirstThenFailover() = runTest {
        val http = Http { req -> if (req.url.startsWith("http://den")) throw IOException("down") else state(3) }
        val r = repo(http, store(kitchen, den, active = "den"))
        r.poll()
        assertTrue(http.calls.first().url.startsWith("http://den"))
        assertEquals("Kitchen", r.state.value.via)
        assertTrue(r.state.value.failover)
        assertEquals("locked", r.state.value.snapshot?.entity("lock.front")?.state)
    }

    @Test fun secondPollSendsTheRevItHolds() = runTest {
        val http = Http { state(7) }
        val r = repo(http, store(kitchen))
        r.poll(); r.poll()
        assertEquals(0L, JSONObject(http.calls[0].body!!).getLong("since"))
        assertEquals(7L, JSONObject(http.calls[1].body!!).getLong("since"))
        assertTrue("every poll offers section versions", JSONObject(http.calls[0].body!!).has("sv"))
    }

    @Test fun olderHearthpaneAsksForAnUpdate() = runTest {
        val r = repo(Http { CompanionResponse(404, """{"ok":false,"error":"not_found"}""") }, store(kitchen))
        r.poll()
        assertEquals(HouseStatus.NEEDS_UPDATE, HouseStatusRules.status(null, r.state.value.reach, 0, 0))
    }

    @Test fun everyPortalDownKeepsLastKnownValues() = runTest {
        var up = true
        val r = repo(Http { if (up) state(1) else throw IOException("gone") }, store(kitchen))
        r.poll(); up = false; r.poll()
        assertEquals(HouseReach.OFFLINE, r.state.value.reach)
        assertEquals(HouseStatus.PORTAL_OFFLINE, HouseStatusRules.status(r.state.value.snapshot, r.state.value.reach, 1_000, 2_000))
        assertTrue(r.state.value.snapshot?.entities?.isNotEmpty() == true)
    }

    @Test fun askingActionRoundTripsTheTicket() = runTest {
        val ticket = "cd".repeat(16)
        val http = Http { req ->
            when {
                req.url.endsWith("/remote/controlcenter") -> state(1)
                JSONObject(req.body!!).has("ticket") -> CompanionResponse(200, """{"ok":true,"done":true}""")
                else -> CompanionResponse(409, JSONObject().put("ok", false).put("error", "confirm").put("ask", "confirm")
                    .put("ticket", ticket).put("title", "Unlock Front door?").put("button", "Unlock").toString())
            }
        }
        val r = repo(http, store(kitchen))
        r.poll()
        val lock = r.state.value.snapshot!!.entity("lock.front")!!
        val first = r.act(HouseAct.unlock(lock)) as HouseActResult.Confirm
        assertEquals("Unlock Front door?", first.confirm.title)
        val done = r.act(first.act, first.confirm.ticket)
        assertTrue(done is HouseActResult.Done)
        assertEquals(ticket, JSONObject(http.calls.last().body!!).getString("ticket"))
        assertEquals("unlocking", r.state.value.snapshot?.entity("lock.front")?.state)
        assertFalse("lock.front" in r.state.value.pending)
    }

    @Test fun refusalsAreHouseholdWords() = runTest {
        val r = repo(Http { req -> if (req.url.endsWith("/act")) CompanionResponse(503,
            """{"ok":false,"error":"ha_unavailable","reason":"Home Assistant isn't answering"}""") else state(1) }, store(kitchen))
        r.poll()
        val res = r.act(HouseAct("lock", "lock.front", "Locked")) as HouseActResult.Failed
        assertEquals("Home Assistant isn't answering", res.text)
    }

    @Test fun cameraBytesComeThroughThePortal() = runTest {
        val jpeg = byteArrayOf(-1, -40, 1, 2)
        val http = Http { req -> if (req.url.endsWith("/camera")) CompanionResponse(200, "", jpeg) else state(1) }
        val r = repo(http, store(kitchen))
        r.poll()
        assertTrue(r.camera("camera.door", 640)!!.contentEquals(jpeg))
        assertEquals("camera.door", JSONObject(http.calls.last().body!!).getString("entity"))
    }
}
