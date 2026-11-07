/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.routines

import org.json.JSONArray
import org.json.JSONObject

/** One picture card; [pic] is a bundled picture id shared with the Portal. */
data class RoutineCard(val id: String, val pic: String, val label: String)

data class RoutineKid(val id: String, val name: String, val avatar: String)

/** A picture routine as the Portal stores it ([days] Monday-first, times in minutes of the day). */
data class Routine(
    val id: String,
    val name: String,
    val kids: List<String>,
    val cards: List<RoutineCard>,
    val days: List<Boolean>,
    val start: Int,
    val end: Int,
    val enabled: Boolean,
    val skips: List<String>,
)

/** Today's progress as the Portal last reported it. [endedAt]/[deadline] are minutes of the day. */
data class RoutineRunInfo(
    val rid: String, val status: String, val endedBy: String?, val endedAt: Int?,
    val deadline: Int, val leftMin: Int, val done: Int, val total: Int,
)

/** GET /remote/routines: the document at [rev] plus the Portal's own day and minute. */
data class RoutineSnapshot(
    val rev: Long, val routines: List<Routine>, val kids: List<RoutineKid>,
    val today: List<RoutineRunInfo>, val day: Int, val minute: Int,
)

/**
 * The bundled picture set: the same 23 ids, labels and drawings as the Portal (stable ids are the
 * contract). Drawables are `routine_pic_<id>`.
 */
object RoutinePictures {
    data class Picture(val id: String, val label: String, val group: String)

    val all = listOf(
        Picture("shirt", "Get dressed", "Morning"), Picture("bowl", "Breakfast", "Morning"),
        Picture("toothbrush", "Brush teeth", "Morning"), Picture("shoes", "Shoes on", "Morning"),
        Picture("backpack", "Backpack", "Morning"), Picture("coat", "Coat on", "Morning"),
        Picture("bed", "Make bed", "Morning"), Picture("bottle", "Water bottle", "Morning"),
        Picture("socks", "Socks on", "Morning"), Picture("lunch", "Lunchbox", "Morning"),
        Picture("hair", "Brush hair", "Morning"), Picture("wash", "Wash hands", "Any time"),
        Picture("toilet", "Toilet", "Any time"), Picture("hat", "Hat on", "Morning"),
        Picture("apple", "Snack", "Morning"), Picture("bath", "Bath", "Evening"),
        Picture("pyjamas", "Pyjamas", "Evening"), Picture("book", "Story", "Evening"),
        Picture("teddy", "Teddy", "Evening"), Picture("moon", "Lights out", "Evening"),
        Picture("toys", "Tidy toys", "Evening"), Picture("cup", "Drink milk", "Any time"),
        Picture("car", "Into the car", "Morning"),
    )
    val ids = all.map { it.id }
    val groups = listOf("Morning", "Any time", "Evening")
    val avatars = listOf("fox", "owl")
    private val byId = all.associateBy { it.id }
    fun label(id: String) = byId[id]?.label ?: ""
    fun isValid(id: String) = id in byId
}

/** Epoch-day calendar math (API-safe, mirrors the Portal's RoutineDates). Monday = 0. */
object RoutineDates {
    val DAY_NAMES = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    val MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    private val ISO = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$")

    fun weekday(day: Int) = Math.floorMod(day + 3, 7)

    fun epochDayOf(text: String): Int? {
        val m = ISO.matchEntire(text) ?: return null
        val (y, mo, d) = m.destructured.toList().map(String::toInt)
        if (mo !in 1..12 || d < 1 || d > daysInMonth(y, mo)) return null
        return fromCivil(y, mo, d)
    }

    fun isoOf(day: Int): String = civil(day).let { (y, m, d) -> "%04d-%02d-%02d".format(y, m, d) }
    fun civil(day: Int): Triple<Int, Int, Int> {
        val z = day + 719468
        val era = Math.floorDiv(z, 146097)
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        return Triple(yoe + era * 400 + if (m <= 2) 1 else 0, m, d)
    }

    fun fromCivil(y0: Int, m: Int, d: Int): Int {
        val y = if (m <= 2) y0 - 1 else y0
        val era = Math.floorDiv(y, 400)
        val yoe = y - era * 400
        val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
        return era * 146097 + yoe * 365 + yoe / 4 - yoe / 100 + doy - 719468
    }

