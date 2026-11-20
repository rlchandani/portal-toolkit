/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.companion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone side of the masked photo-source secrets (Hearthpane audit finding 1), against both
 * Portal reply shapes:
 *
 * | Portal                    | `GET /remote/sources` secret slot | Phone shows   | Unchanged save sends |
 * |---------------------------|-----------------------------------|---------------|----------------------|
 * | old (<= 1.272)            | plaintext                         | "•••• saved"  | no secret field      |
 * | new (1.273+)              | "••••••••" + flags + key hint     | "••••XY saved"| no secret field      |
 * | either, nothing saved     | ""                                | nothing       | no secret field      |
 * | either, user typed secret | -                                 | -             | the typed value      |
 *
 * Both Portals keep a saved secret when the field is omitted (old: same endpoint and account; new:
 * also when blank or masked), so no combination wipes a password.
 */
class CompanionSourceSecretsTest {
    private val oldPortal = JSONObject("""{"ok":true,"sources":{"source":"immich","immichUrl":"http://192.168.1.5:2283",
        "immichKey":"plain-old-key-0123456789","hasImmichKey":true,"immichKeyConfigured":true,
        "smbHost":"nas.local","smbShare":"photos","smbUser":"reader","smbPass":"smb-secret","hasSmbPass":true,
        "davUrl":"","davUser":"","davPass":"","hasDavPass":false}}""")
    private val newPortal = JSONObject("""{"ok":true,"sources":{"source":"immich","immichUrl":"http://192.168.1.5:2283",
        "immichKey":"••••••••","immichKeyHint":"89","hasImmichKey":true,"immichKeyConfigured":true,
        "smbHost":"nas.local","smbShare":"photos","smbUser":"reader","smbPass":"••••••••","hasSmbPass":true,"smbPassConfigured":true,
        "davUrl":"","davUser":"","davPass":"","hasDavPass":false,"davPassConfigured":false}}""")

    private fun read(reply: JSONObject) = CompanionClient.sanitized(reply.getJSONObject("sources"), sourceCredentials = true)

    @Test fun neitherPortalShapeLeavesASecretOnThePhone() {
        for (reply in listOf(oldPortal, newPortal)) {
            val sources = read(reply)
            for (key in CompanionSourceSecrets.FIELDS) assertFalse(key, sources.has(key))
            assertFalse(sources.toString().contains("plain-old-key"))
            assertFalse(sources.toString().contains("smb-secret"))
        }
    }

    @Test fun savedSecretsShowAsSavedWithTheNewPortalsHint() {
        assertEquals("•••• saved", CompanionSourceSecrets.savedLabel(read(oldPortal), "immichKey"))
        assertEquals("••••89 saved", CompanionSourceSecrets.savedLabel(read(newPortal), "immichKey"))
        assertEquals("•••• saved", CompanionSourceSecrets.savedLabel(read(newPortal), "smbPass"))
        assertNull(CompanionSourceSecrets.savedLabel(read(newPortal), "davPass"))
        assertNull(CompanionSourceSecrets.savedLabel(read(oldPortal), "davPass"))
    }

    @Test fun hostileHintIsIgnored() {
        val sources = JSONObject().put("immichKeyConfigured", true).put("immichKeyHint", "ab\u0007")
        assertEquals("•••• saved", CompanionSourceSecrets.savedLabel(sources, "immichKey"))
        assertEquals("•••• saved", CompanionSourceSecrets.savedLabel(sources.put("immichKeyHint", "••"), "immichKey"))
    }

    @Test fun unchangedSaveSendsNoSecretOnEitherPortal() {
        val fields = listOf("smbHost", "smbShare", "smbPath", "smbUser", "smbPass")
        for (draft in listOf("", "   ", "••••••••", "••••")) {
            val body = CompanionSourceSecrets.body(fields, setOf("smbPass"),
                mapOf("smbHost" to " nas.local ", "smbShare" to "photos", "smbUser" to "reader", "smbPass" to draft))
            assertFalse("draft '$draft'", body.has("smbPass"))
            assertEquals("nas.local", body.getString("smbHost"))
            assertEquals("", body.getString("smbPath"))
        }
    }

    @Test fun typedSecretIsSentExactly() {
        val body = CompanionSourceSecrets.body(listOf("immichUrl", "immichKey"), setOf("immichKey"),
            mapOf("immichUrl" to "http://192.168.1.5:2283", "immichKey" to " new key "))
        assertEquals(" new key ", body.getString("immichKey"))
    }

    @Test fun maskDetection() {
        assertTrue(CompanionSourceSecrets.isMasked("••••••••"))
        assertFalse(CompanionSourceSecrets.isMasked(""))
        assertFalse(CompanionSourceSecrets.isMasked("••••ab"))
        assertNull(CompanionSourceSecrets.typed("••••••••"))
        assertEquals("pw", CompanionSourceSecrets.typed("pw"))
    }

    @Test fun configuredAcceptsBothFlagSpellings() {
        assertTrue(CompanionSourceSecrets.configured(JSONObject().put("hasSmbPass", true), "smbPass"))
        assertTrue(CompanionSourceSecrets.configured(JSONObject().put("smbPassConfigured", true), "smbPass"))
        assertFalse(CompanionSourceSecrets.configured(JSONObject(), "smbPass"))
    }

    @Test fun portalErrorWording() {
        assertEquals("Too many tries. Wait 30 s, then try again.",
            CompanionPortalErrors.message("pair_rate_limited", 429, JSONObject().put("retryAfterSec", 30)))
        assertEquals("Too many tries. Wait a moment, then try again.",
            CompanionPortalErrors.message("pair_rate_limited", 429, JSONObject().put("retryAfterSec", -4)))
        assertTrue(CompanionPortalErrors.message("unauthorized", 401, JSONObject()).contains("Pair again"))
        assertEquals("Portal rejected the request: odd (HTTP 418).", CompanionPortalErrors.message("odd", 418, JSONObject()))
    }

    @Test fun pairLabelIsCleanAndOptional() {
        assertEquals("Pixel 8", CompanionPortalErrors.pairLabel(" Pixel 8\n"))
        assertNull(CompanionPortalErrors.pairLabel(null))
        assertNull(CompanionPortalErrors.pairLabel("\u0000 "))
        assertEquals(40, CompanionPortalErrors.pairLabel("x".repeat(99))!!.length)
    }
}
