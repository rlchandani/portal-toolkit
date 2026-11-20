/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.companion

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pairing against new and old Portals (Hearthpane audit findings 2-4): the optional label, a
 * pairing that expired or was removed (401 -> "Pair again"), the PIN rate limit (429), and the
 * household-key share refused for a session that didn't just pair.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompanionPairingSecurityTest {
    private class Store(initial: List<CompanionSession> = emptyList()) : CompanionSessionStore {
        var sessions = initial
        override suspend fun load() = sessions
        override suspend fun save(sessions: List<CompanionSession>) { this.sessions = sessions }
    }

    private class Http(var reply: (CompanionRequest) -> CompanionResponse) : CompanionTransport {
        val calls = mutableListOf<CompanionRequest>()
        override suspend fun execute(request: CompanionRequest): CompanionResponse { calls += request; return reply(request) }
    }

    private fun route(r: CompanionRequest) = r.url.substringAfter("/remote/")

    private val healthy: (CompanionRequest) -> CompanionResponse = { r ->
        when (route(r)) {
            "pair" -> CompanionResponse(200, """{"ok":true,"token":"t1","name":"Kitchen"}""")
            "companion" -> CompanionResponse(200, """{"ok":true,"deviceId":"d1","name":"Kitchen","capabilities":{}}""")
            "nowplaying" -> CompanionResponse(200, """{"ok":true,"np":{}}""")
            else -> CompanionResponse(404, """{"ok":false,"error":"not_found"}""")
        }
    }

    @Test fun pairingSendsTheLabelWhichOlderPortalsSimplyIgnore() = runTest {
        val http = Http(healthy)
        val repo = CompanionRepository(CompanionClient(http), Store(), backgroundScope, UnconfinedTestDispatcher(testScheduler),
            preloadManagement = false, pairLabel = "Pixel 8\u0000")
        repo.pair("portal.local", "123456").join()
        val body = JSONObject(http.calls.first { route(it) == "pair" }.body!!)
        assertEquals("123456", body.getString("pin"))
        assertEquals("Pixel 8", body.getString("label"))
        assertEquals(CompanionConnection.CONNECTED, repo.state.value.connection)
    }

    @Test fun noLabelSendsThePinOnlyBodyAsBefore() = runTest {
        val http = Http(healthy)
        val repo = CompanionRepository(CompanionClient(http), Store(), backgroundScope, UnconfinedTestDispatcher(testScheduler),
            preloadManagement = false)
        repo.pair("portal.local", "123456").join()
        assertFalse(JSONObject(http.calls.first { route(it) == "pair" }.body!!).has("label"))
    }

    @Test fun tooManyTriesShowsTheWait() = runTest {
        val http = Http { CompanionResponse(429, """{"ok":false,"error":"pair_rate_limited","retryAfterSec":30,"scope":"portal"}""") }
        val repo = CompanionRepository(CompanionClient(http), Store(), backgroundScope, UnconfinedTestDispatcher(testScheduler),
            preloadManagement = false)
        repo.pair("portal.local", "123456").join()
        assertEquals("pair_rate_limited", repo.state.value.error?.code)
        assertEquals("Too many tries. Wait 30 s, then try again.", repo.state.value.error?.message)
        assertFalse(repo.state.value.busy)
    }

    @Test fun expiredOrRemovedPairingAsksToPairAgain() = runTest {
        val http = Http { CompanionResponse(401, """{"ok":false,"error":"unauthorized"}""") }
        val saved = CompanionSession(CompanionDevice("d1", "Kitchen", "http://portal.local:8723"), "old-token")
        val repo = CompanionRepository(CompanionClient(http), Store(listOf(saved)), backgroundScope, UnconfinedTestDispatcher(testScheduler),
            preloadManagement = false)
        repo.ready.join()
        repo.selectDevice("d1").join()
        assertEquals(CompanionConnection.UNAUTHORIZED, repo.state.value.connection)
        assertTrue(repo.state.value.error!!.message.contains("Pair again"))
    }

    @Test fun householdShareRefusedForAnOlderSessionIsNotAnAuthFailure() = runTest {
        // A phone that didn't just pair gets 403 pair_again: provisioning backs off quietly.
        val client = CompanionClient(Http { r ->
            when (r.method) {
                "GET" -> CompanionResponse(200, """{"ok":true,"configured":true,"fingerprint":"abcd1234"}""")
                else -> CompanionResponse(403, """{"ok":false,"error":"pair_again"}""")
            }
        })
        val session = CompanionSession(CompanionDevice("d1", "Kitchen", "http://portal.local:8723"), "t")
        val error = runCatching { CompanionHousehold.provision(client, session, object : HouseholdKeySource {
            override fun key() = ByteArray(32)
            override fun peek(): ByteArray? = null
            override fun confirmed() = false
        }) }.exceptionOrNull() as CompanionException
        assertEquals(403, error.error.status)
        assertEquals("pair_again", error.error.code)
    }
}
