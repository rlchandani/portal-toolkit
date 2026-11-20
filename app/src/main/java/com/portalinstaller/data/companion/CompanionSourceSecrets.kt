/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.companion

import org.json.JSONObject

/**
 * Photo-source secrets on the phone. The Portal's read never needs to (and from Hearthpane 1.273
 * does not) return them: an older Portal returns plaintext, a newer one a mask plus flags, and
 * [CompanionClient.sanitized] reduces both to `…Configured` flags (keeping an Immich key hint).
 * The form shows "•••• saved" for a saved secret and sends a secret only when the user typed one,
 * so an unchanged save keeps the saved secret on every Portal version.
 */
object CompanionSourceSecrets {
    val FIELDS = setOf("immichKey", "smbPass", "davPass")
    private const val BULLET = '•'
    private const val SAVED = "•••• saved"

    /** True when a saved secret exists for [key] (new or old Portal flag spelling). */
    fun configured(sources: JSONObject, key: String): Boolean =
        sources.optBoolean("${key}Configured") || sources.optBoolean("has${key.replaceFirstChar(Char::titlecase)}")

    /** "•••• saved", or "••••XY saved" with the Portal's two-character hint; null when none is saved. */
    fun savedLabel(sources: JSONObject, key: String): String? {
        if (!configured(sources, key)) return null
        val hint = sources.optString("${key}Hint").takeIf { it.length in 1..2 && it.none { c -> c == BULLET || c.isISOControl() } }
        return if (hint == null) SAVED else "••••$hint saved"
    }

    /** A placeholder from a masked read, never something the user typed. */
    fun isMasked(value: String): Boolean = value.isNotEmpty() && value.all { it == BULLET }

    /** The secret to send: only a typed value; blank or masked means "keep the saved one". */
    fun typed(draft: String): String? = draft.takeIf { it.isNotEmpty() && it.isNotBlank() && !isMasked(it) }

    /**
     * The save body for [fields] of the selected source: non-secret fields trimmed, secrets only when
     * typed. [secretKeys] marks which of [fields] are secrets.
     */
    fun body(fields: List<String>, secretKeys: Set<String>, drafts: Map<String, String>): JSONObject =
        JSONObject().also { out ->
            for (key in fields) {
                val draft = drafts[key].orEmpty()
                if (key in secretKeys) typed(draft)?.let { out.put(key, it) } else out.put(key, draft.trim())
            }
        }
}
