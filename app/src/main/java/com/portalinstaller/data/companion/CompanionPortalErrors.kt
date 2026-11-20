/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.companion

import org.json.JSONObject

/** Pure wording for Portal error replies, and the pairing label this phone sends. */
object CompanionPortalErrors {
    private const val MAX_LABEL_CHARS = 40
    private const val MAX_WAIT_SEC = 3_600

    fun message(code: String, status: Int, body: JSONObject): String = when (code) {
        "bad_pin" -> "PIN was rejected or expired. Show a fresh PIN on the Portal."
        // 401 covers a removed phone and a pairing that expired after months unused.
        "unauthorized" -> "This phone's pairing ended. Pair again with a fresh PIN from the Portal."
        "pair_rate_limited" -> rateLimited(body)
        "pair_again" -> "Pair again on the Portal to finish household setup."
        "immich_url_not_lan" -> "Use your Immich server's home-network address (or the one saved on the Portal)."
        "no_accessibility", "no_gestures" -> "Enable Hearthpane's accessibility service on the Portal."
        else -> "Portal rejected the request: $code (HTTP $status)."
    }

    /** "Too many tries. Wait 30 s, then try again." from the Portal's `retryAfterSec`. */
    fun rateLimited(body: JSONObject): String {
        val wait = body.optLong("retryAfterSec", 0L)
        return if (wait in 1..MAX_WAIT_SEC) "Too many tries. Wait $wait s, then try again."
        else "Too many tries. Wait a moment, then try again."
    }

    /** Printable, single-line and short, or null when there is nothing worth sending. */
    fun pairLabel(raw: String?): String? =
        raw?.filter { !it.isISOControl() }?.trim()?.take(MAX_LABEL_CHARS)?.takeIf { it.isNotBlank() }
}
