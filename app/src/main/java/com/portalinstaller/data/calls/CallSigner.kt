/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.calls

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/** A signed call request: the body to send, the household header, and the nonce its reply is bound to. */
class SignedCallRequest(val path: String, val body: String, val header: String, val nonce: String) {
    override fun toString() = "SignedCallRequest(path=$path, header=redacted)"
}

/**
 * Signs phone → Portal call requests and checks the Portal's signed replies, matching the
 * Portal's `CallPhoneAuth` byte for byte (see the Calling 2a phone protocol):
 * - the `X-Immortal-Household` header is a `call_phone` household message from this phone's
 *   token-derived id, pinning the SHA-256 of the body and the request path;
 * - every reply is `{payload, sig}` with `sig = HMAC(key, domain|nonce|payload)`.
 * The household key is the one the phone provisioned to every Portal. Pure (JVM-tested).
 */
class CallSigner(
    private val key: ByteArray,
    private val nonce: () -> String = ::randomNonce,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init { require(key.size == KEY_SIZE) { "household key must be $KEY_SIZE bytes" } }

    fun sign(token: String, path: String, body: String): SignedCallRequest {
        val n = nonce()
        val json = JSONObject().put("t", TYPE).put("f", phoneId(token)).put("ts", clock()).put("n", n)
            .put("b", JSONObject().put("s", sha256Hex(body)).put("p", path)).toString()
        val signed = "$MAGIC|$json"
        return SignedCallRequest(path, body, "$signed|${hmacHex(key, signed)}", n)
    }

    /** The payload of a reply envelope if its signature is ours for [nonce]; otherwise null. */
    fun open(envelope: String, nonce: String, domain: String): JSONObject? {
        val o = runCatching { JSONObject(envelope) }.getOrNull() ?: return null
        val payload = o.opt("payload") as? String ?: return null
        val sig = o.opt("sig") as? String ?: return null
        val expected = hmacHex(key, "$domain|$nonce|$payload")
        if (!MessageDigest.isEqual(sig.toByteArray(Charsets.US_ASCII), expected.toByteArray(Charsets.US_ASCII))) return null
        return runCatching { JSONObject(payload) }.getOrNull()
    }

    companion object {
        const val HEADER = "X-Immortal-Household"
        const val TYPE = "call_phone"
        const val MAGIC = "IMMORTAL_HH/1"
        const val RESPONSE_DOMAIN = "call_phone_resp"
        const val ANSWER_DOMAIN = "call_answer"
        const val KEY_SIZE = 32
        private const val PHONE_PREFIX = "ph"
        private const val PHONE_HEX = 16
        private const val NONCE_BYTES = 12
        private val random = SecureRandom()

        /** How a Portal names this phone: bound to that Portal's session token. */
        fun phoneId(token: String): String = PHONE_PREFIX + sha256Hex("hearthpane-phone|$token").take(PHONE_HEX)

        fun sha256Hex(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).toHex()

        fun hmacHex(key: ByteArray, text: String): String =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
                .doFinal(text.toByteArray(Charsets.UTF_8)).toHex()

        fun randomNonce(): String = ByteArray(NONCE_BYTES).also(random::nextBytes).toHex()

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
