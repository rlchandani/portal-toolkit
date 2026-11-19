/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.calls

import java.security.MessageDigest
import org.json.JSONObject

/**
 * The Portal's side of the phone call auth, copied from the launcher's `HouseholdMessage.decode`
 * and `CallPhoneAuth` (Calling 2a), so the tests prove the phone's bytes verify there.
 */
object PortalSide {
    val KEY = ByteArray(32) { (it * 7 + 3).toByte() }
    const val TOKEN = "session-token-abc"
    const val PORTAL_ID = "1b4e28ba-2fa1-11d2-883f-0016d3cca427"
    private val TYPE = Regex("^[a-z][a-z0-9_]{1,31}$")
    private val ID = Regex("^[A-Za-z0-9_-]{1,64}$")

    /** The nonce if [header] is a valid call_phone request for [path] and [body]; else null. */
    fun verify(header: String, path: String, body: String, key: ByteArray = KEY, token: String = TOKEN, nowMs: Long): String? {
        if (header.length > 1_400 || !header.startsWith("IMMORTAL_HH/1|")) return null
        val cut = header.lastIndexOf('|')
        val signed = header.substring(0, cut)
        if (CallSigner.hmacHex(key, signed) != header.substring(cut + 1)) return null
        val json = JSONObject(signed.substring("IMMORTAL_HH/1|".length))
        val t = json.getString("t"); val f = json.getString("f"); val n = json.getString("n")
        if (!TYPE.matches(t) || !ID.matches(f) || !ID.matches(n)) return null
        if (kotlin.math.abs(nowMs - json.getLong("ts")) > 120_000L) return null
        if (t != "call_phone" || f != "ph" + sha(("hearthpane-phone|$token")).take(16)) return null
        val b = json.getJSONObject("b")
        if (b.opt("p") != path || b.optString("s") != sha(body)) return null
        return n
    }

    fun envelope(nonce: String, payload: String, domain: String = CallSigner.RESPONSE_DOMAIN, key: ByteArray = KEY): String =
        JSONObject().put("payload", payload).put("sig", CallSigner.hmacHex(key, "$domain|$nonce|$payload")).toString()

    fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    const val FP = "sha-256 AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89"

    fun sdp(fp: String = FP, vararg candidates: String) = (listOf("v=0", "o=- 1 2 IN IP4 127.0.0.1", "a=fingerprint:$fp") +
        candidates.toList() + "a=mid:0").joinToString("\r\n")
}
