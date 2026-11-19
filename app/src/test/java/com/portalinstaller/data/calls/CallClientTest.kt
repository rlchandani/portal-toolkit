/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.calls

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CallClientTest {
    private val now = 1_763_500_000_000L
    private val endpoint = CallEndpoint("s1", "http://192.168.1.20:8723/", PortalSide.TOKEN)
    private val id = "0123456789abcdef"

    /** A fake Portal: verifies the request like the launcher does, then answers with [reply]. */
    private fun client(reply: (path: String, body: String, nonce: String) -> CallHttpResponse): Pair<CallClient, MutableList<String>> {
        val urls = mutableListOf<String>()
        val http = CallHttp { url, token, header, body, _ ->
            urls += url
            assertEquals(PortalSide.TOKEN, token)
            val path = url.removePrefix("http://192.168.1.20:8723")
            val nonce = PortalSide.verify(header, path, body, nowMs = now) ?: return@CallHttp CallHttpResponse(401, "{\"ok\":false,\"error\":\"unauthorized\"}")
            reply(path, body, nonce)
        }
        return CallClient(http, CallSigner(PortalSide.KEY, clock = { now })) to urls
    }

    @Test fun pollVerifiesAndParses() = runBlocking {
        val (c, urls) = client { _, body, nonce ->
            assertEquals("Pixel", JSONObject(body).getString("fn"))
            CallHttpResponse(200, PortalSide.envelope(nonce, JSONObject().put("ok", true)
                .put("portal", JSONObject().put("id", PortalSide.PORTAL_ID).put("name", "Kitchen"))
                .put("msgs", JSONArray()).toString()))
        }
        val r = c.poll(endpoint, "Pixel")
        assertEquals("Kitchen", r.portal.name)
        assertEquals("http://192.168.1.20:8723/remote/call/poll", urls.single())
        assertEquals(CallSigner.phoneId(PortalSide.TOKEN), c.phoneId(endpoint))
    }

    @Test fun forgedOrReplayedRepliesAreRejected() = runBlocking {
        val (c, _) = client { _, _, _ -> CallHttpResponse(200, PortalSide.envelope("some-other-nonce", "{\"ok\":true,\"r\":\"r\"}")) }
        expectFailure { c.send(endpoint, CallWire.ringBody(id, PortalSide.PORTAL_ID, true, "Pixel")) }
        val (bad, _) = client { _, _, nonce -> CallHttpResponse(200, PortalSide.envelope(nonce, "{\"ok\":true}", key = ByteArray(32))) }
        expectFailure { bad.poll(endpoint, "Pixel") }
    }

    @Test fun httpErrorsCarryTheCode() = runBlocking {
        val (c, _) = client { _, _, _ -> CallHttpResponse(409, "{\"ok\":false,\"error\":\"no_such_call\"}") }
        try { c.send(endpoint, CallWire.endBody(id, "p", EndWire.HUNG_UP)); fail() } catch (e: CallClientException) {
            assertEquals(409, e.status); assertEquals("no_such_call", e.message)
        }
    }

    @Test fun sendReturnsTheRingDecision() = runBlocking {
        val (c, _) = client { path, _, nonce ->
            assertEquals(CallWire.SEND, path)
            CallHttpResponse(200, PortalSide.envelope(nonce, "{\"ok\":true,\"r\":\"q\",\"qe\":420}"))
        }
        assertEquals(SendResult(PortalReply.QUIET, 420), c.send(endpoint, CallWire.ringBody(id, PortalSide.PORTAL_ID, true, "Pixel")))
    }

    @Test fun offerNeedsAnAnswerSignedInTheAnswerDomainAndBound() = runBlocking {
        val offer = PortalSide.sdp()
        val answer = PortalSide.sdp()
        val (c, _) = client { path, body, nonce ->
            assertEquals(CallWire.OFFER, path)
            assertEquals(PortalSide.FP, JSONObject(body).getString("fp"))
            CallHttpResponse(200, PortalSide.envelope(nonce, JSONObject().put("i", id).put("sdp", answer).put("fp", PortalSide.FP).toString(),
                domain = CallSigner.ANSWER_DOMAIN))
        }
        assertEquals(answer, c.offer(endpoint, id, PortalSide.PORTAL_ID, offer))
        val (wrongDomain, _) = client { _, _, nonce ->
            CallHttpResponse(200, PortalSide.envelope(nonce, JSONObject().put("i", id).put("sdp", answer).put("fp", PortalSide.FP).toString()))
        }
        expectFailure { wrongDomain.offer(endpoint, id, PortalSide.PORTAL_ID, offer) }
        expectFailure { c.offer(endpoint, id, PortalSide.PORTAL_ID, "v=0") }
    }

    @Test fun endpointNeverPrintsTheToken() {
        assertFalse(endpoint.toString().contains(PortalSide.TOKEN))
        assertTrue(CallHttpResponse(200, "secret").toString().contains("200"))
        assertFalse(CallSigner(PortalSide.KEY).sign(PortalSide.TOKEN, CallWire.POLL, "{}").toString().contains("IMMORTAL"))
    }

    private suspend fun expectFailure(block: suspend () -> Unit) {
        try { block(); fail("expected a failure") } catch (_: CallClientException) { }
    }
}
