/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.reminders

import com.portalinstaller.data.chores.ChoreText
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads a Portal's `/remote/reminders` answer:
 * `{ok, ready, portal, reminders:[{id,title,person,for,due,done,by,seq}], privateCounts:{name:n}, people:[{id,name,kid}]}`.
 * The Portal is untrusted: a bad entry is skipped, a bad envelope (no `reminders` list) reads as null.
 */
object ReminderCodec {
    private val ID = Regex("^[A-Za-z0-9_-]{1,${ReminderLimits.ID_MAX}}$")
    /** Past year 9999 is junk, not a date. */
    private const val MAX_DUE = 253_402_300_799_999L
    private const val MAX_SEQ = Long.MAX_VALUE / 2

    fun decode(json: JSONObject?): ReminderBoard? {
        if (json == null) return null
        val list = json.opt("reminders") as? JSONArray ?: return null
        return ReminderBoard(
            ready = json.opt("ready") == true,
            portal = text(json.opt("portal"), ReminderLimits.NAME_MAX).orEmpty(),
            reminders = objects(list).mapNotNull(::reminder).distinctBy { it.id }.take(ReminderLimits.MAX_REMINDERS),
            privateCounts = counts(json.opt("privateCounts") as? JSONObject),
            people = objects(json.opt("people") as? JSONArray).mapNotNull(::person).distinctBy { it.id }
                .take(ReminderLimits.MAX_PEOPLE),
        )
    }

    internal fun reminder(j: JSONObject): Reminder? {
        val id = (j.opt("id") as? String)?.takeIf { ID.matches(it) } ?: return null
        val title = text(j.opt("title"), ReminderLimits.TITLE_MAX) ?: return null
        val person = (j.opt("person") as? String)?.takeIf { it.isEmpty() || ID.matches(it) } ?: ""
        return Reminder(
            id = id,
            title = title,
            person = person,
            forName = text(j.opt("for"), ReminderLimits.NAME_MAX) ?: ReminderLimits.HOME,
            due = whole(j.opt("due"))?.takeIf { it in 1..MAX_DUE } ?: 0L,
            done = j.opt("done") == true,
            by = text(j.opt("by"), ReminderLimits.BY_MAX).orEmpty(),
            seq = whole(j.opt("seq"))?.takeIf { it in 0..MAX_SEQ } ?: 0L,
        )
    }

    internal fun person(j: JSONObject): ReminderPerson? {
        val id = (j.opt("id") as? String)?.takeIf { ID.matches(it) } ?: return null
        val name = text(j.opt("name"), ReminderLimits.NAME_MAX) ?: return null
        return ReminderPerson(id, name, kid = j.opt("kid") == true)
    }

    private fun counts(j: JSONObject?): Map<String, Int> {
        if (j == null) return emptyMap()
        val out = sortedMapOf<String, Int>()
        for (key in j.keys()) {
            val name = text(key, ReminderLimits.NAME_MAX) ?: continue
            val n = whole(j.opt(key))?.takeIf { it in 1..ReminderLimits.MAX_PRIVATE.toLong() } ?: continue
            out[name] = (out[name] ?: 0) + n.toInt()
            if (out.size >= ReminderLimits.MAX_PEOPLE) break
        }
        return out.toMap()
    }

    private fun text(raw: Any?, max: Int): String? = (raw as? String)?.let { ChoreText.clean(it, max) }

    /** A JSON whole number; fractions, strings and NaN are refused. */
    private fun whole(raw: Any?): Long? = when (raw) {
        is Int -> raw.toLong()
        is Long -> raw
        is Number -> raw.toDouble().takeIf { it.isFinite() && it == Math.floor(it) && kotlin.math.abs(it) < 9.0e15 }?.toLong()
        else -> null
    }

    private fun objects(a: JSONArray?): List<JSONObject> =
        if (a == null) emptyList() else (0 until minOf(a.length(), ReminderLimits.MAX_REMINDERS * 2)).mapNotNull { a.opt(it) as? JSONObject }
}
