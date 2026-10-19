/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.companion

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompanionMusicRepositoryTest {
    private class Store : CompanionSessionStore {
        override suspend fun load() = listOf("a", "b").map {
            CompanionSession(CompanionDevice(it, it, "http://$it:8723"), "private-$it")
        }
        override suspend fun save(sessions: List<CompanionSession>) {}
    }

    private class Http : CompanionTransport {
        val calls = mutableListOf<CompanionRequest>()
        var music: suspend (CompanionRequest) -> CompanionResponse = {
            CompanionResponse(200, """{"ok":true,"music":{"connection":"ready"}}""")
        }
        override suspend fun execute(request: CompanionRequest): CompanionResponse {
            calls += request
            if (request.url.endsWith("/music") || request.url.endsWith("/music/command")) return music(request)
            val id = request.url.substringAfter("://").substringBefore(":")
            return CompanionResponse(200,
                """{"ok":true,"deviceId":"$id","capabilities":{},"np":{}}""")
        }
    }

    @Test fun musicTargetsViewedPortalEvenWhenBothAreSelected() = runTest {
        val http = Http()
        val repo = CompanionRepository(CompanionClient(http), Store(), backgroundScope,
            UnconfinedTestDispatcher(testScheduler), preloadManagement = false)
        repo.ready.join()
        repo.selectDevice("a").join()
        repo.selectTargets(setOf("a", "b")).join()
        val key = "a:${repo.state.value.sessionGeneration}"
        repo.readMusic(key)
        repo.sendMusic(key, JSONObject().put("command", "pause"))
        val music = http.calls.filter { it.url.endsWith("/music") || it.url.endsWith("/music/command") }
        assertEquals(2, music.size)
        assertTrue(music.all { it.url.startsWith("http://a:") && it.sessionToken == "private-a" })
        assertEquals("/remote/music/command", java.net.URI(music.last().url).path)
    }

    @Test fun oldDeviceKeyDoesNotDispatchAndLateReadCannotBecomeNewDeviceState() = runTest {
        val http = Http()
        val blocked = CompletableDeferred<CompanionResponse>()
        val repo = CompanionRepository(CompanionClient(http), Store(), backgroundScope,
            UnconfinedTestDispatcher(testScheduler), preloadManagement = false)
        repo.ready.join()
        repo.selectDevice("a").join()
        val oldKey = "a:${repo.state.value.sessionGeneration}"
        http.music = { blocked.await() }
        val old = backgroundScope.async { repo.readMusic(oldKey) }
        runCurrent()
        repo.selectDevice("b").join()
        assertNull(repo.sendMusic(oldKey, JSONObject().put("command", "play")))
        blocked.complete(CompanionResponse(200, """{"ok":true,"music":{"title":"Old source"}}"""))
        assertNull(old.await())
        assertFalse(http.calls.any { it.method == "POST" })
    }

    @Test fun expiredMusicPairingDisablesTheSessionWithoutMarkingFeatureUnsupported() = runTest {
        val http = Http()
        val repo = CompanionRepository(CompanionClient(http), Store(), backgroundScope,
            UnconfinedTestDispatcher(testScheduler), preloadManagement = false)
        repo.ready.join()
        repo.selectDevice("a").join()
        http.music = { CompanionResponse(401, """{"ok":false,"error":"unauthorized"}""") }
        try {
            repo.readMusic("a:${repo.state.value.sessionGeneration}")
            fail("Expired pairing must fail.")
        } catch (error: CompanionException) { assertEquals(401, error.error.status) }
        assertEquals(CompanionConnection.UNAUTHORIZED, repo.state.value.connection)
        assertFalse(repo.state.value.connected)
    }
}
