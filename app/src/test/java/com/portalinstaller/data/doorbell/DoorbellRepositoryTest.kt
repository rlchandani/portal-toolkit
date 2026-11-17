/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.doorbell

import com.portalinstaller.data.companion.CompanionClient
import com.portalinstaller.data.companion.CompanionDevice
import com.portalinstaller.data.companion.CompanionRequest
import com.portalinstaller.data.companion.CompanionResponse
import com.portalinstaller.data.companion.CompanionSession
import com.portalinstaller.data.companion.CompanionSessionStore
import com.portalinstaller.data.companion.CompanionTransport
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reads every paired Portal's /remote/doorbell; offline or older Portals add nothing. */
class DoorbellRepositoryTest {
    private val kitchen = CompanionSession(CompanionDevice("kitchen", "Kitchen", "http://kitchen:8723"), "t1")
    private val den = CompanionSession(CompanionDevice("den", "Den", "http://den:8723"), "t2")
    private val store = object : CompanionSessionStore {
        override suspend fun load() = listOf(kitchen, den)
        override suspend fun save(sessions: List<CompanionSession>) {}
    }

    private class Http(val handler: (CompanionRequest) -> CompanionResponse) : CompanionTransport {
        val urls = mutableListOf<String>()
        override suspend fun execute(request: CompanionRequest): CompanionResponse { urls += request.url; return handler(request) }
    }

    private fun events(vararg ids: String) = CompanionResponse(200, JSONObject().put("ok", true).put("events",
        JSONArray(ids.map { JSONObject().put("id", it).put("kind", "ring").put("title", "Someone’s at the door")
            .put("camera", "Front door").put("room", "Kitchen").put("ageMs", 1_000) })).toString())

    @Test fun readsEveryPortalAndMergesById() = runTest {
        val http = Http { r -> if (r.url.startsWith("http://kitchen")) events("aaaaaaaa") else events("aaaaaaaa", "bbbbbbbb") }
        val reports = DoorbellRepository(CompanionClient(http), store).read()
        assertEquals(2, reports.size)
        assertTrue(http.urls.all { it.endsWith("/remote/doorbell") })
        assertEquals(listOf("aaaaaaaa", "bbbbbbbb"), DoorbellBannerPolicy.merge(reports).map { it.id }.sorted())
    }

    @Test fun olderOrOfflinePortalsAreSkipped() = runTest {
        val http = Http { r ->
            if (r.url.startsWith("http://kitchen")) CompanionResponse(404, JSONObject().put("ok", false).put("error", "not_found").toString())
            else throw IOException("offline")
        }
        assertTrue(DoorbellRepository(CompanionClient(http), store).read().isEmpty())
    }
}
