/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.ota

import com.portalinstaller.data.companion.CompanionClient
import com.portalinstaller.data.companion.CompanionDevice
import com.portalinstaller.data.companion.CompanionRequest
import com.portalinstaller.data.companion.CompanionResponse
import com.portalinstaller.data.companion.CompanionSession
import com.portalinstaller.data.companion.CompanionTransport
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalUpdatesControllerTest {
    private val living = CompanionSession(CompanionDevice("l-id", "Living", "http://living:8723"), "t1")
    private val den = CompanionSession(CompanionDevice("d-id", "Den", "http://den:8723"), "t2")

    private class Http(val handler: (CompanionRequest) -> CompanionResponse) : CompanionTransport {
        val calls = mutableListOf<CompanionRequest>()
        override suspend fun execute(request: CompanionRequest): CompanionResponse { calls += request; return handler(request) }
    }

    private fun ota(state: String = "available") = CompanionResponse(200, JSONObject().put("ok", true).put("ota", JSONObject()
        .put("state", state).put("installedVersionName", "1.228").put("installedVersionCode", 222)
        .put("available", JSONObject().put("versionName", "1.229").put("versionCode", 223).put("notes", ""))
        .put("lastCheckedAtMs", 5L).put("error", JSONObject.NULL).put("autoInstall", true)).toString())

    private val notFound = CompanionResponse(404, """{"ok":false,"error":"not_found"}""")
    private val accepted = CompanionResponse(200, """{"ok":true,"status":"installing"}""")

    private fun controller(http: Http, vararg rooms: CompanionSession) =
        PortalUpdatesController(PortalOtaClient(CompanionClient(http)), { rooms.toList() }, settleMs = 0)

    @Test fun readsEveryPairedPortal() = runTest {
        val http = Http { ota() }
        val c = controller(http, living, den)
        c.load()
        assertEquals(listOf("Living", "Den"), c.state.value.rooms.map { it.name })
        assertTrue(c.state.value.rooms.all { it.status is RoomOta.Ready })
        assertTrue(http.calls.all { it.method == "GET" && it.url.endsWith("/remote/ota") })
        assertEquals(2, c.state.value.lines().size)
    }

    @Test fun olderPortalWithoutTheRouteIsSilentlyUnsupported() = runTest {
        val c = controller(Http { if (it.url.startsWith("http://den")) notFound else ota() }, living, den)
        c.load()
        assertEquals(RoomOta.Unsupported, c.state.value.rooms.first { it.id == "d-id" }.status)
        assertEquals(listOf("l-id"), c.state.value.lines().map { it.first })
        assertNull(c.state.value.notice)
    }

    @Test fun unreachablePortalIsOfflineAndHidden() = runTest {
        val c = controller(Http { if (it.url.startsWith("http://den")) throw IOException("down") else ota() }, living, den)
        c.load()
        assertEquals(RoomOta.Offline, c.state.value.rooms.first { it.id == "d-id" }.status)
        assertEquals(1, c.state.value.lines().size)
    }

    @Test fun updateNowPostsInstallThenRereads() = runTest {
        var installed = false
        val http = Http { r ->
            if (r.method == "POST") { installed = true; accepted } else ota(if (installed) "installing" else "available")
        }
        val c = controller(http, living)
        c.load()
        c.install("l-id")
        val post = http.calls.single { it.method == "POST" }
        assertTrue(post.url.endsWith("/remote/ota"))
        assertEquals("install", JSONObject(post.body!!).getString("action"))
        val status = c.state.value.rooms.single().status as RoomOta.Ready
        assertEquals(PortalOtaPhase.INSTALLING, status.ota.phase)
        assertTrue(c.state.value.pending.isEmpty())
    }

    @Test fun refusedInstallIsExplained() = runTest {
        val http = Http { r ->
            if (r.method == "POST") CompanionResponse(409, """{"ok":false,"error":"busy"}""") else ota()
        }
        val c = controller(http, living)
        c.load()
        c.install("l-id")
        assertEquals(PortalOtaCopy.installFailed("Living"), c.state.value.notice?.text)
    }

    @Test fun installOnAnOlderPortalSaysItNeedsUpdatingByHand() = runTest {
        val http = Http { r -> if (r.method == "POST") notFound else ota() }
        val c = controller(http, living)
        c.load()
        c.install("l-id")
        assertEquals(PortalOtaCopy.unsupported("Living"), c.state.value.notice?.text)
    }

    @Test fun unknownRoomIsIgnored() = runTest {
        val http = Http { ota() }
        val c = controller(http, living)
        c.load()
        c.install("nope")
        assertTrue(http.calls.none { it.method == "POST" })
    }
}
