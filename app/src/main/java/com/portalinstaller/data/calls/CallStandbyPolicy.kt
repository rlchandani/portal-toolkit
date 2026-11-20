/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.calls

/**
 * "Ring when Portal Toolkit is closed" (Calling 2b), without push. Pure (JVM-tested).
 *
 * Android can't wake a sleeping app without FCM, and FCM needs the owner's Google project. So,
 * opt-in and off by default, a small foreground service holds one long-poll per paired Portal
 * while the phone is on Wi-Fi: each request parks at the Portal for up to 25 s and returns the
 * moment a ring (or video message) is queued, so a ring arrives in well under a second. Between
 * rings that is one tiny request per Portal every ~25 s on Wi-Fi, no wake locks, no GPS, no
 * cellular. Off Wi-Fi it does nothing at all and says so in its notification.
 *
 * Trade-offs (also in the settings footer): a permanent, quiet notification; Android's deep
 * Doze can still delay the network for a phone left still and unplugged for a long time unless
 * the owner lets Portal Toolkit ignore battery optimisation; some phone makers kill background
 * services anyway; it only works on the home Wi-Fi (no relay).
 */
object CallStandbyPolicy {
    enum class Mode {
        /** The owner hasn't turned it on (the default), or nothing is paired. */
        OFF,
        /** On, but this phone isn't on Wi-Fi: no polling, the notification says it is paused. */
        WAITING_FOR_WIFI,
        /** On Wi-Fi: long-polling every paired Portal. */
        LISTENING,
    }

    fun mode(optedIn: Boolean, onWifi: Boolean, pairedPortals: Int, hasHouseholdKey: Boolean): Mode = when {
        !optedIn || pairedPortals == 0 || !hasHouseholdKey -> Mode.OFF
        !onWifi -> Mode.WAITING_FOR_WIFI
        else -> Mode.LISTENING
    }

    /** How the next poll of one Portal goes: park up to [waitMs], then wait [delayMs] before the next. */
    data class Plan(val waitMs: Long, val delayMs: Long)

    /**
     * In a call or with the app open, the 2a cadence (short polls, fresh presence). Listening in
     * the background: park as long as the Portal allows and re-poll at once; if the Portal had no
     * parking space ([grantedMs] 0 after asking), fall back to a short poll every few seconds so
     * the ring still arrives inside the Portal's 4 s answer window.
     */
    fun plan(inCall: Boolean, foreground: Boolean, mode: Mode, askedMs: Long, grantedMs: Long): Plan? = when {
        inCall -> Plan(0L, PhoneCallTimings.POLL_IN_CALL_MS)
        foreground -> Plan(0L, PhoneCallTimings.POLL_IDLE_MS)
        mode != Mode.LISTENING -> null
        askedMs > 0 && grantedMs == 0L -> Plan(0L, FALLBACK_POLL_MS)
        else -> Plan(CallWire.MAX_WAIT_MS, REPOLL_MS)
    }

    /** The service's notification (mock: content-light). */
    fun notice(mode: Mode, portals: Int): Pair<String, String> = when (mode) {
        Mode.LISTENING -> "Ready for Portal calls" to
            "On home Wi-Fi · ${if (portals == 1) "your Portal" else "$portals Portals"} can ring this phone"
        Mode.WAITING_FOR_WIFI -> "Portal calls paused" to "Not on Wi-Fi · Portals ring this phone again at home"
        Mode.OFF -> "Portal calls" to "Off"
    }

    /** A short poll this often when a Portal has no room to park us (still inside its 4 s ring window). */
    const val FALLBACK_POLL_MS = 3_000L
    /** Re-poll right after a parked poll returns (a tiny gap so a fast-failing Portal can't spin us). */
    const val REPOLL_MS = 250L
    /** After a failed request (Portal off, Wi-Fi blip), wait this long before trying that Portal again. */
    const val RETRY_MS = 5_000L

    const val FOOTER = "Off by default. When on, Portal Toolkit keeps a quiet notification and waits for calls from your Portals " +
        "while this phone is on Wi-Fi: about one tiny request per Portal every 25 seconds, nothing on mobile data. " +
        "Android may still delay a ring on a phone left still and unplugged for a long time unless you let Portal Toolkit " +
        "ignore battery optimisation. Without Google push, phones ring only at home."
}
