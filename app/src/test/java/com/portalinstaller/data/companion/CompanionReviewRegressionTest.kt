/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.companion

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompanionReviewRegressionTest {
    private class Store : CompanionSessionStore {
        override suspend fun load() = listOf("a", "b").map { CompanionSession(CompanionDevice(it, it, "http://$it:8723"), "token-$it") }
        override suspend fun save(sessions: List<CompanionSession>) = Unit
    }
    private fun normal(request: CompanionRequest) = CompanionResponse(200, """{"ok":true,"deviceId":"${if (request.url.contains("//b:")) "b" else "a"}","capabilities":{},"settings":{"domains":[]},"sources":{"source":"folder","folderPath":"/sdcard/Pictures/Family","hasImmichKey":true},"apps":[],"np":{}}""")

    @Test fun sourceStateIsFlatAndKeepsConfiguredFlags() = runTest {
        val repo = CompanionRepository(CompanionClient(CompanionTransport { normal(it) }), Store(), backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repo.ready.join(); repo.selectDevice("a").join()
        assertEquals("folder", repo.state.value.sources!!.optString("source"))
        assertEquals("/sdcard/Pictures/Family", repo.state.value.sources!!.optString("folderPath"))
        assertTrue(repo.state.value.sources!!.optBoolean("hasImmichKey"))
        assertFalse(repo.state.value.sources!!.has("sources"))
    }

    @Test fun snapshotExportTicketCannotReturnAfterSwitchAwayAndBack() = runTest {
        val pending = CompletableDeferred<CompanionResponse>()
        val repo = CompanionRepository(CompanionClient(CompanionTransport {
            if (it.url.endsWith("/snapshot")) pending.await() else normal(it)
        }), Store(), backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repo.ready.join(); repo.selectDevice("a").join()
        val before = repo.state.value.sessionGeneration
        var snapshot: JSONObject? = JSONObject()
        val export = backgroundScope.launch { snapshot = repo.exportSnapshotResult() }
        runCurrent()
        repo.selectDevice("b").join(); repo.selectDevice("a").join()
        assertTrue(repo.state.value.sessionGeneration > before)
        pending.complete(CompanionResponse(200, """{"ok":true,"snapshot":{"schemaVersion":1,"deviceId":"a","revision":"old"}}"""))
        export.join()
        assertNull(snapshot)
        assertNull(repo.state.value.snapshot)
    }

    @Test fun tapBarrierRunsBeforeMovementsQueuedAfterTheTap() = runTest {
        val calls = mutableListOf<CompanionRequest>()
        val firstCursor = CompletableDeferred<CompanionResponse>()
        val repo = CompanionRepository(CompanionClient(CompanionTransport { request ->
            calls += request
            if (request.url.endsWith("/cursor") && calls.count { it.url.endsWith("/cursor") } == 1) firstCursor.await()
            else normal(request)
        }), Store(), backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repo.ready.join(); repo.selectDevice("a").join()
        repo.moveCursor(10f, 0f)
        runCurrent(); advanceTimeBy(40); runCurrent()
        val tap = repo.tap()
        repo.moveCursor(20f, 0f)
        runCurrent()
        firstCursor.complete(CompanionResponse(200, """{"ok":true}"""))
        runCurrent(); advanceTimeBy(40); runCurrent()
        advanceTimeBy(40); runCurrent()
        tap.join()
        assertEquals(listOf("cursor", "tap", "cursor"), calls.map { it.url.substringAfterLast('/') }.filter { it in setOf("cursor", "tap") })
        assertEquals(20.0, JSONObject(calls.last { it.url.endsWith("/cursor") }.body!!).getDouble("dx"), 0.01)
    }
}
