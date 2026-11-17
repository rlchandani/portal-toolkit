/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.doorbell

import org.json.JSONArray
import org.json.JSONObject

/**
 * One doorbell press or motion from a Portal's `/remote/doorbell`. The Portal sends how long ago
 * it happened ([ageMs]), never its clock; the same press has the same [id] on every Portal.
 */
data class DoorbellEvent(
    val id: String,
    val motion: Boolean,
    val title: String,
    val camera: String,
    val room: String,
    val ageMs: Long,
) {
    /** "Front door · Living Area", or just the room without a camera. */
    val detail: String get() = if (camera.isBlank()) room else "$camera · $room"

    companion object {
        private val ID = Regex("^[A-Za-z0-9]{8,40}$")
        private const val MAX_TEXT = 80
        private const val MAX_AGE_MS = 24 * 3_600_000L

        /** Portal data is untrusted: anything malformed is dropped. */
        fun parse(json: JSONObject?): DoorbellEvent? {
            if (json == null) return null
            val id = (json.opt("id") as? String)?.takeIf { ID.matches(it) } ?: return null
            val kind = json.opt("kind") as? String
            if (kind != "ring" && kind != "motion") return null
            val title = clean(json.opt("title") as? String) ?: return null
            val room = clean(json.opt("room") as? String) ?: return null
            val camera = clean(json.opt("camera") as? String).orEmpty()
            val age = (json.opt("ageMs") as? Number)?.toLong()?.takeIf { it in 0L..MAX_AGE_MS } ?: return null
            return DoorbellEvent(id, kind == "motion", title, camera, room, age)
        }

        fun parseList(array: JSONArray?): List<DoorbellEvent> =
            if (array == null) emptyList() else (0 until array.length()).mapNotNull { parse(array.optJSONObject(it)) }

        private fun clean(text: String?): String? =
            text?.replace(Regex("\\p{Cntrl}"), " ")?.trim()?.take(MAX_TEXT)?.takeIf { it.isNotEmpty() }
    }
}

/**
 * Which doorbell events deserve a banner and a notification on the phone. Pure.
 * - The same press reported by several Portals is one event (by id; the freshest copy wins).
 * - Only events younger than [FRESH_MS] are announced; anything announced before is not again.
 */
object DoorbellBannerPolicy {
    const val FRESH_MS = 60_000L
    const val MAX_REMEMBERED = 50

    data class Result(val fresh: List<DoorbellEvent>, val remembered: List<String>)

    fun merge(reports: List<List<DoorbellEvent>>): List<DoorbellEvent> =
        reports.flatten().groupBy { it.id }.values.map { copies -> copies.minBy { it.ageMs } }.sortedBy { it.ageMs }

    fun announce(reports: List<List<DoorbellEvent>>, remembered: List<String>): Result {
        val known = remembered.toSet()
        val fresh = merge(reports).filter { it.ageMs <= FRESH_MS && it.id !in known }
        return Result(fresh, remember(remembered, fresh.map { it.id }))
    }

    /** Oldest ids fall off first. */
    fun remember(remembered: List<String>, ids: List<String>): List<String> =
        (remembered.filterNot { it in ids } + ids.reversed()).takeLast(MAX_REMEMBERED)
}
