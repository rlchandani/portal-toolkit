/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.guests

import com.portalinstaller.data.companion.CompanionClient
import com.portalinstaller.data.companion.CompanionDevice
import com.portalinstaller.data.companion.CompanionRequest
import com.portalinstaller.data.companion.CompanionResponse
import com.portalinstaller.data.companion.CompanionSession
import com.portalinstaller.data.companion.CompanionSessionStore
import com.portalinstaller.data.companion.CompanionTransport
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guest card on every paired Portal, or one; the password only ever leaves in a save body. */
class GuestRepositoryTest {
    private val kitchen = CompanionSession(CompanionDevice("k-id", "Kitchen", "http://k:8723"), "t1")
    private val hall = CompanionSession(CompanionDevice("h-id", "Hall", "http://h:8723"), "t2")
    private val store = object : CompanionSessionStore {
        override suspend fun load() = listOf(kitchen, hall)
        override suspend fun save(sessions: List<CompanionSession>) {}
    }
    private val logs = mutableListOf<String>()

    private class Http(val handler: (CompanionRequest) -> CompanionResponse) : CompanionTransport {
        val calls = mutableListOf<CompanionRequest>()
        override suspend fun execute(request: CompanionRequest): CompanionResponse { calls += request; return handler(request) }
    }

    private fun snapshot(ssid: String, active: Boolean = false, version: Int? = 2) = CompanionResponse(200, JSONObject().put("ok", true)
        .apply { version?.let { put("version", it) } }
        .put("card", JSONObject().put("ssid", ssid).put("security", "wpa"))
        .put("hasPassword", true).put("ready", true)
        .put("mode", JSONObject().put("active", active).put("until", JSONObject.NULL)).toString())

    private fun repo(http: Http) = GuestRepository(CompanionClient(http), store) { logs += it }

    @Test fun readsEveryPortalAndShowsTheFirstCardForAll() = runTest {
        val http = Http { r -> if (r.url.startsWith("http://k")) snapshot("Kitchen Net") else snapshot("Hall Net") }
        val repo = repo(http)
        repo.refresh()
        assertEquals(listOf("Kitchen", "Hall"), repo.state.value.rooms.map { it.name })
        assertTrue(http.calls.all { it.method == "GET" && it.url.endsWith("/remote/guests") })
        assertEquals("Kitchen Net", repo.state.value.shown?.card?.ssid)
        repo.selectTarget("h-id")
        assertEquals("Hall Net", repo.state.value.shown?.card?.ssid)
    }

    @Test fun startingForAllReachesEachPortalAndReportsTheOffline() = runTest {
        val http = Http { r ->
            if (r.url.startsWith("http://h") && r.method == "POST") throw IOException("unreachable")
            if (r.method == "POST") snapshot("Net", active = true) else snapshot("Net")
        }
        val repo = repo(http)
        repo.refresh()
        repo.start(GuestStart.ForDays(2))
        val posts = http.calls.filter { it.method == "POST" }
        assertEquals(2, posts.size)
        assertEquals(2, JSONObject(posts.first().body!!).getInt("days"))
        assertTrue(repo.state.value.rooms.first { it.name == "Kitchen" }.snapshot!!.active)
        assertEquals("Guest mode started on Kitchen. Couldn’t reach Hall.", repo.state.value.message)
        assertFalse(repo.state.value.busy)
    }

    @Test fun oneChosenPortalOnly() = runTest {
        val http = Http { r -> if (r.method == "POST") snapshot("Net") else snapshot("Net", active = true) }
        val repo = repo(http)
        repo.refresh()
        repo.selectTarget("k-id")
        repo.end()
        assertEquals(listOf("http://k:8723/remote/guests"), http.calls.filter { it.method == "POST" }.map { it.url })
        assertEquals("Guest mode ended on Kitchen.", repo.state.value.message)
    }

    @Test fun thePasswordIsSentOnceAndNeverLogged() = runTest {
        val http = Http { r ->
            if (r.method == "POST") CompanionResponse(503, JSONObject().put("ok", false).put("error", "secret_storage_unavailable").toString())
            else snapshot("Net")
        }
        val repo = repo(http)
        repo.refresh()
        repo.selectTarget("k-id")
        repo.save(GuestCard(ssid = "Net"), password = "hunter2-secret", doorCode = DoorCodeChange.Set("4821"))
        assertEquals("hunter2-secret", JSONObject(http.calls.last().body!!).getString("password"))
        assertEquals("4821", JSONObject(http.calls.last().body!!).getString("doorCode"))
        assertTrue(logs.none { it.contains("hunter2") || it.contains("4821") })
        assertFalse(repo.state.value.toString().contains("hunter2"))
        assertEquals("Couldn’t reach Kitchen.", repo.state.value.message)
    }

    @Test fun aPortalWithoutTheRouteIsMarkedOutdated() = runTest {
        val http = Http { r ->
            if (r.url.startsWith("http://h")) CompanionResponse(404, JSONObject().put("ok", false).put("error", "not_found").toString())
            else snapshot("Net")
        }
        val repo = repo(http)
        repo.refresh()
        val hallRoom = repo.state.value.rooms.first { it.name == "Hall" }
        assertTrue(hallRoom.outdated)
        assertNull(hallRoom.snapshot)
    }

    @Test fun rejectedValuesAreReportedAsSuch() = runTest {
        val http = Http { r ->
            if (r.method == "POST") CompanionResponse(400, JSONObject().put("ok", false).put("error", "invalid_ssid").toString())
            else snapshot("Net")
        }
        val repo = repo(http)
        repo.refresh()
        repo.selectTarget("h-id")
        repo.save(GuestCard(ssid = "Net"), password = "", doorCode = DoorCodeChange.Keep)
        assertEquals("Hall didn’t accept the change. Update Calder there and try again.", repo.state.value.message)
        assertFalse(JSONObject(http.calls.last().body!!).has("password"))
    }

    @Test fun anOldPortalStillRunsGuestModeButIsNotSentTheNewCard() = runTest {
        val http = Http { r -> if (r.url.startsWith("http://h")) snapshot("Old", version = null) else snapshot("New") }
        val repo = repo(http)
        repo.refresh()
        assertTrue(repo.state.value.rooms.first { it.name == "Hall" }.needsUpdate)
        assertEquals("New", repo.state.value.shown?.card?.ssid) // the up-to-date Portal's card is edited
        repo.save(GuestCard(ssid = "Net"), password = "", doorCode = DoorCodeChange.Keep)
        val saves = http.calls.filter { it.method == "POST" }
        assertEquals(listOf("http://k:8723/remote/guests"), saves.map { it.url })
        assertEquals("Saved on Kitchen. Update Calder on Hall to use the new guest setup.", repo.state.value.message)
        repo.start(GuestStart.UntilEnded)
        assertEquals(3, http.calls.count { it.method == "POST" })
    }
}
