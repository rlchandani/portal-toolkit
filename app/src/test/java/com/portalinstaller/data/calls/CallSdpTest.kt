/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallSdpTest {
    private val host = "a=candidate:1 1 udp 2122260223 192.168.1.40 50000 typ host generation 0"
    private val tcp = "a=candidate:2 1 tcp 1518280447 192.168.1.40 9 typ host tcptype active"
    private val public = "a=candidate:3 1 udp 2122260223 8.8.8.8 50001 typ host"
    private val srflx = "a=candidate:4 1 udp 1686052607 10.0.0.2 50002 typ srflx raddr 0.0.0.0 rport 0"
    private val v6 = "a=candidate:5 1 udp 2122262783 fe80::1 50003 typ host"
    private val mdns = "a=candidate:6 1 udp 2122260223 abc.local 50004 typ host"

    @Test fun keepsOnlyPrivateUdpHostCandidates() {
        val out = CallSdp.lanOnly(PortalSide.sdp(PortalSide.FP, host, tcp, public, srflx, v6, mdns, "a=candidate:garbage"))
        assertTrue(out.contains(host))
        listOf(tcp, public, srflx, v6, mdns, "a=candidate:garbage").forEach { assertFalse(out.contains(it)) }
        assertTrue(CallSdp.hasLanCandidate(out))
        assertFalse(CallSdp.hasLanCandidate(CallSdp.lanOnly(PortalSide.sdp(PortalSide.FP, public))))
    }

    @Test fun fingerprintNormalisedAndSingle() {
        assertEquals(PortalSide.FP, CallSdp.fingerprintOf(PortalSide.sdp("SHA-256 " + PortalSide.FP.removePrefix("sha-256 ").lowercase())))
        val two = PortalSide.sdp() + "\r\na=fingerprint:sha-256 " + "00:".repeat(31) + "00"
        assertNull(CallSdp.fingerprintOf(two))
        assertNull(CallSdp.fingerprintOf(PortalSide.sdp("sha-1 AB:CD")))
        assertNull(CallSdp.fingerprintOf("v=0"))
        // The same fingerprint repeated per m-section is fine.
        assertEquals(PortalSide.FP, CallSdp.fingerprintOf(PortalSide.sdp() + "\r\na=fingerprint:" + PortalSide.FP))
    }

    @Test fun privateRanges() {
        listOf("10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.0.9").forEach { assertTrue(it, CallSdp.isPrivateLan(it)) }
        listOf("172.32.0.1", "11.0.0.1", "192.169.1.1", "256.1.1.1", "localhost", "10.0.0").forEach { assertFalse(it, CallSdp.isPrivateLan(it)) }
    }
}
