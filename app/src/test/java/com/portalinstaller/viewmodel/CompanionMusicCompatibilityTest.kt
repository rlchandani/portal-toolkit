/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.viewmodel

import com.portalinstaller.data.companion.CompanionClient
import com.portalinstaller.data.companion.CompanionConnection
import com.portalinstaller.data.companion.CompanionDevice
import com.portalinstaller.data.companion.CompanionMusicAction
import com.portalinstaller.data.companion.CompanionPlayback
import com.portalinstaller.data.companion.CompanionRepository
import com.portalinstaller.data.companion.CompanionRequest
import com.portalinstaller.data.companion.CompanionResponse
import com.portalinstaller.data.companion.CompanionSession
import com.portalinstaller.data.companion.CompanionSessionStore
import com.portalinstaller.data.companion.CompanionTransport
import com.portalinstaller.data.companion.commandBody
import com.portalinstaller.ui.controlcenter.usesLegacyPlayback
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Only the HTTP boundary is synthetic; parsing, session ownership and UI policy are real. */
@OptIn(ExperimentalCoroutinesApi::class)
class CompanionMusicCompatibilityTest {
    @Test fun missingMusicRouteAllowsLegacyPlayback() = unsupportedRoute(404)

    @Test fun rejectedMusicMethodAllowsLegacyPlayback() = unsupportedRoute(405)

    @Test fun unimplementedMusicRouteAllowsLegacyPlayback() = unsupportedRoute(501)

    private fun unsupportedRoute(status: Int) = runTest {
        val fixture = fixture()
        fixture.http.music = { unsupported(status) }
        fixture.controller.refresh().join()
        val state = fixture.controller.state.value
        assertTrue(state.unsupported)
        assertTrue(usesLegacyPlayback(fixture.key, state))
        assertFalse(state.fresh)
        assertFalse(state.reading)
        assertFalse(state.sending)
        assertTrue(fixture.repo.state.value.connected)
        assertTrue(fixture.http.writes.isEmpty())

        // The same nowplaying snapshot consumed by MediaPanel remains available.
        val playback = CompanionPlayback.fromJson(fixture.repo.state.value.nowPlaying)
        assertTrue(playback.active)
        assertEquals("Local radio", playback.title)
        // Observing unsupported music never sends a command. An explicit legacy action can.
        fixture.repo.media("pause").join()
        val write = fixture.http.writes.single()
        assertEquals("portal-a.invalid", URI(write.url).host)
        assertEquals("/remote/media", URI(write.url).path)
        assertEquals("pause", JSONObject(write.body!!).getString("action"))
    }

    @Test fun expiredPairingDisablesMusicWithoutSelectingFallback() = runTest {
        val fixture = fixture()
        fixture.controller.refresh().join()
        val observed = fixture.controller.state.value.music!!
        fixture.http.music = { CompanionResponse(401, """{"ok":false,"error":"unauthorized"}""") }
        fixture.controller.refresh().join()
        runCurrent() // The repository connection change reaches the controller's session observer.
        assertEquals(CompanionConnection.UNAUTHORIZED, fixture.repo.state.value.connection)
        assertFalse(fixture.repo.state.value.connected)
        assertFalse(fixture.controller.state.value.connected)
        assertFalse(fixture.controller.state.value.unsupported)
        assertFalse(usesLegacyPlayback(fixture.key, fixture.controller.state.value))
        fixture.controller.command(fixture.key, observed, CompanionMusicAction.Transport("pause")).join()
        assertTrue(fixture.http.writes.isEmpty())
    }

    @Test fun networkFailureKeepsNativeMusicStaleWithoutSelectingFallback() = readFailure {
        throw IOException("Synthetic connection failure")
    }

    @Test fun malformedSchemaKeepsNativeMusicStaleWithoutSelectingFallback() = readFailure {
        val response = JSONObject(nativeMusic().body)
        response.getJSONObject("music").put("schemaVersion", 2)
        CompanionResponse(200, response.toString())
    }

