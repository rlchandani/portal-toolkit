/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.house

/** Every state the phone's Control Center can be in (the mock's States group, phone side). */
enum class HouseStatus { LOADING, LIVE, CONNECTING, STALE, HA_UNREACHABLE, TOKEN_REFUSED, NOT_SET_UP, PORTAL_OFFLINE, NO_PORTAL, NEEDS_UPDATE }

/**
 * Which state to show (pure, unit tested). The phone's own reachability comes first (no paired
 * Portal, none answering, Hearthpane too old), then the Portal's Home Assistant connection, then
 * staleness: values older than [STALE_MS] on the phone are "last known" even if the Portal said live.
 */
object HouseStatusRules {
    const val STALE_MS = 12_000L

    fun status(snapshot: HouseSnapshot?, reach: HouseReach, lastOkAt: Long, now: Long): HouseStatus = when {
        reach == HouseReach.NO_PORTAL -> HouseStatus.NO_PORTAL
        reach == HouseReach.NEEDS_UPDATE -> HouseStatus.NEEDS_UPDATE
        reach == HouseReach.HA_NOT_SET_UP -> HouseStatus.NOT_SET_UP
        snapshot == null -> if (reach == HouseReach.OFFLINE) HouseStatus.PORTAL_OFFLINE else HouseStatus.LOADING
        reach == HouseReach.OFFLINE -> HouseStatus.PORTAL_OFFLINE
        else -> fromPortal(snapshot.conn.phase, now - lastOkAt > STALE_MS, snapshot.entities.isNotEmpty())
    }

    private fun fromPortal(phase: String, phoneStale: Boolean, hasData: Boolean): HouseStatus = when (phase) {
        "live" -> if (phoneStale) HouseStatus.STALE else HouseStatus.LIVE
        "stale" -> HouseStatus.STALE
        "unreachable", "offline" -> HouseStatus.HA_UNREACHABLE
        "auth_failed" -> HouseStatus.TOKEN_REFUSED
        "not_configured" -> HouseStatus.NOT_SET_UP
        else -> if (hasData) HouseStatus.STALE else HouseStatus.CONNECTING
    }

    /** Controls act only on live values; everything else shows them disabled with a reason. */
    fun canControl(status: HouseStatus): Boolean = status == HouseStatus.LIVE

    /** The banner line over stale or unreachable values ("Reconnecting… values from 7:36 pm"). */
    fun banner(status: HouseStatus, portal: String, lastLive: String?): String? = when (status) {
        HouseStatus.STALE -> "Reconnecting…" + (lastLive?.let { " values from $it" } ?: "")
        HouseStatus.HA_UNREACHABLE -> "Can't reach Home Assistant" + (lastLive?.let { " · showing $it" } ?: "")
        HouseStatus.PORTAL_OFFLINE -> "${portal.ifBlank { "Your Portal" }} isn't answering" + (lastLive?.let { " · showing $it" } ?: "")
        HouseStatus.TOKEN_REFUSED -> "Home Assistant refused the Portal's token"
        else -> null
    }
}

/** How the last poll went from the phone's side. */
enum class HouseReach { OK, OFFLINE, NO_PORTAL, NEEDS_UPDATE, HA_NOT_SET_UP }
