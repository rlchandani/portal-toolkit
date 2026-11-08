/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.guests

/** One raw Wi-Fi scan result, copied out of `android.net.wifi.ScanResult`. */
data class ScanEntry(val ssid: String, val capabilities: String, val level: Int)

/** A network the household can pick for the guest card. */
data class NearbyNetwork(val ssid: String, val security: String, val level: Int, val connected: Boolean)

/** Turns scan results into the picker list. Pure, so the rules are unit-tested. */
object GuestNetworks {
    /** A connected network that isn't in the scan (hidden, or a stale scan) sorts first anyway. */
    private const val UNKNOWN_LEVEL = -100

    /**
     * The card's security for a capabilities string such as `[RSN-PSK+SAE-CCMP][ESS]`, or null for
     * enterprise (802.1X) networks, which a Wi-Fi QR code can't join. PSK wins over SAE so WPA2/WPA3
     * transition networks stay joinable by older phones; OWE (Enhanced Open) joins like open.
     */
    fun security(capabilities: String): String? {
        val c = capabilities.uppercase()
        return when {
            "PSK" in c -> GuestCard.SECURITY_WPA
            "SAE" in c -> GuestCard.SECURITY_SAE
            "EAP" in c || "SUITE_B" in c || "SUITE-B" in c -> null
            "WEP" in c -> GuestCard.SECURITY_WEP
            else -> GuestCard.SECURITY_NONE
        }
    }

    fun fromScan(results: List<ScanEntry>, connectedSsid: String?): List<NearbyNetwork> {
        val scanned = results.asSequence()
            .filter { isVisibleSsid(it.ssid) }
            .groupBy { it.ssid }
            .mapNotNull { (_, same) -> same.maxBy { it.level }.toNearby(connectedSsid) }
        val missingConnected = connectedSsid?.takeIf { ssid -> scanned.none { it.ssid == ssid } }
            ?.let { NearbyNetwork(it, GuestCard.SECURITY_WPA, UNKNOWN_LEVEL, connected = true) }
        return (scanned + listOfNotNull(missingConnected))
            .sortedWith(compareByDescending<NearbyNetwork> { it.connected }.thenByDescending { it.level }
                .thenBy { it.ssid.lowercase() })
    }

    private fun ScanEntry.toNearby(connectedSsid: String?): NearbyNetwork? =
        security(capabilities)?.let { NearbyNetwork(ssid, it, level, connected = ssid == connectedSsid) }

    private fun isVisibleSsid(ssid: String): Boolean = ssid.isNotBlank() && ssid.none { it == '\u0000' }

    /** `"Home"` → `Home`; Android's placeholders for "no permission / not connected" → null. */
    fun cleanConnectedSsid(raw: String?): String? {
        val value = raw?.trim()?.removeSurrounding("\"") ?: return null
        return value.takeIf { it.isNotEmpty() && it != "<unknown ssid>" && it != "0x" }
    }

    /** 0–3 bars from RSSI dBm. */
    fun bars(level: Int): Int = when {
        level >= -55 -> 3
        level >= -67 -> 2
        level >= -78 -> 1
        else -> 0
    }
}
