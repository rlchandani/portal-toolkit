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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompanionRepositoryTest {
    private class Store(initial: List<CompanionSession> = emptyList()) : CompanionSessionStore {
        var sessions = initial
        override suspend fun load() = sessions
        override suspend fun save(sessions: List<CompanionSession>) { this.sessions = sessions }
    }
    private class Http : CompanionTransport {
        val calls = mutableListOf<CompanionRequest>()
        var handle: suspend (CompanionRequest) -> CompanionResponse = { request ->
            when (request.url.substringAfterLast('/')) {
                "pair" -> CompanionResponse(200, """{"ok":true,"token":"paired","name":"Kitchen"}""")
                "companion" -> CompanionResponse(200, """{"ok":true,"deviceId":"${if (request.url.contains("portal.local")) "stable" else request.url.substringAfter("://").substringBefore(":")}","name":"Kitchen","model":"Portal+","apiLevel":29,"capabilities":{}}""")
                "settings" -> CompanionResponse(200, """{"ok":true,"settings":{"domains":[]}}""")
                "apps" -> CompanionResponse(200, """{"ok":true,"apps":[]}""")
                "nowplaying" -> CompanionResponse(200, """{"ok":true,"np":{"title":"Song"}}""")
                "sources" -> CompanionResponse(200, """{"ok":true,"sources":{"source":"local"}}""")
                else -> CompanionResponse(404, """{"ok":false,"error":"not_found"}""")
            }
        }
        override suspend fun execute(request: CompanionRequest): CompanionResponse {
            calls += request
            return handle(request)
        }
    }
    private fun session(id: String, host: String = id) = CompanionSession(CompanionDevice(id, id, "http://$host:8787"), "token-$id")

    @Test fun pairingUsesPinThenBearerAndPersistsStableIdentity() = runTest {
        val store = Store()
        val http = Http()
        val repo = CompanionRepository(CompanionClient(http), store, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repo.pair("portal.local", "123456").join()
        assertEquals(CompanionConnection.CONNECTED, repo.state.value.connection)
        assertEquals("stable", repo.state.value.activeDeviceId)
        assertEquals("http://portal.local:8723", store.sessions.single().device.endpoint)
        assertEquals("123456", JSONObject(http.calls.first().body!!).getString("pin"))
        assertNull(http.calls.first().sessionToken)
        assertTrue(http.calls.drop(1).all { it.sessionToken == "paired" })
        assertFalse(http.calls.any { it.url.endsWith("/layout") }) // capability absence must not guess support
    }

    @Test fun refreshFromOldGenerationCannotReplaceNewDevice() = runTest {
        val http = Http()
        val blocker = CompletableDeferred<CompanionResponse>()
        val repo = CompanionRepository(CompanionClient(http), Store(listOf(session("a"), session("b"))), backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repo.ready.join()
        http.handle = { request ->
            if (request.url.contains("//a:") && request.url.endsWith("/companion")) blocker.await()
            else CompanionResponse(200, """{"ok":true,"deviceId":"b","name":"B","settings":{"domains":[]},"apps":[],"sources":{},"np":{"title":"B song"},"capabilities":{}}""")
        }
        val old = repo.selectDevice("a")
        repo.selectDevice("b").join()
        blocker.complete(CompanionResponse(200, """{"ok":true,"deviceId":"a","name":"A","capabilities":{}}"""))
        old.join()
        assertEquals("b", repo.state.value.activeDeviceId)
        assertEquals("B", repo.state.value.info!!.getString("name"))
        assertEquals("B song", repo.state.value.nowPlaying!!.getString("title"))
    }

    @Test fun queuedCommandKeepsOriginalSessionAndLateFailureIsIgnored() = runTest {
        val http = Http()
        val store = Store(listOf(session("a"), session("b")))
        val repo = CompanionRepository(CompanionClient(http), store, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repo.ready.join()
        http.handle = { CompanionResponse(200, """{"ok":true,"settings":{"domains":[]},"apps":[],"sources":{},"np":{},"capabilities":{}}""") }
        repo.selectDevice("a").join()
        val pending = CompletableDeferred<CompanionResponse>()
        http.handle = { if (it.url.endsWith("/key")) pending.await() else CompanionResponse(200, """{"ok":true,"settings":{"domains":[]},"apps":[],"sources":{},"np":{},"capabilities":{}}""") }
        val command = repo.remoteAction("home")
        runCurrent()
        repo.selectDevice("b").join()
        pending.complete(CompanionResponse(401, """{"ok":false,"error":"unauthorized"}"""))
        command.join()
        val request = http.calls.single { it.url.endsWith("/key") }
        assertTrue(request.url.startsWith("http://a:"))
        assertEquals("token-a", request.sessionToken)
        assertEquals(CompanionConnection.CONNECTED, repo.state.value.connection)
        assertNull(repo.state.value.error)
    }

    @Test fun rejectedRegistryWriteRetainsValidationDetailsAndRefreshesConditionalRows() = runTest {
        val http = Http()
        val repo = CompanionRepository(CompanionClient(http), Store(listOf(session("a"))), backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repo.ready.join()
        http.handle = { CompanionResponse(200, """{"ok":true,"settings":{"domains":[]},"apps":[],"sources":{},"np":{},"capabilities":{}}""") }
        repo.selectDevice("a").join()
        http.calls.clear()
        http.handle = {
            if (it.method == "POST") CompanionResponse(200, """{"ok":true,"applied":[],"domain":{"id":"screensaver"},"errors":{"intervalSec":"out_of_range"}}""")
            else CompanionResponse(200, """{"ok":true,"settings":{"domains":[{"id":"screensaver","controls":[{"key":"shuffle","type":"bool","value":true}]}]}}""")
        }
        repo.writeSettings("screensaver", JSONObject().put("intervalSec", -1)).join()
        assertNotNull(repo.state.value.error)
        assertEquals("out_of_range", repo.state.value.error!!.details!!.getJSONObject("errors").getString("intervalSec"))
        assertNull(repo.state.value.lastResult)
        assertEquals("shuffle", repo.state.value.settings.single().controls.single().key)
        assertEquals(listOf("POST", "GET"), http.calls.map { it.method })
    }

    @Test fun disconnectInvalidatesPendingPairAndDoesNotSaveCredentials() = runTest {
        val http = Http()
        val pair = CompletableDeferred<CompanionResponse>()
        val store = Store()
        val repo = CompanionRepository(CompanionClient(http), store, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repo.ready.join()
        http.handle = { pair.await() }
        val pending = repo.pair("portal.local", "123456")
        repo.disconnect()
        pair.complete(CompanionResponse(200, """{"ok":true,"token":"late","name":"Late"}"""))
        pending.join()
        assertTrue(store.sessions.isEmpty())
        assertNull(repo.state.value.activeDeviceId)
        assertEquals(CompanionConnection.UNPAIRED, repo.state.value.connection)
    }

    @Test fun unauthorizedAndOfflinePreserveLastKnownData() = runTest {
        val http = Http()
        val repo = CompanionRepository(CompanionClient(http), Store(listOf(session("a"))), backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repo.ready.join()
        repo.selectDevice("a").join()
        val timestamp = repo.state.value.lastUpdatedAt
        http.handle = { CompanionResponse(401, """{"ok":false,"error":"unauthorized"}""") }
        repo.refresh().join()
        assertEquals(CompanionConnection.UNAUTHORIZED, repo.state.value.connection)
        assertEquals(timestamp, repo.state.value.lastUpdatedAt)
        assertNotNull(repo.state.value.nowPlaying)
        http.handle = { throw java.io.IOException("unreachable") }
        repo.refresh().join()
        assertEquals(CompanionConnection.OFFLINE, repo.state.value.connection)
        assertNotNull(repo.state.value.nowPlaying)
    }

    @Test fun sourcePayloadNeverPublishesConfiguredCredentials() = runTest {
        val http = Http()
        http.handle = { CompanionResponse(200, """{"ok":true,"deviceId":"a","capabilities":{},"settings":{"domains":[]},"apps":[],"np":{},"sources":{"folderPath":"/sdcard/Pictures/Family","immich":{"url":"https://photos","key":"private-key"},"smb":{"pass":"private-pass"}}}""") }
        val repo = CompanionRepository(CompanionClient(http), Store(listOf(session("a"))), backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repo.ready.join()
        repo.selectDevice("a").join()
        assertFalse(repo.state.value.sources!!.toString().contains("private"))
        assertEquals("/sdcard/Pictures/Family", repo.state.value.sources!!.getString("folderPath"))
    }

    @Test fun partialSourceApplyCannotClaimFullWriteSaved() = runTest {
        val http = Http()
        val repo = CompanionRepository(CompanionClient(http), Store(listOf(session("a"))), backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repo.ready.join()
        http.handle = { CompanionResponse(200, """{"ok":true,"deviceId":"a","capabilities":{},"settings":{"domains":[]},"apps":[],"sources":{},"np":{}}""") }
        repo.selectDevice("a").join()
        http.handle = { if (it.method == "POST") CompanionResponse(200, """{"ok":true,"applied":["source"]}""")
            else CompanionResponse(200, """{"ok":true,"sources":{},"settings":{"domains":[]}}""") }
        repo.writeSources(JSONObject().put("source", "default").put("folderPath", "")).join()
        assertNotNull(repo.state.value.error)
        assertNull(repo.state.value.lastResult)
    }

    @Test fun refreshUpdatesDeviceMetadataAndUpgradesLegacyIdentity() = runTest {
        val store = Store(listOf(session("legacy:old", "portal.local")))
        val repo = CompanionRepository(CompanionClient(Http()), store, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repo.ready.join()
        repo.selectDevice("legacy:old").join()
        assertEquals("stable", repo.state.value.activeDeviceId)
        assertEquals("Portal+", repo.state.value.activeDevice!!.model)
        assertEquals("stable", store.sessions.single().device.id)
    }
}
