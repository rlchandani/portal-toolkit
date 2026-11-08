/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.guests

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nearby Wi-Fi from scan results: security, dedupe, order and the connected network. */
class GuestNetworksTest {
    @Test fun securityFromCapabilities() {
        assertEquals("sae", GuestNetworks.security("[RSN-SAE-CCMP][ESS]"))
        assertEquals("wpa", GuestNetworks.security("[RSN-PSK+SAE-CCMP][ESS]")) // WPA2/WPA3 transition
        assertEquals("wpa", GuestNetworks.security("[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS][WPS]"))
        assertEquals("wpa", GuestNetworks.security("[WPA-PSK-TKIP][ESS]"))
        assertEquals("wep", GuestNetworks.security("[WEP][ESS]"))
        assertEquals("none", GuestNetworks.security("[RSN-OWE-CCMP][ESS]"))
        assertEquals("none", GuestNetworks.security("[ESS][OWE_TRANSITION]"))
        assertEquals("none", GuestNetworks.security("[ESS]"))
        assertEquals("none", GuestNetworks.security(""))
        assertNull(GuestNetworks.security("[WPA2-EAP/SHA1-CCMP][RSN-EAP/SHA1-CCMP][ESS]"))
        assertNull(GuestNetworks.security("[RSN-EAP_SUITE_B_192-GCMP-256][ESS]"))
    }

    @Test fun dedupesBySsidKeepingTheStrongest() {
        val list = GuestNetworks.fromScan(listOf(
            ScanEntry("Home", "[RSN-PSK-CCMP]", -80),
            ScanEntry("Home", "[RSN-SAE-CCMP]", -50),
        ), connectedSsid = null)
        assertEquals(1, list.size)
        assertEquals(-50, list[0].level)
        assertEquals("sae", list[0].security)
    }

    @Test fun connectedFirstThenBySignalAndHiddenOrEnterpriseSkipped() {
        val list = GuestNetworks.fromScan(listOf(
            ScanEntry("Cafe", "[ESS]", -40),
            ScanEntry("", "[RSN-PSK-CCMP]", -30),
            ScanEntry("\u0000\u0000", "[RSN-PSK-CCMP]", -30),
            ScanEntry("Office", "[RSN-EAP-CCMP]", -35),
            ScanEntry("Home", "[RSN-PSK-CCMP]", -70),
            ScanEntry("Neighbour", "[RSN-PSK-CCMP]", -60),
        ), connectedSsid = "Home")
        assertEquals(listOf("Home", "Cafe", "Neighbour"), list.map { it.ssid })
        assertTrue(list[0].connected)
        assertEquals(listOf(false, false), list.drop(1).map { it.connected })
    }

    @Test fun aConnectedNetworkMissingFromTheScanIsStillOffered() {
        val list = GuestNetworks.fromScan(listOf(ScanEntry("Cafe", "[ESS]", -40)), connectedSsid = "Hidden Home")
        assertEquals("Hidden Home", list[0].ssid)
        assertTrue(list[0].connected)
        assertEquals("wpa", list[0].security)
    }

    @Test fun connectedSsidIsUnquotedAndUnknownIsNull() {
        assertEquals("Home", GuestNetworks.cleanConnectedSsid("\"Home\""))
        assertNull(GuestNetworks.cleanConnectedSsid("<unknown ssid>"))
        assertNull(GuestNetworks.cleanConnectedSsid(null))
        assertNull(GuestNetworks.cleanConnectedSsid("\"\""))
    }

    @Test fun signalBars() {
        assertEquals(3, GuestNetworks.bars(-50))
        assertEquals(2, GuestNetworks.bars(-65))
        assertEquals(1, GuestNetworks.bars(-75))
        assertEquals(0, GuestNetworks.bars(-90))
    }
}
