/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallSignerTest {
    private val now = 1_763_500_000_000L
    private val signer = CallSigner(PortalSide.KEY, nonce = { "0123456789abcdef01234567" }, clock = { now })

    @Test fun phoneIdIsTokenBoundAndLooksLikeThePortalExpects() {
        val id = CallSigner.phoneId(PortalSide.TOKEN)
        assertTrue(Regex("^ph[0-9a-f]{16}$").matches(id))
        assertEquals("ph" + PortalSide.sha("hearthpane-phone|${PortalSide.TOKEN}").take(16), id)
        assertTrue(id != CallSigner.phoneId("another-token"))
    }

    @Test fun signedHeaderVerifiesOnThePortal() {
        val body = CallWire.pollBody("Rohit’s Pixel")
        val req = signer.sign(PortalSide.TOKEN, CallWire.POLL, body)
        assertEquals("0123456789abcdef01234567", PortalSide.verify(req.header, CallWire.POLL, req.body, nowMs = now))
        assertTrue(req.header.length <= 1_400)
    }

    @Test fun portalRejectsSwappedBodyPathKeyTokenOrStaleClock() {
        val req = signer.sign(PortalSide.TOKEN, CallWire.SEND, "{\"t\":\"call_end\"}")
        assertNull(PortalSide.verify(req.header, CallWire.SEND, "{\"t\":\"call_ring\"}", nowMs = now))
        assertNull(PortalSide.verify(req.header, CallWire.OFFER, req.body, nowMs = now))
        assertNull(PortalSide.verify(req.header, CallWire.SEND, req.body, key = ByteArray(32), nowMs = now))
        assertNull(PortalSide.verify(req.header, CallWire.SEND, req.body, token = "stolen", nowMs = now))
        assertNull(PortalSide.verify(req.header, CallWire.SEND, req.body, nowMs = now + 121_000))
    }

    @Test fun opensOnlyEnvelopesSignedForThisNonceAndDomain() {
        val env = PortalSide.envelope("n1", "{\"ok\":true}")
        assertNotNull(signer.open(env, "n1", CallSigner.RESPONSE_DOMAIN))
        assertNull(signer.open(env, "n2", CallSigner.RESPONSE_DOMAIN))
        assertNull(signer.open(env, "n1", CallSigner.ANSWER_DOMAIN))
        assertNull(signer.open(PortalSide.envelope("n1", "{\"ok\":true}", key = ByteArray(32)), "n1", CallSigner.RESPONSE_DOMAIN))
        assertNull(signer.open("not json", "n1", CallSigner.RESPONSE_DOMAIN))
        assertNull(signer.open("{\"payload\":\"{}\"}", "n1", CallSigner.RESPONSE_DOMAIN))
        assertNull(signer.open(PortalSide.envelope("n1", "not json"), "n1", CallSigner.RESPONSE_DOMAIN))
    }

    @Test fun randomNoncesAreHexAndFresh() {
        val a = CallSigner.randomNonce()
        assertTrue(Regex("^[0-9a-f]{24}$").matches(a))
        assertTrue(a != CallSigner.randomNonce())
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesAShortKey() { CallSigner(ByteArray(16)) }
}
