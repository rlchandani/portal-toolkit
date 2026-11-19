/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.house

import org.json.JSONObject

/** Household words for what went wrong (pure, unit tested); never a raw error code. */
object HouseCopy {
    const val OFFLINE = "Can't reach your Portal. Check you're on the home Wi-Fi."
    const val NO_ANSWER = "The Portal didn't hear back from Home Assistant in time."

    fun refused(code: String, details: JSONObject?): String = when (code) {
        "ha_unavailable" -> details?.optString("reason")?.takeIf { it.isNotBlank() } ?: "Home Assistant isn't connected right now."
        "ha_refused" -> "Couldn't do that: " + (details?.optString("message")?.takeIf { it.isNotBlank() } ?: "Home Assistant refused")
        "rate_limited" -> "That's a lot of taps. Try again in a moment."
        "busy" -> "The Portal is busy. Try again in a moment."
        "unknown_entity", "unknown_camera" -> "That device isn't on the Portal any more."
        "no_car", "no_limit" -> "Home Assistant doesn't have that car control."
        "refused" -> details?.optString("reason")?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() } ?: "The Portal said no."
        "unauthorized" -> "Pair this phone with the Portal again."
        "ha_not_configured" -> "Home Assistant isn't set up on this Portal."
        "not_found" -> "Update Hearthpane on your Portal to control the house from here."
        else -> "The Portal didn't accept that."
    }
}
