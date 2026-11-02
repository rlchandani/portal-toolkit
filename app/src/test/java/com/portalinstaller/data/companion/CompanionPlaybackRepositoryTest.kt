/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.companion

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompanionPlaybackRepositoryTest {
    private class Store(initial: List<CompanionSession>) : CompanionSessionStore {
        var sessions = initial
        override suspend fun load() = sessions
        override suspend fun save(sessions: List<CompanionSession>) { this.sessions = sessions }
    }

    private class Http : CompanionTransport {
        val calls = mutableListOf<CompanionRequest>()
        val images get() = calls.filter { it.binary }
        var handle: suspend (CompanionRequest) -> CompanionResponse = { normal(it) }
        override suspend fun execute(request: CompanionRequest): CompanionResponse {
            calls += request
            return handle(request)
        }
        fun normal(request: CompanionRequest): CompanionResponse = when (request.url.toHttpUrl().encodedPath) {
            "/remote/companion" -> CompanionResponse(200,
                """{"ok":true,"deviceId":"${request.url.toHttpUrl().host}","capabilities":{}}""")
            "/remote/nowplaying" -> CompanionResponse(200,
                """{"ok":true,"np":{"active":true,"title":"Track","volume":{"level":2,"max":15,"muted":false,"canSet":true}}}""")
            else -> CompanionResponse(404, """{"ok":false,"error":"not_found"}""")
        }
    }

    private fun session(id: String) = CompanionSession(CompanionDevice(id, id, "http://$id:8723"), "test-session-$id")

    private suspend fun TestScope.connectedRepository(
        http: Http,
        store: Store = Store(listOf(session("a"), session("b"))),
        clock: () -> Long = { 0L },
    ): CompanionRepository {
        val repo = CompanionRepository(CompanionClient(http), store, backgroundScope,
            UnconfinedTestDispatcher(testScheduler), clock = clock, preloadManagement = false)
        repo.ready.join()
        repo.selectDevice("a").join()
        return repo
    }

    @Test fun artworkCacheUsesVersionAndProtectsBytesFromCallerMutation() = runTest {
        val http = Http()
        val version = "cover 1/&?"
        http.handle = { request ->
            if (request.binary) CompanionResponse(200, "",
                if (request.url.toHttpUrl().queryParameter("v") == version) byteArrayOf(1, 2, 3) else byteArrayOf(4, 5, 6))
            else http.normal(request)
        }
        val repo = connectedRepository(http)
        val first = repo.mediaArtBytes(version)!!
        first[0] = 99
        val cached = repo.mediaArtBytes(version)!!
        assertArrayEquals(byteArrayOf(1, 2, 3), cached)
        cached[1] = 99
        assertArrayEquals(byteArrayOf(1, 2, 3), repo.mediaArtBytes(version))
        assertEquals(1, http.images.size)
        assertFalse(repo.state.value.busy)
        assertArrayEquals(byteArrayOf(4, 5, 6), repo.mediaArtBytes("next-cover"))
        assertEquals(listOf(version, "next-cover"), http.images.map { it.url.toHttpUrl().queryParameter("v") })
        assertTrue(http.images.all { it.method == "GET" && it.url.toHttpUrl().encodedPath == "/remote/art" })
    }

    @Test fun expiredArtworkIsFetchedAgainWithoutUsingRealTime() = runTest {
        val http = Http()
        var now = 0L
        var bytes = byteArrayOf(1)
        http.handle = { request -> if (request.binary) CompanionResponse(200, "", bytes) else http.normal(request) }
        val repo = connectedRepository(http, clock = { now })
        assertArrayEquals(byteArrayOf(1), repo.mediaArtBytes("7"))
        bytes = byteArrayOf(2)
        now = 299_999L
        assertArrayEquals(byteArrayOf(1), repo.mediaArtBytes("7"))
        assertEquals(1, http.images.size)
        now = 300_000L
        assertArrayEquals(byteArrayOf(2), repo.mediaArtBytes("7"))
        assertEquals(2, http.images.size)
    }

    @Test fun identicalArtworkVersionsDoNotShareBytesAcrossDevices() = runTest {
        val http = Http()
        http.handle = { request ->
            if (request.binary) CompanionResponse(200, "",
                if (request.url.toHttpUrl().host == "a") byteArrayOf(1) else byteArrayOf(2))
            else http.normal(request)
        }
        val repo = connectedRepository(http)
        assertArrayEquals(byteArrayOf(1), repo.mediaArtBytes("7"))
        repo.selectDevice("b").join()
        assertArrayEquals(byteArrayOf(2), repo.mediaArtBytes("7"))
        repo.selectDevice("a").join()
        assertArrayEquals(byteArrayOf(1), repo.mediaArtBytes("7"))
        assertEquals(listOf("a", "b"), http.images.map { it.url.toHttpUrl().host })
    }

    @Test fun pairingAgainDoesNotReuseArtworkFromReplacedCredentials() = runTest {
        val http = Http()
        http.handle = { request ->
            when {
                request.binary -> CompanionResponse(200, "", if (http.images.size == 1) byteArrayOf(1) else byteArrayOf(2))
                request.url.endsWith("/pair") -> CompanionResponse(200, """{"ok":true,"token":"replacement-test-session","name":"a"}""")
                else -> http.normal(request)
            }
        }
        val repo = connectedRepository(http)
        assertArrayEquals(byteArrayOf(1), repo.mediaArtBytes("7"))
        repo.pair("http://a:8723", "123456").join()
        assertEquals("a", repo.state.value.activeDeviceId)
        assertEquals(CompanionConnection.CONNECTED, repo.state.value.connection)
        assertArrayEquals(byteArrayOf(2), repo.mediaArtBytes("7"))
        assertEquals(2, http.images.size)
        assertEquals(http.images.first().url, http.images.last().url)
        assertTrue(http.images.first().sessionToken != http.images.last().sessionToken)
    }

    @Test fun lateArtworkAfterSwitchAwayAndBackIsNeitherReturnedNorCached() = runTest {
        val http = Http()
        val pending = CompletableDeferred<CompanionResponse>()
        http.handle = { request ->
            if (request.binary) {
                if (http.images.size == 1) pending.await() else CompanionResponse(200, "", byteArrayOf(2))
            } else http.normal(request)
        }
        val repo = connectedRepository(http)
        val old = backgroundScope.async { repo.mediaArtBytes("7") }
        runCurrent()
        assertEquals(1, http.images.size)
        repo.selectDevice("b").join()
        repo.selectDevice("a").join()
        pending.complete(CompanionResponse(200, "", byteArrayOf(1)))
        assertNull(old.await())
        assertArrayEquals(byteArrayOf(2), repo.mediaArtBytes("7"))
        assertEquals(2, http.images.size)
        assertNull(repo.state.value.error)
    }

    @Test fun setVolumeWaitsForRefreshAndPublishesThePortalsActualLevel() = runTest {
        val http = Http()
        val repo = connectedRepository(http)
        http.calls.clear()
        val refreshed = CompletableDeferred<CompanionResponse>()
        http.handle = { request ->
            when {
                request.method == "POST" && request.url.endsWith("/volume") ->
                    CompanionResponse(200, """{"ok":true,"level":7}""")
                request.method == "GET" && request.url.endsWith("/nowplaying") -> refreshed.await()
                else -> http.normal(request)
            }
        }
        val change = repo.setVolume(7)
        runCurrent()
        assertFalse(change.isCompleted)
        assertTrue(repo.state.value.busy)
        assertEquals(2, CompanionPlayback.fromJson(repo.state.value.nowPlaying).volume!!.level)
        refreshed.complete(CompanionResponse(200, """{
            "ok":true,"np":{"active":true,"title":"Track","volume":{"level":6,"max":15,"canSet":true}}
        }"""))
        change.join()
        assertEquals(6, CompanionPlayback.fromJson(repo.state.value.nowPlaying).volume!!.level)
        assertFalse(repo.state.value.busy)
        assertNull(repo.state.value.error)
        assertTrue(repo.state.value.lastResult!!.getBoolean("ok"))
        assertEquals(listOf("POST /remote/volume", "GET /remote/nowplaying"),
            http.calls.map { "${it.method} ${it.url.toHttpUrl().encodedPath}" })
        val body = JSONObject(http.calls.first().body!!)
        assertEquals("set", body.getString("dir"))
        assertEquals(7, body.getInt("level"))
    }

    @Test fun textIsConfirmedOnlyAfterThePortalAcknowledgesTheUnchangedDraft() = runTest {
        val http = Http()
        val repo = connectedRepository(http)
        val pending = CompletableDeferred<CompanionResponse>()
        http.handle = { request -> if (request.url.endsWith("/text")) pending.await() else http.normal(request) }
        val text = "First line\nCafé 🎵"
        val confirmation = backgroundScope.async { repo.sendTextConfirmed(text) }
        runCurrent()
        assertFalse(confirmation.isCompleted)
        assertTrue(repo.state.value.busy)
        assertNull(repo.state.value.lastResult)
        val request = http.calls.single { it.url.endsWith("/text") }
        assertEquals("POST", request.method)
        val body = JSONObject(request.body!!)
        assertEquals(text, body.getString("text"))
        assertEquals("set", body.getString("mode"))
        pending.complete(CompanionResponse(200, """{"ok":true,"applied":true,"receipt":"text-applied"}"""))
        assertTrue(confirmation.await())
        assertEquals("text-applied", repo.state.value.lastResult!!.getString("receipt"))
        assertFalse(repo.state.value.busy)
        assertNull(repo.state.value.error)
    }

    @Test fun rejectedOrUnappliedTextReturnsFalseWithTheActionError() = runTest {
        data class Case(val response: CompanionResponse, val code: String, val connection: CompanionConnection)
        for (case in listOf(
            Case(CompanionResponse(400, """{"ok":false,"error":"no_accessibility"}"""), "no_accessibility", CompanionConnection.CONNECTED),
            Case(CompanionResponse(200, """{"ok":true,"applied":false}"""), "not_applied", CompanionConnection.CONNECTED),
            Case(CompanionResponse(401, """{"ok":false,"error":"unauthorized"}"""), "unauthorized", CompanionConnection.UNAUTHORIZED),
        )) {
            val http = Http()
            val repo = connectedRepository(http)
            http.handle = { request -> if (request.url.endsWith("/text")) case.response else http.normal(request) }
            assertFalse(case.code, repo.sendTextConfirmed("Keep this draft"))
            assertEquals(case.code, repo.state.value.error!!.code)
            assertEquals(case.connection, repo.state.value.connection)
            assertNull(repo.state.value.lastResult)
            assertFalse(repo.state.value.busy)
        }
    }

    @Test fun unpairedTextIsNotConfirmedAndMakesNoTransportRequest() = runTest {
        val http = Http()
        val repo = CompanionRepository(CompanionClient(http), Store(emptyList()), backgroundScope,
            UnconfinedTestDispatcher(testScheduler), preloadManagement = false)
        repo.ready.join()
        assertFalse(repo.sendTextConfirmed("Keep this draft"))
        assertTrue(http.calls.isEmpty())
        assertEquals("invalid_request", repo.state.value.error!!.code)
        assertFalse(repo.state.value.busy)
    }

    @Test fun lateTextSuccessOrFailureCannotConfirmAfterSwitchAwayAndBack() = runTest {
        for (response in listOf(
            CompanionResponse(200, """{"ok":true,"applied":true,"receipt":"old-selection"}"""),
            CompanionResponse(401, """{"ok":false,"error":"unauthorized"}"""),
        )) {
            val http = Http()
            val pending = CompletableDeferred<CompanionResponse>()
            val repo = connectedRepository(http)
            http.handle = { request -> if (request.url.endsWith("/text")) pending.await() else http.normal(request) }
            val old = backgroundScope.async { repo.sendTextConfirmed("Old draft") }
            runCurrent()
            assertFalse(old.isCompleted)
            assertEquals(1, http.calls.count { it.url.endsWith("/text") })
            repo.selectDevice("b").join()
            repo.selectDevice("a").join()
            val generation = repo.state.value.sessionGeneration
            pending.complete(response)
            assertFalse(old.await())
            assertEquals("a", repo.state.value.activeDeviceId)
            assertEquals(generation, repo.state.value.sessionGeneration)
            assertEquals(CompanionConnection.CONNECTED, repo.state.value.connection)
            assertNull(repo.state.value.lastResult)
            assertNull(repo.state.value.error)
            assertFalse(repo.state.value.busy)
        }
    }

    @Test fun textWaitingBehindAnotherWriteIsDroppedWhenTheDeviceChanges() = runTest {
        val http = Http()
        val repo = connectedRepository(http)
        val pendingKey = CompletableDeferred<CompanionResponse>()
        http.handle = { request -> if (request.url.endsWith("/key")) pendingKey.await() else http.normal(request) }
        val key = repo.remoteAction("home")
        runCurrent()
        assertEquals(1, http.calls.count { it.url.endsWith("/key") })
        val queuedText = backgroundScope.async { repo.sendTextConfirmed("Old draft") }
        runCurrent()
        assertFalse(queuedText.isCompleted)
        assertFalse(http.calls.any { it.url.endsWith("/text") })
        repo.selectDevice("b").join()
        pendingKey.complete(CompanionResponse(200, """{"ok":true}"""))
        key.join()
        assertFalse(queuedText.await())
        assertFalse(http.calls.any { it.url.endsWith("/text") })
        assertEquals("b", repo.state.value.activeDeviceId)
        assertEquals(CompanionConnection.CONNECTED, repo.state.value.connection)
        assertNull(repo.state.value.error)
        assertNull(repo.state.value.lastResult)
        assertFalse(repo.state.value.busy)
    }
}
