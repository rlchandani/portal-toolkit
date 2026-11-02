/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.companion

import java.io.ByteArrayInputStream
import java.nio.file.Files
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompanionGroupTest {
    private fun session(id: String) = CompanionSession(CompanionDevice(id, id,
        "http://$id:8723", photoFolder = "/sdcard/$id"), "token-$id")
    private inner class Store(ids: List<String> = listOf("a", "b")) : CompanionSessionStore {
        var saved = ids.map { session(it) }
        var selected: Set<String>? = null
        override suspend fun load() = saved
        override suspend fun save(sessions: List<CompanionSession>) { saved = sessions }
        override suspend fun loadSelectedIds() = selected
        override suspend fun saveSelectedIds(ids: Set<String>) { selected = ids }
    }
    private class Http : CompanionTransport {
        val calls = mutableListOf<CompanionRequest>()
        val writes get() = calls.filter { it.method == "POST" }
        var handler: suspend (CompanionRequest) -> CompanionResponse = { request -> response(request) }
        override suspend fun execute(request: CompanionRequest): CompanionResponse { calls += request; return handler(request) }
    }
    private fun keys(request: CompanionRequest) = JSONObject(request.body!!).getJSONObject("values").keys().asSequence().toSet()
    private fun patch(value: Boolean) = JSONObject().put("shuffle", value)
    private fun source(name: String, opened: () -> Unit = {}, disposed: () -> Unit = {}) = PhotoUploadSource(name) {
        CompanionUpload(name, "image/jpeg", 3, dispose = disposed, open = { opened(); ByteArrayInputStream(byteArrayOf(1, 2, 3)) })
    }

    @Test fun firstGroupWriteUsesAllSavedPortalsAndOnlyTheChangedKey() = runTest {
        val http = Http()
        val repo = CompanionRepository(CompanionClient(http), Store(), backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repo.ready.join(); repo.selectDevice("a").join()
        repo.writeSelectedSettings("screensaver", patch(true)).join()
        assertEquals(setOf("http://a:8723/remote/settings", "http://b:8723/remote/settings"), http.writes.map { it.url }.toSet())
        http.writes.forEach { assertEquals(setOf("shuffle"), keys(it)) }
        assertEquals(2, repo.state.value.groupOperations.single().items.count { it.status == GroupWorkStatus.SUCCEEDED })
        assertNull(repo.state.value.lastResult)
    }

    @Test fun targetSelectionAndActiveReadRemainIndependentAcrossRestartAndDisconnect() = runTest {
        val store = Store()
        val http = Http()
        val repo = CompanionRepository(CompanionClient(http), store, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repo.ready.join()
        assertEquals(setOf("a", "b"), repo.state.value.selectedTargetIds)
        repo.selectTargets(setOf("b")).join()
        repo.selectDevice("a").join()
        http.calls.clear()
        repo.refreshSettings().join()
        assertTrue(http.calls.all { it.url.contains("//a:") })
        assertEquals(setOf("b"), repo.state.value.selectedTargetIds)
        repo.disconnect()
        assertEquals(setOf("b"), repo.state.value.selectedTargetIds)
        val restarted = CompanionRepository(CompanionClient(http), store, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        restarted.ready.join()
        assertEquals(setOf("b"), restarted.state.value.selectedTargetIds)
        restarted.selectTargets(emptySet()).join()
        val empty = CompanionRepository(CompanionClient(http), store, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        empty.ready.join()
        empty.writeSelectedSettings("screensaver", patch(true)).join()
        assertTrue(empty.state.value.groupOperations.isEmpty())
        assertNotNull(empty.state.value.error)
    }

    @Test fun privateSelectionStorePersistsExplicitEmptyAndFiltersForgottenIdsWithoutCredentials() = runTest {
        val directory = Files.createTempDirectory("companion-group-private").toFile()
        try {
            val store = FileCompanionSessionStore(directory)
            store.save(listOf(session("a"), session("b")))
            assertNull(store.loadSelectedIds())
            store.saveSelectedIds(setOf("b", "forgotten"))
            val repo = CompanionRepository(CompanionClient(Http()), FileCompanionSessionStore(directory), backgroundScope, UnconfinedTestDispatcher(testScheduler))
            repo.ready.join()
            assertEquals(setOf("b"), repo.state.value.selectedTargetIds)
            val persisted = directory.resolve("companion-targets.json").readText()
            assertFalse(persisted.contains("token-")); assertFalse(persisted.contains("http"))
            repo.selectTargets(emptySet()).join()
            assertEquals(emptySet<String>(), FileCompanionSessionStore(directory).loadSelectedIds())
            assertFalse(directory.listFiles()!!.any { it.name.endsWith(".tmp") })
        } finally { directory.deleteRecursively() }
    }

    @Test fun settingsCaptureTargetRosterAndPayloadBeforeSelectionOrCallerMutation() = runTest {
        val http = Http()
        val repo = CompanionRepository(CompanionClient(http), Store(), backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repo.ready.join(); repo.selectDevice("a").join()
        val gate = CompletableDeferred<Unit>()
        http.handler = { request -> if (request.method == "POST") gate.await(); response(request) }
        val values = patch(true)
        val work = repo.writeSelectedSettings("screensaver", values)
        runCurrent()
        repo.selectTargets(setOf("a")).join(); repo.selectDevice("b").join()
        values.put("name", "Do not clone")
        gate.complete(Unit); work.join()
        assertEquals(setOf("a", "b"), repo.state.value.groupOperations.single().items.map { it.targetId }.toSet())
        assertEquals(2, http.writes.size)
        http.writes.forEach { assertEquals(setOf("shuffle"), keys(it)) }
        assertEquals("b", repo.state.value.activeDeviceId)
        assertNull(repo.state.value.lastResult)
    }

    @Test fun partialSettingsFailureRetriesOnlyFailedTargetAndUnappliedKeys() = runTest {
        val http = Http()
        val repo = CompanionRepository(CompanionClient(http), Store(), backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repo.ready.join()
        http.handler = { request -> if (request.method == "POST" && request.url.contains("//b:"))
            CompanionResponse(200, """{"ok":true,"domain":{"id":"screensaver"},"applied":["shuffle"]}""") else response(request) }
        repo.writeSelectedSettings("screensaver", patch(true).put("intervalSec", 30)).join()
        val op = repo.state.value.groupOperations.single()
        assertEquals(listOf(GroupWorkStatus.SUCCEEDED, GroupWorkStatus.FAILED), op.items.map { it.status })
        http.calls.clear(); http.handler = { response(it) }
        repo.selectTargets(setOf("a")).join()
        repo.retryGroupOperation(op.id).join()
        assertEquals(1, http.writes.size)
        assertTrue(http.writes.single().url.contains("//b:"))
        assertEquals(setOf("intervalSec"), keys(http.writes.single()))
        assertTrue(repo.state.value.groupOperations.single().items.all { it.status == GroupWorkStatus.SUCCEEDED })
    }

    @Test fun rapidSettingsEditsKeepPerTargetOrderAndAtMostTwoInFlight() = runTest {
        val http = Http()
        val repo = CompanionRepository(CompanionClient(http), Store(listOf("a", "b", "c")), backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repo.ready.join()
        val gate = CompletableDeferred<Unit>()
        var inFlight = 0; var peak = 0
        val sent = mutableMapOf<String, MutableList<Boolean>>()
        http.handler = { request ->
            if (request.method == "POST") {
                inFlight++; peak = maxOf(peak, inFlight)
                sent.getOrPut(request.url) { mutableListOf() }.add(JSONObject(request.body!!).getJSONObject("values").getBoolean("shuffle"))
                gate.await(); inFlight--
            }
            response(request)
        }
        val first = repo.writeSelectedSettings("screensaver", patch(true))
        val second = repo.writeSelectedSettings("screensaver", patch(false))
        runCurrent()
        assertEquals(2, http.writes.size)
        gate.complete(Unit); first.join(); second.join()
        assertEquals(2, peak)
        assertEquals(3, sent.size)
        sent.values.forEach { assertEquals(listOf(true, false), it) }
    }

    @Test fun offlineUnsupportedAndWrongIdentityTargetsFailSeparatelyWithoutPost() = runTest {
        val http = Http()
        val repo = CompanionRepository(CompanionClient(http), Store(listOf("a", "b", "c")), backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repo.ready.join()
        http.handler = { request -> when {
            request.url.contains("//a:") -> throw java.io.IOException("private connection diagnostics")
            request.url.contains("//b:") -> CompanionResponse(200, """{"ok":true,"settings":{"domains":[]}}""")
            else -> CompanionResponse(200, """{"ok":true,"deviceId":"different"}""")
        } }
        repo.writeSelectedSettings("screensaver", patch(true)).join()
        assertTrue(http.writes.isEmpty())
        val items = repo.state.value.groupOperations.single().items
        assertTrue(items.all { it.status == GroupWorkStatus.FAILED })
        assertTrue(items[0].detail!!.contains("Offline"))
        assertTrue(items[1].detail!!.contains("Unsupported"))
        assertTrue(items[2].detail!!.contains("different"))
        assertFalse(items.toString().contains("private connection diagnostics"))
    }

    @Test fun photoCaptureUsesOwnExactFoldersFreshStreamsAndFailureOnlyRetry() = runTest {
        val store = Store()
        store.saved = store.saved.map { CompanionSession(it.device.copy(photoFolder = "/sdcard/${it.device.id} "), it.token) }
        val http = Http()
        val repo = CompanionRepository(CompanionClient(http), store, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repo.ready.join()
        val targets = repo.captureSelectedTargets()
        repo.selectTargets(setOf("a")).join()
        var opened = 0; var disposed = 0
        val sources = listOf(source("one.jpg", { opened++ }, { disposed++ }), source("two.jpg", { opened++ }, { disposed++ }))
        http.handler = { request ->
            request.upload?.open?.invoke()?.use { assertEquals(3, it.readBytes().size) }
            if (request.upload != null && request.url.contains("//b:") && request.url.contains("two.jpg"))
                CompanionResponse(503, """{"ok":false,"error":"unavailable"}""") else response(request)
        }
        repo.uploadSelectedPhotos(sources, targets).join()
        assertEquals(4, opened); assertEquals(4, disposed)
        val op = repo.state.value.groupOperations.single()
        assertEquals(3, op.items.count { it.status == GroupWorkStatus.SUCCEEDED })
        assertEquals(1, op.items.count { it.status == GroupWorkStatus.FAILED })
        assertEquals(listOf("/sdcard/a ", "/sdcard/a ", "/sdcard/b ", "/sdcard/b "), op.items.map { it.folder })
        http.calls.clear()
        // A later browse choice must not redirect the retry's destination.
        repo.selectDevice("b").join(); repo.listPhotos("/sdcard/NewFolder").join()
        http.handler = { request -> request.upload?.open?.invoke()?.close(); response(request) }
        repo.retryGroupOperation(op.id).join()
        val upload = http.writes.single { it.upload != null }
        assertTrue(upload.url.contains("//b:")); assertTrue(upload.url.contains("two.jpg"))
        assertEquals("/sdcard/b ", upload.url.toHttpUrl().queryParameter("path"))
        assertEquals(5, opened); assertEquals(5, disposed)
        assertFalse(http.writes.any { it.url.endsWith("/sources") })
    }

    @Test fun uploadFanoutIsBoundedAndCancelLeavesPostedRequestsConfirmed() = runTest {
        val http = Http()
        val repo = CompanionRepository(CompanionClient(http), Store(listOf("a", "b", "c")), backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repo.ready.join()
        val gate = CompletableDeferred<Unit>()
        var inFlight = 0; var peak = 0
        http.handler = { request -> if (request.upload != null) { inFlight++; peak = maxOf(peak, inFlight); gate.await(); inFlight-- }; response(request) }
        val work = repo.uploadSelectedPhotos(listOf(source("one.jpg"), source("two.jpg")))
        runCurrent()
        assertEquals(2, http.writes.size)
        val id = repo.state.value.groupOperations.single().id
        repo.cancelGroupOperation(id)
        gate.complete(Unit); work.join()
        val op = repo.state.value.groupOperations.single()
        assertEquals(2, peak)
        assertEquals(2, op.items.count { it.status == GroupWorkStatus.SUCCEEDED })
        assertEquals(4, op.items.count { it.status == GroupWorkStatus.CANCELLED })
        repo.retryGroupOperation(id).join()
        assertEquals(2, http.writes.size)
    }

    @Test fun credentialsAndRenameAreRejectedByGroupBoundaryWhileSourcesStayActive() = runTest {
        val http = Http()
        val repo = CompanionRepository(CompanionClient(http), Store(), backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repo.ready.join(); repo.selectDevice("a").join(); http.calls.clear()
        repo.writeSelectedSettings("immortal", JSONObject().put("deviceName", "Renamed")).join()
        repo.writeSelectedSettings("fleet", JSONObject().put("name", "Renamed")).join()
        repo.writeSelectedSettings("screensaver", JSONObject().put("immichKey", "private")).join()
        assertTrue(http.writes.isEmpty())
        repo.writeSources(JSONObject().put("source", "default")).join()
        assertEquals(1, http.writes.size)
        assertTrue(http.writes.single().url.contains("//a:"))
    }

    @Test fun forgottenPairingsCannotReceiveQueuedWorkOrRetryWithReplacementSession() = runTest {
        val http = Http()
        val repo = CompanionRepository(CompanionClient(http), Store(listOf("a", "b", "c")), backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repo.ready.join()
        val gate = CompletableDeferred<Unit>()
        http.handler = { request -> if (request.method == "POST") gate.await(); response(request) }
        val work = repo.writeSelectedSettings("screensaver", patch(true))
        runCurrent(); repo.forgetDevice("c").join()
        gate.complete(Unit); work.join()
        assertEquals(2, http.writes.size)
        assertEquals(GroupWorkStatus.FAILED, repo.state.value.groupOperations.single().items.last().status)
    }

    @Test fun dismissedNewerEditStillPreventsAnOlderFailedRetryOverwritingIt() = runTest {
        val http = Http()
        val repo = CompanionRepository(CompanionClient(http), Store(listOf("a")), backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repo.ready.join()
        http.handler = { request -> if (request.method == "POST") CompanionResponse(200, """{"ok":true,"domain":{"id":"screensaver"},"applied":[]}""") else response(request) }
        repo.writeSelectedSettings("screensaver", patch(true)).join()
        val old = repo.state.value.groupOperations.single().id
        http.handler = { response(it) }
        repo.writeSelectedSettings("screensaver", patch(false)).join()
        val latest = repo.state.value.groupOperations.last().id
        repo.dismissGroupOperation(latest)
        http.calls.clear()
        repo.retryGroupOperation(old).join()
        assertTrue("An old retry must not undo a newer confirmed edit", http.writes.isEmpty())
        assertEquals(GroupWorkStatus.FAILED, repo.state.value.groupOperations.single().items.single().status)
    }

    @Test fun corruptTargetSelectionKeepsPairingsVisibleAndFailsClosedUntilChosenAgain() = runTest {
        val directory = Files.createTempDirectory("companion-group-corrupt").toFile()
        try {
            val store = FileCompanionSessionStore(directory)
            store.save(listOf(session("a"), session("b")))
            directory.resolve("companion-targets.json").writeText("{corrupt")
            val repo = CompanionRepository(CompanionClient(Http()), store, backgroundScope, UnconfinedTestDispatcher(testScheduler))
            repo.ready.join()
            assertEquals(setOf("a", "b"), repo.state.value.devices.map { it.id }.toSet())
            assertTrue(repo.state.value.selectedTargetIds.isEmpty())
            assertNotNull(repo.state.value.error)
            repo.selectTargets(setOf("b")).join()
            repo.writeSelectedSettings("screensaver", patch(true)).join()
            assertEquals("b", repo.state.value.groupOperations.single().items.single().targetId)
        } finally { directory.deleteRecursively() }
    }

    @Test fun legacyIdentityUpgradeRetainsTheExplicitSelectedRoomAcrossRestart() = runTest {
        val store = Store(listOf("a"))
        store.saved = listOf(CompanionSession(session("a").device.copy(id = "legacy:a"), "token-a"))
        store.selected = setOf("legacy:a")
        val http = Http()
        http.handler = { request -> if (request.url.endsWith("/companion"))
            CompanionResponse(200, """{"ok":true,"deviceId":"stable-a","name":"Family Area","capabilities":{}}""") else response(request) }
        val repo = CompanionRepository(CompanionClient(http), store, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repo.ready.join(); repo.selectDevice("legacy:a").join()
        assertEquals(setOf("stable-a"), repo.state.value.selectedTargetIds)
        val restarted = CompanionRepository(CompanionClient(http), store, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        restarted.ready.join()
        assertEquals(setOf("stable-a"), restarted.state.value.selectedTargetIds)
    }

    @Test fun selectionMadeDuringStartupIsAppliedToTheLoadedRoster() = runTest {
        val loaded = CompletableDeferred<Unit>()
        val store = object : CompanionSessionStore {
            var chosen: Set<String>? = null
            override suspend fun load(): List<CompanionSession> { loaded.await(); return listOf(session("a"), session("b")) }
            override suspend fun save(sessions: List<CompanionSession>) {}
            override suspend fun loadSelectedIds() = chosen
            override suspend fun saveSelectedIds(ids: Set<String>) { chosen = ids }
        }
        val repo = CompanionRepository(CompanionClient(Http()), store, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        val selecting = repo.selectTargets(setOf("b"))
        loaded.complete(Unit); selecting.join()
        assertEquals(setOf("b"), repo.state.value.selectedTargetIds)
        assertEquals(setOf("b"), store.chosen)
    }

    @Test fun uploadProgressCoalescesChunkPublicationsPerItemButFinishesWithExactBytes() = runTest {
        val published = mutableListOf<GroupWorkItem>()
        val length = 1024L * 1024
        val http = Http()
        http.handler = { request ->
            if (request.upload != null) {
                repeat(32) { chunk ->
                    delay(10)
                    request.onUploadProgress!!.invoke((chunk + 1) * 32L * 1024)
                }
                confirmedUpload(request, length)
            } else response(request)
        }
        val coordinator = CompanionGroupController(CompanionClient(http), backgroundScope,
            UnconfinedTestDispatcher(testScheduler), { true },
            publish = { ops -> published += ops.single().items.single() },
            progressClock = { testScheduler.currentTime })
        val source = PhotoUploadSource("large.jpg") {
            CompanionUpload("large.jpg", "image/jpeg", length, open = { ByteArrayInputStream(byteArrayOf()) })
        }
        coordinator.photos(CompanionTargets(listOf(session("a"))), listOf(source)) {}.join()
        assertEquals(listOf(0L, 327680L, 655360L, 983040L),
            published.filter { it.status == GroupWorkStatus.SENDING }.map { it.bytesSent })
        assertEquals(length, published.last().bytesSent)
        assertEquals(GroupWorkStatus.SUCCEEDED, published.last().status)
        assertEquals(listOf(GroupWorkStatus.QUEUED, GroupWorkStatus.CHECKING,
            GroupWorkStatus.SENDING, GroupWorkStatus.SUCCEEDED), published.map { it.status }.distinct())
    }

    @Test fun shortUploadsFlushEachTargetsExactConfirmedOrFailedBytesWithoutChunkEmissions() = runTest {
        val published = mutableListOf<List<GroupWorkItem>>()
        val length = 65539L
        val http = Http()
        http.handler = { request ->
            if (request.upload != null) {
                delay(10); request.onUploadProgress!!.invoke(32768)
                delay(10); request.onUploadProgress!!.invoke(65536)
                if (request.url.contains("//b:")) CompanionResponse(503, """{"ok":false,"error":"unavailable"}""")
                else confirmedUpload(request, length)
            } else response(request)
        }
        val coordinator = CompanionGroupController(CompanionClient(http), backgroundScope,
            UnconfinedTestDispatcher(testScheduler), { true },
            publish = { ops -> published += ops.single().items },
            progressClock = { testScheduler.currentTime })
        val source = PhotoUploadSource("short.jpg") {
            CompanionUpload("short.jpg", "image/jpeg", length, open = { ByteArrayInputStream(byteArrayOf()) })
        }
        coordinator.photos(CompanionTargets(listOf(session("a"), session("b"))), listOf(source)) {}.join()
        val final = published.last().associateBy { it.targetId }
        assertEquals(GroupWorkStatus.SUCCEEDED, final.getValue("a").status)
        assertEquals(length, final.getValue("a").bytesSent)
        assertEquals(GroupWorkStatus.FAILED, final.getValue("b").status)
        assertEquals(65536L, final.getValue("b").bytesSent)
        assertTrue(published.flatten().filter { it.status == GroupWorkStatus.SENDING }.all { it.bytesSent == 0L })
    }

    @Test fun progressCoalescingResetsForFailedOnlyRetryAndCannotReusePreviousBytes() = runTest {
        val published = mutableListOf<GroupWorkItem>()
        val length = 100000L
        val http = Http()
        var attempt = 0
        var oldProgress: ((Long) -> Unit)? = null
        http.handler = { request ->
            if (request.upload != null) {
                attempt++
                delay(10)
                if (attempt == 1) oldProgress = request.onUploadProgress else oldProgress!!.invoke(900000)
                request.onUploadProgress!!.invoke(if (attempt == 1) 30000 else 1000)
                if (attempt == 1) CompanionResponse(503, """{"ok":false,"error":"unavailable"}""")
                else confirmedUpload(request, length)
            } else response(request)
        }
        var operation: GroupOperation? = null
        val coordinator = CompanionGroupController(CompanionClient(http), backgroundScope,
            UnconfinedTestDispatcher(testScheduler), { true },
            publish = { ops -> operation = ops.single(); published += ops.single().items.single() },
            progressClock = { testScheduler.currentTime })
        val source = PhotoUploadSource("retry.jpg") {
            CompanionUpload("retry.jpg", "image/jpeg", length, open = { ByteArrayInputStream(byteArrayOf()) })
        }
        coordinator.photos(CompanionTargets(listOf(session("a"))), listOf(source)) {}.join()
        assertEquals(30000L, operation!!.items.single().bytesSent)
        assertTrue(operation!!.retryable)
        published.clear()
        advanceTimeBy(100)
        coordinator.retry(operation!!.id).join()
        assertEquals(2, attempt)
        assertEquals(listOf(0L), published.filter { it.status == GroupWorkStatus.SENDING }.map { it.bytesSent })
        assertEquals(length, published.last().bytesSent)
        assertEquals(GroupWorkStatus.SUCCEEDED, published.last().status)
        val publicationCount = published.size
        advanceTimeBy(100)
        oldProgress!!.invoke(950000)
        assertEquals("A finished attempt must ignore late callbacks", publicationCount, published.size)
        assertEquals(length, published.last().bytesSent)
    }

    @Test fun groupLedgerUsesReferenceControlAndDomainTitlesInsteadOfRegistryKeys() = runTest {
        val http = Http()
        http.handler = { request -> when {
            request.method == "POST" -> CompanionResponse(200, JSONObject().put("ok", true)
                .put("domain", JSONObject().put("id", JSONObject(request.body!!).getString("domain")))
                .put("applied", org.json.JSONArray(listOf("weatherUnit", "shuffle"))).toString())
            request.url.toHttpUrl().encodedPath.endsWith("/settings") -> CompanionResponse(200, """{"ok":true,"settings":{"domains":[
                {"id":"immortal","title":"Immortal settings","controls":[{"key":"weatherUnit","title":"Temperature unit","type":"enum","value":"c","options":[{"value":"c"},{"value":"f"}]}]},
                {"id":"screensaver","title":"Photos & screensaver","controls":[{"key":"shuffle","title":"Shuffle photos","type":"bool","value":false}]}]}}""")
            else -> response(request)
        } }
        val repo = CompanionRepository(CompanionClient(http), Store(), backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repo.ready.join(); repo.selectDevice("a").join()
        repo.writeSelectedSettings("immortal", JSONObject().put("weatherUnit", "f")).join()
        val weather = repo.state.value.groupOperations.single()
        assertEquals("Temperature unit", weather.title)
        assertTrue(weather.items.all { it.name == "Immortal settings" })
        assertTrue(weather.items.all { it.status == GroupWorkStatus.SUCCEEDED })
        assertEquals(setOf("weatherUnit"), keys(http.writes.first()))
        repo.writeSelectedSettings("screensaver", patch(true)).join()
        assertEquals("Shuffle photos", repo.state.value.groupOperations.last().title)
        assertTrue(repo.state.value.groupOperations.last().items.all { it.name == "Photos & screensaver" })
    }

    @Test fun ledgerWithoutAReferenceSchemaFallsBackToTheHumanizedDomain() = runTest {
        val repo = CompanionRepository(CompanionClient(Http()), Store(), backgroundScope, UnconfinedTestDispatcher(testScheduler), preloadManagement = false)
        repo.ready.join()
        repo.writeSelectedSettings("screensaver", patch(true)).join()
        assertEquals("Screensaver", repo.state.value.groupOperations.single().title)
        assertTrue(repo.state.value.groupOperations.single().items.all { it.name == "Screensaver" })
        assertTrue(repo.state.value.groupOperations.single().items.all { it.status == GroupWorkStatus.SUCCEEDED })
    }

    private companion object {
        fun confirmedUpload(request: CompanionRequest, length: Long = request.upload!!.length) =
            CompanionResponse(200, JSONObject().put("ok", true).put("bytes", length)
                .put("path", request.url.toHttpUrl().queryParameter("path") + "/" + request.upload!!.name).toString())
        fun response(request: CompanionRequest): CompanionResponse = CompanionResponse(200, when {
            request.upload != null -> confirmedUpload(request).body
            request.method == "POST" && request.url.endsWith("/sources") -> """{"ok":true,"applied":["source"]}"""
            request.method == "POST" -> JSONObject().put("ok", true)
                .put("domain", JSONObject().put("id", JSONObject(request.body!!).getString("domain")))
                .put("applied", org.json.JSONArray(listOf("shuffle", "intervalSec"))).toString()
            else -> """{"ok":true,"settings":{"domains":[{"id":"screensaver","controls":[{"key":"shuffle","type":"bool","value":false},{"key":"intervalSec","type":"int","min":1,"max":120,"value":15}]}]},"np":{},"files":[],"capabilities":{}}"""
        })
    }
}
