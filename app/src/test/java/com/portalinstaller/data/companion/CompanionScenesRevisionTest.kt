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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompanionScenesRevisionTest {
    private val firstRevision = "a".repeat(64)
    private val secondRevision = "b".repeat(64)
    private class Store : CompanionSessionStore {
        override suspend fun load() = listOf(CompanionSession(CompanionDevice("a", "Family", "http://a:8723"), "fixture"))
        override suspend fun save(sessions: List<CompanionSession>) {}
    }
    private inner class Server : CompanionTransport {
        var revision: String? = firstRevision
        var scenes = JSONArray("""[{"id":"evening","name":"Evening","steps":[{"type":"key","action":"home"}]}]""")
        val writes = mutableListOf<JSONObject>()
        var keyGate: CompletableDeferred<Unit>? = null
        override suspend fun execute(request: CompanionRequest): CompanionResponse {
            if (request.url.endsWith("/key")) { keyGate?.await(); return CompanionResponse(200, """{"ok":true}""") }
            if (!request.url.endsWith("/scenes")) return CompanionResponse(200, """{"ok":true,"deviceId":"a","capabilities":{"scenes":true},"np":{}}""")
            if (request.method == "POST") {
                val body = JSONObject(request.body!!)
                writes += body
                if (revision != null && body.optString("expectedRevision") != revision)
                    return CompanionResponse(409, """{"ok":false,"error":"stale_revision"}""")
                scenes = JSONArray(body.getJSONArray("scenes").toString())
            }
            val response = JSONObject().put("ok", true).put("scenes", scenes)
            revision?.let { response.put("revision", it) }
            return CompanionResponse(200, response.toString())
        }
        fun addBrowserSchedule() {
            scenes.put(JSONObject("""{"id":"scheduled","name":"Browser schedule","steps":[{"type":"key","action":"screensaver"}],"schedule":{"enabled":true,"hour":20,"minute":0,"days":[1]}}"""))
            revision = secondRevision
        }
    }

    @Test fun scenesRefreshRetainsTheServerRevisionWithItsList() = runTest {
        val server = Server()
        val repo = CompanionRepository(CompanionClient(server), Store(), backgroundScope, UnconfinedTestDispatcher(testScheduler), preloadManagement = false)
        repo.ready.join(); repo.selectDevice("a").join(); repo.refreshScenes().join()
        assertEquals(firstRevision, repo.state.value.scenesRevision)
        server.addBrowserSchedule(); repo.refreshScenes().join()
        assertEquals(secondRevision, repo.state.value.scenesRevision)
        assertEquals(2, repo.state.value.scenes.length())
    }

    @Test fun queuedSceneSaveKeepsOriginalRevisionAndListAndCannotEraseBrowserAddition() = runTest {
        val server = Server()
        val repo = CompanionRepository(CompanionClient(server), Store(), backgroundScope, UnconfinedTestDispatcher(testScheduler), preloadManagement = false)
        repo.ready.join(); repo.selectDevice("a").join(); repo.refreshScenes().join()
        val submitted = JSONArray(repo.state.value.scenes.toString())
        submitted.getJSONObject(0).put("name", "Edited on phone")
        server.keyGate = CompletableDeferred()
        val barrier = repo.remoteAction("home")
        runCurrent()
        val save = repo.saveScenes(submitted)
        server.addBrowserSchedule(); repo.refreshScenes().join()
        submitted.put(JSONObject().put("id", "caller-mutation"))
        server.keyGate!!.complete(Unit); barrier.join(); save.join()
        assertEquals(firstRevision, server.writes.single().optString("expectedRevision"))
        assertEquals(1, server.writes.single().getJSONArray("scenes").length())
        assertEquals(2, server.scenes.length())
        assertEquals("Browser schedule", server.scenes.getJSONObject(1).getString("name"))
        assertEquals("stale_revision", repo.state.value.error!!.code)
        assertEquals(409, repo.state.value.error!!.status)
        assertNull(repo.state.value.lastResult)
        assertEquals(secondRevision, repo.state.value.scenesRevision)
    }

    @Test fun explicitEditorRevisionRemainsOldEvenAfterBackgroundRefresh() = runTest {
        val server = Server()
        val repo = CompanionRepository(CompanionClient(server), Store(), backgroundScope, UnconfinedTestDispatcher(testScheduler), preloadManagement = false)
        repo.ready.join(); repo.selectDevice("a").join(); repo.refreshScenes().join()
        val baseline = repo.state.value.scenesRevision
        val draft = JSONArray(repo.state.value.scenes.toString())
        server.addBrowserSchedule(); repo.refreshScenes().join()
        repo.saveScenes(draft, baseline).join()
        assertEquals(firstRevision, server.writes.single().optString("expectedRevision"))
        assertEquals("stale_revision", repo.state.value.error!!.code)
        assertEquals(2, server.scenes.length())
    }

    @Test fun unversionedEditorCannotSaveAfterTheServerAdvertisesRevisions() = runTest {
        val server = Server()
        val repo = CompanionRepository(CompanionClient(server), Store(), backgroundScope, UnconfinedTestDispatcher(testScheduler), preloadManagement = false)
        repo.ready.join(); repo.selectDevice("a").join(); repo.refreshScenes().join()
        repo.writeScenes(JSONArray(repo.state.value.scenes.toString()), expectedRevision = null).join()
        assertTrue("A known revision must not be silently omitted", server.writes.isEmpty())
        assertNotNull(repo.state.value.error)
        assertNull(repo.state.value.lastResult)
    }

    @Test fun legacyScenesResponseClearsRevisionAndOmitsExpectedRevisionOnSave() = runTest {
        val server = Server()
        val repo = CompanionRepository(CompanionClient(server), Store(), backgroundScope, UnconfinedTestDispatcher(testScheduler), preloadManagement = false)
        repo.ready.join(); repo.selectDevice("a").join(); repo.refreshScenes().join()
        server.revision = null; repo.refreshScenes().join()
        assertNull(repo.state.value.scenesRevision)
        repo.saveScenes(JSONArray(repo.state.value.scenes.toString())).join()
        assertFalse(server.writes.single().has("expectedRevision"))
        assertNull(repo.state.value.error)
    }
}
