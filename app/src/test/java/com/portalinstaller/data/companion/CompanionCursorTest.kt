/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.companion

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompanionCursorTest {
    private class Store : CompanionSessionStore {
        override suspend fun load() = listOf("a", "b").map { CompanionSession(CompanionDevice(it, it, "http://$it:8723"), "token-$it") }
        override suspend fun save(sessions: List<CompanionSession>) = Unit
    }
    private fun response(request: CompanionRequest) = CompanionResponse(200, """{"ok":true,"deviceId":"${if (request.url.contains("//b:")) "b" else "a"}","capabilities":{},"settings":{"domains":[]},"sources":{},"apps":[],"np":{}}""")

    @Test fun cursorAggregatesAt40MsWithOneInflightAndNoGlobalBusy() = runTest {
        val calls = mutableListOf<CompanionRequest>()
        val first = CompletableDeferred<CompanionResponse>()
        val http = CompanionTransport { request ->
            calls += request
            if (request.url.endsWith("/cursor") && calls.count { it.url.endsWith("/cursor") } == 1) first.await()
            else response(request)
        }
        val repo = CompanionRepository(CompanionClient(http), Store(), backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repo.ready.join(); repo.selectDevice("a").join()
        repeat(100) { repo.moveCursor(1f, -2f) }
        runCurrent()
        assertFalse(repo.state.value.busy)
        assertEquals(0, calls.count { it.url.endsWith("/cursor") })
        advanceTimeBy(40); runCurrent()
        assertEquals(1, calls.count { it.url.endsWith("/cursor") })
        val body = JSONObject(calls.single { it.url.endsWith("/cursor") }.body!!)
        assertEquals(100.0, body.getDouble("dx"), 0.01)
        assertEquals(-200.0, body.getDouble("dy"), 0.01)
        repeat(100) { repo.moveCursor(2f, 3f) }
        advanceTimeBy(100); runCurrent()
        assertEquals(1, calls.count { it.url.endsWith("/cursor") })
        first.complete(CompanionResponse(200, """{"ok":true}"""))
        runCurrent(); advanceTimeBy(40); runCurrent()
        assertEquals(2, calls.count { it.url.endsWith("/cursor") })
        assertEquals(200.0, JSONObject(calls.last { it.url.endsWith("/cursor") }.body!!).getDouble("dx"), 0.01)
        assertFalse(repo.state.value.busy)
    }

    @Test fun deviceSwitchDropsPendingDeltasAndPollingRefreshDoesNotSetBusy() = runTest {
        val calls = mutableListOf<CompanionRequest>()
        val pending = CompletableDeferred<CompanionResponse>()
        var blockReads = false
        val http = CompanionTransport { request ->
            calls += request
            if (blockReads && request.url.endsWith("/companion")) pending.await() else response(request)
        }
        val repo = CompanionRepository(CompanionClient(http), Store(), backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repo.ready.join(); repo.selectDevice("a").join()
        repo.moveCursor(100f, 100f)
        repo.selectDevice("b").join()
        advanceTimeBy(100); runCurrent()
        assertFalse(calls.any { it.url.endsWith("/cursor") })
        blockReads = true
        val refresh = repo.refresh()
        runCurrent()
        assertFalse(repo.state.value.busy)
        pending.complete(response(CompanionRequest("GET", "http://b:8723/remote/companion")))
        refresh.join()
        assertEquals("b", repo.state.value.activeDeviceId)
    }
}
