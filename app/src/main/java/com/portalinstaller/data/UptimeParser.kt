package com.portalinstaller.data

/**
 * Extrage secundele de uptime din conținutul `/proc/uptime` (ex.
 * "12345.67 23456.78"). Prima valoare este uptime-ul total în secunde;
 * o trunchiem la întreg. Întoarce null dacă intrarea e goală sau invalidă.
 */
fun parseUptimeSeconds(procUptime: String): Long? {
    val firstToken = procUptime.trim().takeIf { it.isNotEmpty() }
        ?.substringBefore(' ')
        ?: return null
    return firstToken.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it >= 0.0 }
        ?.toLong()
}
