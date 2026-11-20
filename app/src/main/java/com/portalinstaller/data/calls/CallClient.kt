/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.calls

import java.io.IOException
import org.json.JSONObject

/** Where one paired Portal listens, with its session token (never logged). */
class CallEndpoint(val sessionId: String, val baseUrl: String, internal val token: String) {
    override fun toString() = "CallEndpoint(session=$sessionId, credentials=redacted)"
}

data class CallHttpResponse(val status: Int, val body: String) {
    override fun toString() = "CallHttpResponse(status=$status)"
}

/** POST with the call headers; the production one is [OkHttpCallHttp]. */
fun interface CallHttp {
    suspend fun post(url: String, token: String, householdHeader: String, body: String, timeoutMs: Long): CallHttpResponse
}

class CallClientException(message: String, val status: Int? = null) : IOException(message)

/**
 * The phone's side of `/remote/call/…`: every request signed by [CallSigner], every reply
 * opened only if its signature is bound to that request's nonce. Throws [CallClientException]
 * (or IOException from the network) instead of returning anything unverified.
 */
class CallClient(private val http: CallHttp, private val signer: CallSigner) {
    /** [waitMs] > 0: a background long-poll that parks at the Portal until a message arrives (Calling 2b). */
    suspend fun poll(endpoint: CallEndpoint, phoneName: String, aliases: Map<String, String> = emptyMap(), background: Boolean = false,
                     waitMs: Long = 0L): PollResult {
        val body = CallWire.pollBody(phoneName, aliases, background, waitMs)
        val payload = call(endpoint, CallWire.POLL, body, CallSigner.RESPONSE_DOMAIN, POLL_TIMEOUT_MS + waitMs.coerceIn(0, CallWire.MAX_WAIT_MS))
        return CallWire.parsePoll(payload, CallSigner.phoneId(endpoint.token)) ?: throw CallClientException("bad poll reply")
    }

    /** Sends a `{t,b}` body built by [CallWire]; returns the Portal's immediate result. */
    suspend fun send(endpoint: CallEndpoint, body: String): SendResult {
        val payload = call(endpoint, CallWire.SEND, body, CallSigner.RESPONSE_DOMAIN, SEND_TIMEOUT_MS)
        return CallWire.parseSend(payload) ?: throw CallClientException("bad send reply")
    }

    /** POSTs our offer; returns the Portal's verified answer SDP. */
    suspend fun offer(endpoint: CallEndpoint, callId: String, portalId: String, sdp: String): String {
        val fp = CallSdp.fingerprintOf(sdp) ?: throw CallClientException("offer has no single fingerprint")
        val payload = call(endpoint, CallWire.OFFER, CallWire.offerBody(callId, portalId, sdp, fp), CallSigner.ANSWER_DOMAIN, OFFER_TIMEOUT_MS)
        return CallWire.parseAnswer(payload, callId) ?: throw CallClientException("answer not bound to this call")
    }

    fun phoneId(endpoint: CallEndpoint): String = CallSigner.phoneId(endpoint.token)

    private suspend fun call(endpoint: CallEndpoint, path: String, body: String, domain: String, timeoutMs: Long): JSONObject {
        val req = signer.sign(endpoint.token, path, body)
        val res = http.post(endpoint.baseUrl.trimEnd('/') + path, endpoint.token, req.header, req.body, timeoutMs)
        if (res.status != HTTP_OK) throw CallClientException(errorCode(res.body) ?: "http ${res.status}", res.status)
        return signer.open(res.body, req.nonce, domain) ?: throw CallClientException("reply signature invalid")
    }

    private fun errorCode(body: String): String? = runCatching { JSONObject(body).optString("error") }.getOrNull()?.takeIf { it.isNotBlank() }

    companion object {
        private const val HTTP_OK = 200
        const val POLL_TIMEOUT_MS = 4_000L
        const val SEND_TIMEOUT_MS = 5_000L
        /** The Portal opens its media and gathers candidates before it answers (it waits up to 8 s). */
        const val OFFER_TIMEOUT_MS = 12_000L
    }
}
