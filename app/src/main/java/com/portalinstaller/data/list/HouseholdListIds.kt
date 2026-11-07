/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.list

import java.security.MessageDigest
import java.util.Locale

/**
 * Item ids and text rules shared with the Portal app (pinned by known-answer tests on both
 * sides), so a queued add and a queued tick of the same item agree on its id before any Portal
 * has seen it, and two places adding "Milk" land on one item.
 */
object HouseholdListIds {
    const val MAX_TEXT = 60
    private val WHITESPACE = Regex("\\s+")
    val ID = Regex("^[A-Za-z0-9_-]{1,64}$")

    fun norm(text: String): String = text.trim().lowercase(Locale.ROOT).replace(WHITESPACE, " ")

    fun itemId(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(("immortal-list-id:" + norm(text)).toByteArray(Charsets.UTF_8))
        return "h" + digest.joinToString("") { "%02x".format(it) }.take(15)
    }

    /** Trimmed, whitespace-collapsed text, or null when blank or longer than [MAX_TEXT]. */
    fun cleanText(raw: String): String? {
        val text = raw.trim().replace(WHITESPACE, " ")
        return text.takeIf { it.isNotEmpty() && it.length <= MAX_TEXT }
    }
}
