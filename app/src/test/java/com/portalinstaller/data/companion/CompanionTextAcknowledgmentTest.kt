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
class CompanionTextAcknowledgmentTest {
    private val session = CompanionSession(CompanionDevice("a", "Family", "http://a:8723"), "fixture-token")
    private val draft = "First line\nCafé 🎵"
    private val body get() = JSONObject().put("text", draft).put("mode", "set")
    private class Store(private val session: CompanionSession) : CompanionSessionStore {
        override suspend fun load() = listOf(session)
        override suspend fun save(sessions: List<CompanionSession>) = Unit
    }
    private class Http(var reply: CompanionResponse) : CompanionTransport {
        val posts = mutableListOf<CompanionRequest>()
        override suspend fun execute(request: CompanionRequest): CompanionResponse {
            if (request.method == "POST") { posts += request; return reply }
            return CompanionResponse(200, """{"ok":true,"deviceId":"a","capabilities":{},"np":{}}""")
        }
    }
    private suspend fun error(client: CompanionClient): CompanionError {
        try { client.post(session, "text", body) }
        catch (e: CompanionException) { return e.error }
        fail("Unconfirmed text was reported as applied.")
        throw AssertionError()
    }
    @Test fun missingAppliedCannotConfirmTheDraft() = runTest {
        val http = Http(CompanionResponse(200, """{"ok":true}"""))
        assertEquals("text_unconfirmed", error(CompanionClient(http)).code)
        assertEquals(1, http.posts.size)
    }
    @Test fun nonBooleanAppliedCannotConfirmTheDraft() = runTest {
        for (value in listOf("\"true\"", "\"false\"", "null", "1", "{}", "[]")) {
            val http = Http(CompanionResponse(200, """{"ok":true,"applied":$value}"""))
            assertEquals(value, "text_unconfirmed", error(CompanionClient(http)).code)
            assertEquals(1, http.posts.size)
        }
    }
    @Test fun malformedPositiveRepliesAreUnconfirmed() = runTest {
        for (reply in listOf("{", "[]", "", """{"ok":"true","applied":true}""",
            """{"applied":true}""", """{"ok":null,"applied":true}""")) {
            assertEquals(reply, "text_unconfirmed",
                error(CompanionClient(Http(CompanionResponse(200, reply)))).code)
        }
    }
    @Test fun validAppliedReplyKeepsExactRequestAndOnePost() = runTest {
        val http = Http(CompanionResponse(200, """{"ok":true,"applied":true,"mode":"set"}"""))
        assertEquals(true, CompanionClient(http).post(session, "text", body).get("applied"))
        val sent = http.posts.single()
        assertEquals("POST", sent.method)
        assertEquals("http://a:8723/remote/text", sent.url)
        assertEquals(draft, JSONObject(sent.body!!).getString("text"))
        assertEquals("set", JSONObject(sent.body).getString("mode"))
    }
    @Test fun explicitRejectionUnappliedAndUnauthorizedStayRejected() = runTest {
        for ((response, code) in listOf(
            CompanionResponse(200, """{"ok":true,"applied":false}""") to "not_applied",
            CompanionResponse(200, """{"ok":false,"error":"no_accessibility"}""") to "no_accessibility",
            CompanionResponse(401, """{"ok":false,"error":"unauthorized"}""") to "unauthorized",
            CompanionResponse(503, """{"ok":false,"error":"no_accessibility"}""") to "no_accessibility",
        )) {
            val failure = error(CompanionClient(Http(response)))
            assertEquals(code, failure.code)
            assertEquals(response.status, failure.status)
        }
    }
    @Test fun transportLossIsUnconfirmedAndCancellationPropagates() = runTest {
        assertEquals("text_unconfirmed", error(CompanionClient { throw IOException("fixture lost reply") }).code)
        try {
            CompanionClient { throw CancellationException("fixture") }.post(session, "text", body)
            fail("Cancellation was swallowed.")
        } catch (_: CancellationException) { }
    }
    @Test fun repositoryCannotConfirmOrRepeatMissingApplied() = runTest {
        val http = Http(CompanionResponse(200, """{"ok":true}"""))
        val repo = CompanionRepository(CompanionClient(http), Store(session), backgroundScope,
            UnconfinedTestDispatcher(testScheduler), preloadManagement = false)
        repo.ready.join()
        repo.selectDevice("a").join()
        assertFalse(repo.sendTextConfirmed(draft))
        assertEquals("text_unconfirmed", repo.state.value.error?.code)
        assertNull(repo.state.value.lastResult)
        assertFalse(repo.state.value.busy)
        assertEquals(1, http.posts.size)
    }
}