    @Test fun missingSchemaFieldsDoNotBecomeOlderPortalSupport() = readFailure {
        CompanionResponse(200, """{"ok":true,"music":{"connection":"ready"}}""")
    }

    @Test fun invalidJsonOnSuccessfulHttpDoesNotSelectFallback() = readFailure {
        CompanionResponse(200, "<html>not a music response</html>")
    }

    @Test fun serverFailureDoesNotSelectFallback() = readFailure {
        CompanionResponse(500, """{"ok":false,"error":"internal_error"}""")
    }

    private fun readFailure(response: suspend () -> CompanionResponse) = runTest {
        val fixture = fixture()
        fixture.controller.refresh().join()
        val observed = fixture.controller.state.value.music!!
        fixture.http.music = { response() }
        fixture.controller.refresh().join()
        val state = fixture.controller.state.value
        assertEquals("Native song", state.music?.track?.title)
        assertFalse(state.fresh)
        assertFalse(state.unsupported)
        assertFalse(usesLegacyPlayback(fixture.key, state))
        assertFalse(state.reading)
        assertFalse(state.sending)
        assertFalse(state.feedback.isNullOrBlank())
        assertTrue(fixture.repo.state.value.connected)
        fixture.controller.command(fixture.key, observed, CompanionMusicAction.Transport("pause")).join()
        assertTrue(fixture.http.writes.isEmpty())
    }

    @Test fun unsupportedReadInvalidatesPreviouslyAvailableNativeCommands() = runTest {
        val fixture = fixture()
        fixture.controller.refresh().join()
        val observed = fixture.controller.state.value.music!!
        assertNotNull(observed.commandBody(CompanionMusicAction.Transport("pause")))
        fixture.http.music = { unsupported(404) }
        fixture.controller.refresh().join()
        assertTrue(usesLegacyPlayback(fixture.key, fixture.controller.state.value))
        fixture.controller.command(fixture.key, observed, CompanionMusicAction.Transport("pause")).join()
        assertFalse(fixture.controller.state.value.fresh)
        assertTrue(fixture.http.writes.isEmpty())
    }

    @Test fun successfulRefreshClearsUnsupportedWithoutAutomaticallyWriting() = runTest {
        val fixture = fixture()
        fixture.http.music = { unsupported(501) }
        fixture.controller.refresh().join()
        assertTrue(usesLegacyPlayback(fixture.key, fixture.controller.state.value))
        fixture.http.music = { nativeMusic() }
        fixture.controller.refresh().join()
        assertFalse(usesLegacyPlayback(fixture.key, fixture.controller.state.value))
        assertFalse(fixture.controller.state.value.unsupported)
        assertTrue(fixture.controller.state.value.fresh)
        assertEquals("Native song", fixture.controller.state.value.music?.track?.title)
        assertTrue(fixture.http.writes.isEmpty())
    }

    @Test fun unsupportedSnapshotCannotSelectFallbackForAnotherPortalOrReconnectedSession() = runTest {
        val fixture = fixture()
        fixture.http.music = { unsupported(405) }
        fixture.controller.refresh().join()
        val oldKey = fixture.key
        val oldState = fixture.controller.state.value
        assertTrue(usesLegacyPlayback(oldKey, oldState))
        fixture.select("b")
        assertFalse(usesLegacyPlayback(fixture.key, oldState))
        assertFalse(usesLegacyPlayback(fixture.key, fixture.controller.state.value))
        fixture.select("a")
        assertNotEquals(oldKey, fixture.key)
        assertFalse(usesLegacyPlayback(fixture.key, oldState))
        assertFalse(fixture.controller.state.value.unsupported)
        assertTrue(fixture.http.writes.isEmpty())
    }

