/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.companion

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompanionSettingsAcknowledgmentTest {
    private val session = CompanionSession(CompanionDevice("a", "Family", "http://a:8723"), "fixture-token")
    private val patch get() = JSONObject().put("shuffle", true)
    private val body get() = JSONObject().put("domain", "screensaver").put("values", patch)
    private val valid = """{"ok":true,"domain":{"id":"screensaver"},"applied":["shuffle"]}"""
    private class Store(private val session: CompanionSession) : CompanionSessionStore {
        override suspend fun load() = listOf(session)
        override suspend fun save(sessions: List<CompanionSession>) = Unit
    }
    private class Http(var reply: CompanionResponse) : CompanionTransport {
        var posts = 0
        override suspend fun execute(request: CompanionRequest): CompanionResponse {
            if (request.method == "POST") { posts++; return reply }
            return CompanionResponse(200, """{"ok":true,"deviceId":"a","capabilities":{},"np":{},"settings":{"domains":[{"id":"screensaver","title":"Photos & screensaver","controls":[{"key":"shuffle","type":"bool","value":false}]}]}}""")
        }
    }
    private suspend fun error(client: CompanionClient): CompanionError {
        try { client.post(session, "settings", body) }
        catch (e: CompanionException) { return e.error }
        fail("An uncertain settings write was reported as confirmed.")
        throw AssertionError()
    }
    @Test fun wrongDomainCannotConfirmMatchingKeys() = runTest {
        val http = Http(CompanionResponse(200, valid.replace("screensaver", "immortal")))
        assertEquals("settings_unconfirmed", error(CompanionClient(http)).code)
        assertEquals(1, http.posts)
    }
    @Test fun missingOrMalformedDomainIdentityIsUnconfirmed() = runTest {
        for (domain in listOf("", ""","domain":null""", ""","domain":"screensaver"""",
            ""","domain":{}""", ""","domain":{"id":123}""", ""","domain":{"id":""}""")) {
            val http = Http(CompanionResponse(200, """{"ok":true,"applied":["shuffle"]$domain}"""))
            assertEquals(domain, "settings_unconfirmed", error(CompanionClient(http)).code)
            assertEquals(1, http.posts)
        }
    }
    @Test fun stringSuccessIsUnconfirmed() = runTest {
        assertEquals("settings_unconfirmed", error(CompanionClient(Http(
            CompanionResponse(200, valid.replace("true", "\"true\""))))).code)
    }
    @Test fun malformedPositiveResponseIsUnconfirmed() = runTest {
        assertEquals("settings_unconfirmed", error(CompanionClient(Http(CompanionResponse(200, "{")))).code)
    }
    @Test fun malformedAppliedListIsUnconfirmed() = runTest {
        for (applied in listOf("", ""","applied":null""", ""","applied":false""",
            ""","applied":"shuffle"""", ""","applied":{}""", ""","applied":[123]""")) {
            assertEquals(applied, "settings_unconfirmed", error(CompanionClient(Http(
                CompanionResponse(200, """{"ok":true,"domain":{"id":"screensaver"}$applied}""")))).code)
        }
    }
    @Test fun explicitRejectionAndUnauthorizedStayRejected() = runTest {
        for (status in listOf(200, 401)) {
            val result = error(CompanionClient(Http(CompanionResponse(status, """{"ok":false,"error":"unauthorized"}"""))))
            assertEquals("unauthorized", result.code)
            assertEquals(status, result.status)
        }
    }
    @Test fun transportLossIsUnconfirmedAndCancellationPropagates() = runTest {
        assertEquals("settings_unconfirmed", error(CompanionClient { throw IOException("lost after posting") }).code)
        try {
            CompanionClient { throw CancellationException("fixture") }.post(session, "settings", body)
            fail("Cancellation was swallowed.")
        } catch (_: CancellationException) { }
    }
    @Test fun validReplyPreservesExactRequestAndSinglePost() = runTest {
        val http = Http(CompanionResponse(200, valid))
        assertEquals("screensaver", CompanionClient(http).post(session, "settings", body).getJSONObject("domain").getString("id"))
        assertEquals(1, http.posts)
    }
    @Test fun singleRoomWrongDomainRetainsReviewErrorWithoutRepeating() = runTest {
        val http = Http(CompanionResponse(200, valid.replace("screensaver", "immortal")))
        val repo = CompanionRepository(CompanionClient(http), Store(session), backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repo.ready.join(); repo.selectDevice("a").join()
        repo.writeSettings("screensaver", patch).join()
        assertEquals("settings_unconfirmed", repo.state.value.error?.code)
        assertNull(repo.state.value.lastResult)
        assertEquals(1, http.posts)
    }
    @Test fun groupMissingIdentityCannotBeRetriedAsFailed() = runTest {
        val http = Http(CompanionResponse(200, """{"ok":true,"applied":["shuffle"]}"""))
        val repo = CompanionRepository(CompanionClient(http), Store(session), backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repo.ready.join(); repo.selectDevice("a").join()
        repo.writeSelectedSettings("screensaver", patch).join()
        val operation = repo.state.value.groupOperations.single()
        assertEquals(GroupWorkStatus.UNCONFIRMED, operation.items.single().status)
        assertTrue(operation.items.single().detail?.contains("Review") == true)
        repo.retryGroupOperation(operation.id).join()
        assertEquals(1, http.posts)
    }
    @Test fun groupMissingAppliedListCannotBeRetriedAsFailed() = runTest {
        val http = Http(CompanionResponse(200, """{"ok":true,"domain":{"id":"screensaver"}}"""))
        val repo = CompanionRepository(CompanionClient(http), Store(session), backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repo.ready.join(); repo.selectDevice("a").join()
        repo.writeSelectedSettings("screensaver", patch).join()
        val operation = repo.state.value.groupOperations.single()
        assertEquals(GroupWorkStatus.UNCONFIRMED, operation.items.single().status)
        repo.retryGroupOperation(operation.id).join()
        assertEquals(1, http.posts)
    }
    @Test fun validEmptyAppliedListRetainsRejectionAndRetry() = runTest {
        val http = Http(CompanionResponse(200, """{"ok":true,"domain":{"id":"screensaver"},"applied":[]}"""))
        val repo = CompanionRepository(CompanionClient(http), Store(session), backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repo.ready.join(); repo.selectDevice("a").join()
        repo.writeSelectedSettings("screensaver", patch).join()
        val operation = repo.state.value.groupOperations.single()
        assertEquals(GroupWorkStatus.FAILED, operation.items.single().status)
        assertTrue(operation.retryable)
        assertEquals(1, http.posts)
    }
}
