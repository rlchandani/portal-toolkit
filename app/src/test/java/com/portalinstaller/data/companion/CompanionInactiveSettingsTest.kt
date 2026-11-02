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
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompanionInactiveSettingsTest {
    private val session = CompanionSession(CompanionDevice("a", "A", "http://a:8723"), "fixture-token")

    /** Display hides inactive children; only explicit domain preflight exposes them. */
    private class Server : CompanionTransport {
        val calls = mutableListOf<CompanionRequest>()
        var enabled = false
        var shuffle = false
        var interval = 45
        var legacy = false
        var postGate: CompletableDeferred<Unit>? = null
        val posts get() = calls.filter { it.method == "POST" }
        override suspend fun execute(request: CompanionRequest): CompanionResponse {
            calls += request
            val url = request.url.toHttpUrl()
            if (url.encodedPath.endsWith("/companion"))
                return CompanionResponse(200, """{"ok":true,"deviceId":"a"}""")
            if (request.method == "POST") {
                postGate?.await()
                val patch = JSONObject(request.body!!).getJSONObject("values")
                if (patch.has("enabled")) enabled = patch.getBoolean("enabled")
                if (patch.has("shuffle")) shuffle = patch.getBoolean("shuffle")
                if (patch.has("intervalSec")) interval = patch.getInt("intervalSec")
                return CompanionResponse(200, JSONObject().put("ok", true)
                    .put("applied", JSONArray(patch.keys().asSequence().toList()))
                    .put("domain", domain(false)).toString())
            }
            val inactive = !legacy && url.queryParameter("domain") == "screensaver" &&
                url.queryParameter("includeInactive") == "1"
            return CompanionResponse(200, JSONObject().put("ok", true).put("settings",
                JSONObject().put("domains", JSONArray().put(domain(inactive)))).toString())
        }
        private fun domain(inactive: Boolean) = JSONObject().put("id", "screensaver")
            .put("title", "Screensaver").put("controls", JSONArray().apply {
                put(JSONObject().put("key", "enabled").put("type", "bool").put("value", enabled))
                if (enabled || inactive) {
                    put(JSONObject().put("key", "shuffle").put("type", "bool").put("value", shuffle))
                    put(JSONObject().put("key", "intervalSec").put("type", "int")
                        .put("value", interval).put("min", 5).put("max", 600))
                    put(JSONObject().put("key", "customFlag").put("type", "bool")
                        .put("secret", true).put("value", false))
                }
            })
    }

    @Test fun disabledChildWriteUsesInactiveSchemaWithoutEnablingMaster() = runTest {
        val server = Server()
        var operation: GroupOperation? = null
        val client = CompanionClient(server)
        val group = CompanionGroupController(client, backgroundScope,
            UnconfinedTestDispatcher(testScheduler), { true }, { operation = it.single() })
        group.settings(CompanionTargets(listOf(session)), "screensaver",
            JSONObject().put("shuffle", true)) {}.join()
        assertEquals(GroupWorkStatus.SUCCEEDED, operation!!.items.single().status)
        assertEquals(1, server.posts.size)
        assertTrue(server.shuffle)
        assertFalse(server.enabled)
        val preflight = server.calls.single { it.method == "GET" && it.url.toHttpUrl().encodedPath.endsWith("/settings") }
        assertEquals("screensaver", preflight.url.toHttpUrl().queryParameter("domain"))
        assertEquals("1", preflight.url.toHttpUrl().queryParameter("includeInactive"))
        assertEquals(setOf("enabled"), CompanionParser.settings(client.get(session, "settings"))
            .single().controls.map { it.key }.toSet())
    }

    @Test fun combinedEnableAndHiddenChildPatchIsOneValidatedPost() = runTest {
        val server = Server()
        var operation: GroupOperation? = null
        val group = CompanionGroupController(CompanionClient(server), backgroundScope,
            UnconfinedTestDispatcher(testScheduler), { true }, { operation = it.single() })
        group.settings(CompanionTargets(listOf(session)), "screensaver",
            JSONObject().put("enabled", true).put("intervalSec", 75)) {}.join()
        assertEquals(GroupWorkStatus.SUCCEEDED, operation!!.items.single().status)
        assertEquals(1, server.posts.size)
        assertTrue(server.enabled)
        assertEquals(75, server.interval)
        assertEquals(setOf("enabled", "intervalSec"), JSONObject(server.posts.single().body!!)
            .getJSONObject("values").keys().asSequence().toSet())
    }

    @Test fun invalidHiddenRangeCannotPartiallyEnableParent() = runTest {
        val server = Server()
        var operation: GroupOperation? = null
        val group = CompanionGroupController(CompanionClient(server), backgroundScope,
            UnconfinedTestDispatcher(testScheduler), { true }, { operation = it.single() })
        group.settings(CompanionTargets(listOf(session)), "screensaver",
            JSONObject().put("enabled", true).put("intervalSec", 601)) {}.join()
        assertEquals(GroupWorkStatus.FAILED, operation!!.items.single().status)
        assertTrue(operation!!.items.single().detail!!.contains("Unsupported value"))
        assertTrue(server.posts.isEmpty())
        assertFalse(server.enabled)
        assertEquals(45, server.interval)
    }

    @Test fun unsupportedHiddenKeyStillRejectsBeforePost() = runTest {
        val server = Server()
        var operation: GroupOperation? = null
        val group = CompanionGroupController(CompanionClient(server), backgroundScope,
            UnconfinedTestDispatcher(testScheduler), { true }, { operation = it.single() })
        group.settings(CompanionTargets(listOf(session)), "screensaver",
            JSONObject().put("missing", true)) {}.join()
        assertEquals(GroupWorkStatus.FAILED, operation!!.items.single().status)
        assertTrue(server.posts.isEmpty())
    }

    @Test fun inactiveTargetMetadataStillPreventsActiveOnlyBroadcast() = runTest {
        val server = Server()
        var operation: GroupOperation? = null
        val group = CompanionGroupController(CompanionClient(server), backgroundScope,
            UnconfinedTestDispatcher(testScheduler), { true }, { operation = it.single() })
        group.settings(CompanionTargets(listOf(session)), "screensaver",
            JSONObject().put("customFlag", true)) {}.join()
        assertEquals(GroupWorkStatus.FAILED, operation!!.items.single().status)
        assertTrue(operation!!.items.single().detail!!.contains("active-Portal"))
        assertTrue(server.posts.isEmpty())
    }

    @Test fun inactivePreflightRemainsInsidePerRoomFifo() = runTest {
        val server = Server().apply { postGate = CompletableDeferred() }
        val group = CompanionGroupController(CompanionClient(server), backgroundScope,
            UnconfinedTestDispatcher(testScheduler), { true }, {})
        val first = group.settings(CompanionTargets(listOf(session)), "screensaver",
            JSONObject().put("enabled", true)) {}
        val second = group.settings(CompanionTargets(listOf(session)), "screensaver",
            JSONObject().put("shuffle", true)) {}
        runCurrent()
        assertEquals(1, server.posts.size)
        assertEquals(1, server.calls.count { it.url.toHttpUrl().encodedPath.endsWith("/settings") && it.method == "GET" })
        server.postGate!!.complete(Unit)
        first.join(); second.join()
        assertEquals(2, server.posts.size)
        assertTrue(server.enabled)
        assertTrue(server.shuffle)
    }

    @Test fun legacyPeerOmittingInactiveMetadataFailsWithoutPartialWrite() = runTest {
        val server = Server().apply { legacy = true }
        var operation: GroupOperation? = null
        val group = CompanionGroupController(CompanionClient(server), backgroundScope,
            UnconfinedTestDispatcher(testScheduler), { true }, { operation = it.single() })
        group.settings(CompanionTargets(listOf(session)), "screensaver",
            JSONObject().put("enabled", true).put("shuffle", true)) {}.join()
        assertEquals(GroupWorkStatus.FAILED, operation!!.items.single().status)
        assertTrue(server.posts.isEmpty())
        assertFalse(server.enabled)
    }
}
