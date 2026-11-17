/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.chores

import org.json.JSONArray
import org.json.JSONObject

/** What the parent edits for a kid on the phone. [id] null = a new kid. */
data class KidDraft(
    val id: String? = null,
    val name: String = "",
    val avatar: String = ChoreAvatars.all.first(),
    val color: Int = 0,
    val goal: Int = 0,
    val reward: String = "",
    val seq: Long = 0L,
) {
    val valid: Boolean get() = ChoreText.clean(name, ChoreLimits.NAME_MAX) != null && goal in 0..ChoreLimits.MAX_GOAL

    companion object {
        /** A new kid gets the next avatar and colour so siblings look different at a glance. */
        fun next(existing: Int) = KidDraft(avatar = ChoreAvatars.all[existing % ChoreAvatars.all.size], color = existing % ChoreLimits.COLORS)

        internal fun of(k: ChoreKid) = KidDraft(k.id, k.name, k.avatar, k.color, k.goal, k.reward, k.seq)
    }
}

enum class ChoreRepeat(val wire: String, val label: String) { DAILY("daily", "Every day"), DAYS("days", "Some days"), ONCE("once", "Just once") }

/** What the parent edits for a chore. [kid] null = anyone; [days] ISO weekdays; [day] epoch day. */
data class ChoreDraft(
    val id: String? = null,
    val title: String = "",
    val pic: String = "bed",
    val kid: String? = null,
    val stars: Int = 1,
    val repeat: ChoreRepeat = ChoreRepeat.DAILY,
    val days: Set<Int> = setOf(1, 2, 3, 4, 5),
    val day: Int = 0,
    val needsOk: Boolean = false,
    val seq: Long = 0L,
) {
    val valid: Boolean get() = ChoreText.clean(title, ChoreLimits.TITLE_MAX) != null &&
        stars in ChoreLimits.MIN_STARS..ChoreLimits.MAX_STARS && (repeat != ChoreRepeat.DAYS || days.isNotEmpty())

    companion object {
        internal fun of(c: Chore) = ChoreDraft(c.id, c.title, c.pic, c.kid, c.stars,
            repeat = when (c.schedule) { ChoreSchedule.Daily -> ChoreRepeat.DAILY; is ChoreSchedule.Weekdays -> ChoreRepeat.DAYS; is ChoreSchedule.Once -> ChoreRepeat.ONCE },
            days = (c.schedule as? ChoreSchedule.Weekdays)?.days ?: setOf(1, 2, 3, 4, 5),
            day = (c.schedule as? ChoreSchedule.Once)?.day ?: 0, needsOk = c.needsOk, seq = c.seq)
    }
}

/** A phone command for `/remote/chores` POST (see the Portal's ChoreRequests). */
sealed interface ChoreCommand {
    data class SaveKid(val draft: KidDraft) : ChoreCommand
    data class DeleteKid(val id: String) : ChoreCommand
    data class SaveChore(val draft: ChoreDraft) : ChoreCommand
    data class DeleteChore(val id: String) : ChoreCommand
    data class Approve(val tick: String) : ChoreCommand
    data class Decline(val tick: String) : ChoreCommand
    data class TakeBack(val tick: String) : ChoreCommand

    fun body(): JSONObject = when (this) {
        is SaveKid -> JSONObject().put("action", "kid_save").put("kid", JSONObject().put("id", draft.id ?: "")
            .put("name", draft.name.trim()).put("avatar", draft.avatar).put("color", draft.color).put("goal", draft.goal)
            .put("reward", draft.reward.trim()).put("seq", draft.seq))
        is DeleteKid -> JSONObject().put("action", "kid_delete").put("id", id)
        is SaveChore -> JSONObject().put("action", "chore_save").put("chore", JSONObject().put("id", draft.id ?: "")
            .put("title", draft.title.trim()).put("pic", draft.pic).put("kid", draft.kid ?: "").put("stars", draft.stars)
            .put("repeat", draft.repeat.wire).put("days", JSONArray(draft.days.sorted())).put("day", draft.day)
            .put("needsOk", draft.needsOk).put("seq", draft.seq))
        is DeleteChore -> JSONObject().put("action", "chore_delete").put("id", id)
        is Approve -> JSONObject().put("action", "approve").put("id", tick)
        is Decline -> JSONObject().put("action", "decline").put("id", tick)
        is TakeBack -> JSONObject().put("action", "take_back").put("id", tick)
    }
}

/** Phone copy: short and warm. */
object ChoreCopy {
    const val OFFLINE = "Couldn’t reach your Portals. Check that this phone is on home Wi-Fi."
    private val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    fun refused(code: String): String = when (code) {
        "too_many_kids" -> "That’s the most kids for now (${ChoreLimits.MAX_KIDS})."
        "too_many_chores" -> "That’s a lot of chores. Remove one first."
        "unknown_kid" -> "That kid was removed on another device."
        "unknown_chore" -> "That chore was removed on another device."
        "unknown_tick", "invalid_state" -> "That was already sorted on another device."
        "invalid_name", "invalid_title" -> "Use a shorter name."
        "unauthorized" -> "Pair this phone with the Portal again."
        else -> "The Portal couldn’t do that. Try again."
    }

    fun schedule(s: ChoreSchedule, today: Int): String = when (s) {
        ChoreSchedule.Daily -> "Every day"
        is ChoreSchedule.Weekdays -> when (s.days) {
            setOf(1, 2, 3, 4, 5) -> "Weekdays"
            setOf(6, 7) -> "Weekends"
            else -> s.days.sorted().joinToString(", ") { WEEKDAYS[it - 1] }
        }
        is ChoreSchedule.Once -> when (s.day - today) {
            0 -> "Today only"
            1 -> "Tomorrow only"
            else -> if (s.day < today) "Done and dusted" else "Once, on ${WEEKDAYS[ChoreDays.weekday(s.day) - 1]} (in ${s.day - today} days)"
        }
    }

    fun weekday(day: Int): String = WEEKDAYS[day - 1]

    fun stars(n: Int): String = if (n == 1) "1 star" else "$n stars"

    /** "Mia · Make bed · Tue". */
    fun pendingLine(kid: String, chore: String, tickDay: Int, today: Int): String =
        "$kid · $chore · " + if (tickDay == today) "Today" else WEEKDAYS[ChoreDays.weekday(tickDay) - 1]
}