    fun daysInMonth(y: Int, m: Int) = when (m) {
        2 -> if (y % 4 == 0 && (y % 100 != 0 || y % 400 == 0)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    fun clock12(min: Int): String { val m = Math.floorMod(min, 1440); return "${(m / 60 + 11) % 12 + 1}:%02d".format(m % 60) }
    fun time12(min: Int) = clock12(min) + if (Math.floorMod(min, 1440) < 720) " AM" else " PM"
}

/** US school holidays for the Skip days list (labels only; nothing is skipped automatically). */
object RoutineHolidays {
    fun name(day: Int): String? {
        val (y, m, d) = RoutineDates.civil(day)
        val wd = RoutineDates.weekday(day)
        fun nth(n: Int, weekday: Int) = wd == weekday && (d - 1) / 7 == n - 1
        fun last(weekday: Int) = wd == weekday && d + 7 > RoutineDates.daysInMonth(y, m)
        val thanksgiving = m == 11 && nth(4, 3)
        return when {
            m == 1 && d == 1 -> "New Year’s Day"
            m == 1 && nth(3, 0) -> "Martin Luther King Jr. Day"
            m == 2 && nth(3, 0) -> "Presidents’ Day"
            m == 5 && last(0) -> "Memorial Day"
            m == 6 && d == 19 -> "Juneteenth"
            m == 7 && d == 4 -> "Independence Day"
            m == 9 && nth(1, 0) -> "Labor Day"
            m == 10 && nth(2, 0) -> "Indigenous Peoples’ Day"
            m == 11 && d == 11 -> "Veterans Day"
            thanksgiving -> "Thanksgiving"
            m == 11 && wd == 4 && name(day - 1) == "Thanksgiving" -> "Day after Thanksgiving"
            m == 12 && d == 25 -> "Christmas Day"
            else -> null
        }
    }
}

/** JSON wire form of `/remote/routines`; everything from the Portal is validated before use. */
object RoutineWire {
    private val ID = Regex("^[a-z0-9]{1,24}$")

    fun routineJson(r: Routine): JSONObject = JSONObject().put("id", r.id).put("name", r.name).put("kids", JSONArray(r.kids))
        .put("cards", JSONArray().apply { r.cards.forEach { put(JSONObject().put("id", it.id).put("pic", it.pic).put("label", it.label)) } })
        .put("days", JSONArray(r.days.map { if (it) 1 else 0 })).put("start", r.start).put("end", r.end)
        .put("enabled", r.enabled).put("skips", JSONArray(r.skips))

    fun kidsJson(kids: List<RoutineKid>): JSONArray =
        JSONArray().apply { kids.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("avatar", it.avatar)) } }

    fun parseSnapshot(j: JSONObject): RoutineSnapshot = RoutineSnapshot(
        rev = j.optLong("rev", 0),
        routines = j.optJSONArray("routines").objects().mapNotNull(::parseRoutine),
        kids = j.optJSONArray("kids").objects().mapNotNull(::parseKid),
        today = j.optJSONArray("today").objects().mapNotNull(::parseRun),
        day = j.optInt("day"), minute = j.optInt("minute").coerceIn(0, 1439),
    )

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    fun parseRoutine(j: JSONObject): Routine? = runCatching {
        val id = j.getString("id").takeIf(ID::matches) ?: return null
        val cards = j.getJSONArray("cards").objects().map { RoutineCard(it.getString("id"), it.getString("pic"), it.getString("label")) }
        if (cards.any { !RoutinePictures.isValid(it.pic) }) return null
        val days = j.getJSONArray("days").let { a -> (0 until a.length()).map { a.getInt(it) == 1 } }
        if (days.size != 7) return null
        val kids = j.optJSONArray("kids")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        val skips = j.optJSONArray("skips")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            .filter { RoutineDates.epochDayOf(it) != null }
        Routine(id, j.getString("name"), kids, cards, days, j.getInt("start"), j.getInt("end"), j.getBoolean("enabled"), skips)
    }.getOrNull()

    private fun parseKid(j: JSONObject): RoutineKid? = runCatching {
        RoutineKid(j.getString("id").takeIf(ID::matches) ?: return null, j.getString("name"), j.getString("avatar"))
    }.getOrNull()

    private fun parseRun(j: JSONObject): RoutineRunInfo? = runCatching {
        val rid = j.get("rid") as? String ?: return null
        RoutineRunInfo(rid, j.getString("status"), if (j.isNull("endedBy")) null else j.optString("endedBy"),
            if (j.isNull("endedAt")) null else j.getInt("endedAt"), j.getInt("deadline"), j.getInt("leftMin"),
            j.getInt("done"), j.getInt("total"))
    }.getOrNull()
}
