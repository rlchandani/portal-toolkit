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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompanionStatusImagesTest {
    private class Store : CompanionSessionStore {
        override suspend fun load() = listOf("a", "b").map { CompanionSession(CompanionDevice(it, it, "http://$it:8723"), "token-$it") }
        override suspend fun save(sessions: List<CompanionSession>) = Unit
    }
    private fun metadata(request: CompanionRequest) = CompanionResponse(200, """{"ok":true,"deviceId":"${if (request.url.contains("//b:")) "b" else "a"}","capabilities":{},"settings":{"domains":[]},"sources":{},"apps":[],"np":{}}""")

    @Test fun passiveStatusReadsOnlyTwoRoutesWithoutBusyOrErasingWriteError() = runTest {
        val calls = mutableListOf<CompanionRequest>()
        var rejectWrite = false
        val http = CompanionTransport { request ->
            calls += request
            if (rejectWrite && request.method == "POST") CompanionResponse(400, """{"ok":false,"error":"validation_failed"}""") else metadata(request)
        }
        val repo = CompanionRepository(CompanionClient(http), Store(), backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repo.ready.join(); repo.selectDevice("a").join()
        rejectWrite = true
        repo.remoteAction("invalid").join()
        val error = repo.state.value.error
        calls.clear()
        val status = repo.refreshStatus()
        runCurrent()
        assertFalse(repo.state.value.busy)
        status.join()
        assertEquals(listOf("companion", "nowplaying"), calls.map { it.url.substringAfterLast('/') })
        assertEquals(error, repo.state.value.error)
    }

    @Test fun imageConcurrencyIsFourAndCacheNeverCrossesSession() = runTest {
        var active = 0
        var maxActive = 0
        val binaryCalls = mutableListOf<CompanionRequest>()
        val release = CompletableDeferred<Unit>()
        val http = CompanionTransport { request ->
            if (request.binary) {
                binaryCalls += request
                active++; maxActive = maxOf(maxActive, active)
                try { release.await(); CompanionResponse(200, "", byteArrayOf(1,2,3)) } finally { active-- }
            } else metadata(request)
        }
        val repo = CompanionRepository(CompanionClient(http), Store(), backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repo.ready.join(); repo.selectDevice("a").join()
        val loads = (1..8).map { index -> backgroundScope.launch { repo.photoBytes("/sdcard/Pictures/Immortal/$index.jpg") } }
        runCurrent()
        assertEquals(4, active)
        assertEquals(4, maxActive)
        release.complete(Unit)
        loads.forEach { it.join() }
        val before = binaryCalls.size
        assertNotNull(repo.photoBytes("/sdcard/Pictures/Immortal/1.jpg"))
        assertEquals(before, binaryCalls.size)
        repo.selectDevice("b").join()
        assertNotNull(repo.photoBytes("/sdcard/Pictures/Immortal/1.jpg"))
        assertEquals(before + 1, binaryCalls.size)
        assertTrue(binaryCalls.last().url.startsWith("http://b:"))
    }

    @Test fun callbackAndSuspendThumbnailsShareTheSameLimitAndStayPassive() = runTest {
        var active = 0
        val release = CompletableDeferred<Unit>()
        val http = CompanionTransport { request ->
            if (request.binary) {
                active++
                try { release.await(); CompanionResponse(200, "", byteArrayOf(1)) } finally { active-- }
            } else metadata(request)
        }
        val repo = CompanionRepository(CompanionClient(http), Store(), backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repo.ready.join(); repo.selectDevice("a").join()
        val callbacks = (1..4).map { repo.loadPhotoThumbnail("/sdcard/Pictures/Immortal/$it.jpg") {} }
        val suspends = (5..8).map { index -> backgroundScope.launch { repo.photoBytes("/sdcard/Pictures/Immortal/$index.jpg") } }
        runCurrent()
        assertEquals(4, active)
        assertFalse(repo.state.value.busy)
        release.complete(Unit)
        (callbacks + suspends).forEach { it.join() }
    }
}
