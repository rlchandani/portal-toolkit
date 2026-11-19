/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.calls

/**
 * SDP rules shared with the Portal's `CallSignalAuth` (ported, same behaviour):
 * - a call stays on the home Wi-Fi: only UDP host candidates on private IPv4 addresses survive;
 * - the DTLS fingerprint is the SDP's single `a=fingerprint` (all lines must agree), as
 *   `sha-256 AA:BB:…`. It travels inside the household-signed offer/answer, so WebRTC's DTLS
 *   check ties the media keys to the household key. Pure.
 */
object CallSdp {
    const val MAX_SDP = 32 * 1024
    private val FINGERPRINT = Regex("^sha-256 ([0-9A-F]{2}:){31}[0-9A-F]{2}$")
    private val FINGERPRINT_LINE = Regex("^a=fingerprint:(\\S+) (\\S+)\\s*$", RegexOption.MULTILINE)
    private val CANDIDATE = Regex("^a=candidate:\\S+ \\d+ (\\S+) \\d+ (\\S+) \\d+ typ (\\S+).*$")
    private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
    private const val LINE_BREAK = "\r\n"

    fun fingerprintOf(sdp: String): String? {
        val all = FINGERPRINT_LINE.findAll(sdp).map { "${it.groupValues[1].lowercase()} ${it.groupValues[2].uppercase()}" }.toSet()
        return all.singleOrNull()?.takeIf(FINGERPRINT::matches)
    }

    fun lanOnly(sdp: String): String = sdp.split(LINE_BREAK).filter { line ->
        if (!line.startsWith("a=candidate:")) return@filter true
        val m = CANDIDATE.matchEntire(line) ?: return@filter false
        m.groupValues[1].equals("udp", ignoreCase = true) && m.groupValues[3] == "host" && isPrivateLan(m.groupValues[2])
    }.joinToString(LINE_BREAK)

    fun hasLanCandidate(sdp: String): Boolean = sdp.split(LINE_BREAK).any { it.startsWith("a=candidate:") }

    fun isPrivateLan(host: String): Boolean {
        val m = IPV4.matchEntire(host) ?: return false
        val o = m.groupValues.drop(1).map { it.toInt() }
        if (o.any { it > 255 }) return false
        return o[0] == 10 || (o[0] == 172 && o[1] in 16..31) || (o[0] == 192 && o[1] == 168)
    }
}