    @Test fun lateUnsupportedRepositoryReadCannotChangeViewedPortalOrDispatchWrites() = runTest {
        val fixture = fixture(ignoreReadCancellation = true)
        val delayed = CompletableDeferred<CompanionResponse>()
        var delivered = false
        fixture.http.music = { request ->
            if (URI(request.url).host == "portal-a.invalid") {
                delayed.await().also { delivered = true }
            } else nativeMusic("Current Portal")
        }
        val oldKey = fixture.key
        val oldRead = fixture.controller.refresh()
        runCurrent()
        assertEquals(1, fixture.http.musicReads.size)
        fixture.select("b")
        assertTrue(oldRead.isCancelled)
        assertFalse(oldRead.isCompleted) // Its transport will still deliver after cancellation.
        fixture.controller.refresh().join()
        val current = fixture.controller.state.value
        delayed.complete(unsupported(404))
        oldRead.join()
        runCurrent()
        assertTrue(delivered)
        assertEquals(current, fixture.controller.state.value)
        assertEquals("b", fixture.repo.state.value.activeDeviceId)
        assertEquals("Current Portal", current.music?.track?.title)
        assertFalse(usesLegacyPlayback(fixture.key, fixture.controller.state.value))
        fixture.controller.command(oldKey, current.music!!, CompanionMusicAction.Transport("pause")).join()
        assertNull(fixture.repo.sendMusic(oldKey, JSONObject().put("command", "pause")))
        assertTrue(fixture.http.writes.isEmpty())
    }

    @Test fun controllerRejectsLateUnsupportedEvenWhenReadBypassesRepositoryOwnership() = runTest {
        for (status in listOf(404, 405, 501)) {
            val http = Http()
            val delayed = CompletableDeferred<CompanionResponse>()
            var delivered = false
            http.music = { request ->
                if (URI(request.url).host == "portal-a.invalid")
                    delayed.await().also { delivered = true }
                else nativeMusic("Current Portal")
            }
            val client = CompanionClient(http)
            val controller = CompanionMusicController(backgroundScope,
                // Model a read already committed to delivery despite cancellation.
                read = { key -> withContext(NonCancellable) {
                    client.get(session(key.substringBefore(':')), "music")
                } },
                send = { key, body -> client.post(session(key.substringBefore(':')), "music/command", body) })
            controller.updateSession("a:1", true)
            val oldRead = controller.refresh()
            runCurrent()
            controller.updateSession("b:2", true)
            assertTrue(oldRead.isCancelled)
            assertFalse(oldRead.isCompleted)
            controller.refresh().join()
            val current = controller.state.value
            delayed.complete(unsupported(status))
            oldRead.join()
            runCurrent()
            assertTrue(delivered)
            assertEquals("HTTP $status", current, controller.state.value)
            assertFalse(usesLegacyPlayback("b:2", controller.state.value))
            controller.command("a:1", current.music!!, CompanionMusicAction.Transport("pause")).join()
            assertTrue(http.writes.isEmpty())
        }
    }

    @Test fun retiredReadCannotSelectFallbackAfterSamePortalStartsObservingAgain() = runTest {
        val http = Http()
        val delayed = CompletableDeferred<CompanionResponse>()
        var reads = 0
        var delivered = false
        http.music = {
            if (++reads == 1) delayed.await().also { delivered = true } else nativeMusic("Fresh observation")
        }
        val client = CompanionClient(http)
        val controller = CompanionMusicController(backgroundScope,
            read = { withContext(NonCancellable) { client.get(session("a"), "music") } },
            send = { _, body -> client.post(session("a"), "music/command", body) })
        controller.updateSession("a:1", true)
        val retired = controller.refresh()
        runCurrent()
        controller.stopObserving()
        assertTrue(retired.isCancelled)
        assertFalse(retired.isCompleted)
        controller.refresh().join()
        val current = controller.state.value
        delayed.complete(unsupported(501))
        retired.join()
        runCurrent()
        assertTrue(delivered)
        assertEquals(current, controller.state.value)
        assertEquals("Fresh observation", current.music?.track?.title)
        assertFalse(usesLegacyPlayback("a:1", controller.state.value))
        assertTrue(http.writes.isEmpty())
    }

    private class Fixture(
        val http: Http,
        val repo: CompanionRepository,
        val controller: CompanionMusicController,
    ) {
        val key get() = "${repo.state.value.activeDeviceId}:${repo.state.value.sessionGeneration}"
        suspend fun select(id: String) {
            repo.selectDevice(id).join()
        }
    }

    private suspend fun TestScope.fixture(ignoreReadCancellation: Boolean = false): Fixture {
        val http = Http()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val store = object : CompanionSessionStore {
            override suspend fun load() = listOf(session("a"), session("b"))
            override suspend fun save(sessions: List<CompanionSession>) {}
        }
        val repo = CompanionRepository(CompanionClient(http), store, backgroundScope, dispatcher,
            preloadManagement = false)
        val controller = CompanionMusicController(backgroundScope,
            read = { key ->
                if (ignoreReadCancellation) withContext(NonCancellable) { repo.readMusic(key) }
                else repo.readMusic(key)
            }, send = repo::sendMusic)
        backgroundScope.launch(dispatcher) {
            repo.state.collect { selected ->
                controller.updateSession("${selected.activeDeviceId}:${selected.sessionGeneration}", selected.connected)
            }
        }
        repo.ready.join()
        return Fixture(http, repo, controller).also { it.select("a") }
    }

    private class Http : CompanionTransport {
        val calls = mutableListOf<CompanionRequest>()
        val writes get() = calls.filter { it.method != "GET" }
        val musicReads get() = calls.filter { URI(it.url).path == "/remote/music" }
        var music: suspend (CompanionRequest) -> CompanionResponse = { nativeMusic() }
        override suspend fun execute(request: CompanionRequest): CompanionResponse {
            calls += request
            return when (URI(request.url).path) {
                "/remote/companion" -> {
                    check(request.method == "GET")
                    val id = URI(request.url).host.substringAfter("portal-").substringBefore(".")
                    CompanionResponse(200, """{"ok":true,"deviceId":"$id","name":"Portal $id","capabilities":{}}""")
                }
                "/remote/nowplaying" -> {
                    check(request.method == "GET")
                    CompanionResponse(200, """{"ok":true,"np":{"active":true,"playing":true,
                        "source":"local","title":"Local radio","artist":"Station","album":"",
                        "durationMs":0,"positionMs":0,"canSeek":false,"hasArt":false,
                        "volume":{"level":5,"max":15,"muted":false,"canSet":true}}}""")
                }
                "/remote/music" -> {
                    check(request.method == "GET")
                    music(request)
                }
                "/remote/media", "/remote/music/command" -> {
                    check(request.method == "POST")
                    CompanionResponse(200, """{"ok":true,"status":"already_applied"}""")
                }
                else -> error("Unexpected synthetic route: ${URI(request.url).path}")
            }
        }
    }

    private companion object {
        fun session(id: String) =
            CompanionSession(CompanionDevice(id, "Portal $id", "http://portal-$id.invalid:8723"), "synthetic-session-$id")

        fun unsupported(status: Int) =
            CompanionResponse(status, """{"ok":false,"error":"unsupported_route"}""")

        fun nativeMusic(title: String = "Native song") = CompanionResponse(200, """
            {"ok":true,"music":{"schemaVersion":1,"sourceRevision":3,"sourceId":"ma:family",
            "connection":"ready","playback":"playing","pending":null,"lastCommand":null,
            "audioOwnedByOther":false,"controlPlayerId":"ma:family","queueId":"queue",
            "track":{"id":"track","title":"$title","artist":"Artist","album":"","durationMs":30000,"isLive":false},
            "positionMs":5000,"capabilities":{"play":true,"pause":true,"next":true,"previous":true,
            "seek":true,"queue":true,"lyrics":false,"rooms":false,"volume":true,"selectPlayer":true},
            "queue":[{"id":"track","title":"Native song","artist":"Artist","durationMs":30000,"available":true,"canSelect":true}],
            "rooms":[{"id":"ma:family","name":"Family Area","available":true,"volume":25,"muted":false,
            "groupId":null,"inCurrentGroup":true,"canSelect":true,"canJoin":false,"canRemove":false,"canSetVolume":true}],
            "lyrics":[]}}
        """.trimIndent())
    }
}
