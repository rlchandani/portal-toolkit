/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.chores

import java.util.TimeZone

/** Local calendar days for chores: epoch days in the device's zone; weeks start on Monday. */
object ChoreDays {
    const val DAY_MS = 86_400_000L
    private const val DAYS_PER_WEEK = 7
    /** Epoch day 0 (1 Jan 1970) was a Thursday: ISO weekday 4. */
    private const val EPOCH_SHIFT = 3

    fun day(nowMs: Long, zone: TimeZone): Int = Math.floorDiv(nowMs + zone.getOffset(nowMs), DAY_MS).toInt()

    /** ISO weekday: Monday 1 … Sunday 7. */
    fun weekday(day: Int): Int = Math.floorMod(day + EPOCH_SHIFT, DAYS_PER_WEEK) + 1

    /** The Monday of [day]'s week. */
    fun weekStart(day: Int): Int = day - (weekday(day) - 1)

    /** The oldest day whose ticks are kept: the Monday [ChoreLimits.HISTORY_WEEKS] weeks back. */
    fun keepFrom(today: Int): Int = weekStart(today) - ChoreLimits.HISTORY_WEEKS * DAYS_PER_WEEK

    fun isDue(chore: Chore, day: Int): Boolean = !chore.deleted && when (val s = chore.schedule) {
        ChoreSchedule.Daily -> true
        is ChoreSchedule.Weekdays -> weekday(day) in s.days
        is ChoreSchedule.Once -> s.day == day
    }
}

/**
 * Derived views of a [ChoreState]: today's chores per kid, weekly totals and history. Pure, so the
 * Home widget, the phone route and the tests read exactly the same numbers.
 */
object ChoreBoard {
    /** One chore on a kid's row today; [tick] is the tick holding it (done or waiting), if any. */
    data class Item(val chore: Chore, val tick: ChoreTick?) {
        val done: Boolean get() = tick != null
        val waiting: Boolean get() = tick?.status == TickStatus.PENDING
    }

    data class Week(val kid: String, val start: Int, val stars: Int, val pending: Int, val goal: Int, val reward: String) {
        val reached: Boolean get() = goal > 0 && stars >= goal
    }

    data class KidToday(val kid: ChoreKid, val items: List<Item>, val week: Week)

    /** Each live kid with today's chores (their own, then "anyone" chores nobody else has done). */
    fun today(state: ChoreState, day: Int): List<KidToday> {
        val holders = holders(state)
        val due = state.liveChores.filter { ChoreDays.isDue(it, day) }
        val start = ChoreDays.weekStart(day)
        return state.liveKids.map { kid ->
            val items = due.mapNotNull { chore ->
                val tick = holders[slot(chore.id, day)]
                when {
                    chore.kid == kid.id -> Item(chore, tick)
                    chore.kid == null && (tick == null || tick.kid == kid.id) -> Item(chore, tick)
                    else -> null
                }
            }
            KidToday(kid, items, week(state, kid, start, holders))
        }
    }

    /** This week's totals for every live kid. */
    fun week(state: ChoreState, day: Int): List<Week> {
        val holders = holders(state)
        return state.liveKids.map { week(state, it, ChoreDays.weekStart(day), holders) }
    }

    /** The [ChoreLimits.HISTORY_WEEKS] weeks before [day]'s week, newest first. */
    fun history(state: ChoreState, day: Int): List<List<Week>> {
        val holders = holders(state)
        val start = ChoreDays.weekStart(day)
        return (1..ChoreLimits.HISTORY_WEEKS).map { back ->
            state.liveKids.map { week(state, it, start - back * 7, holders) }
        }
    }

    /** Ticks waiting for a parent's OK, oldest day first. */
    fun pending(state: ChoreState): List<ChoreTick> = holders(state).values
            .filter { it.status == TickStatus.PENDING }.sortedWith(compareBy({ it.day }, { it.id }))

    /**
     * Kids whose weekly total reached their goal between [before] and [after] because of stars that
     * started counting now: a tick made today, or a parent's OK for a waiting one. A Portal catching
     * up on last Tuesday's ticks doesn't celebrate.
     */
    fun goalsReached(before: ChoreState, after: ChoreState, day: Int): List<String> {
        val was = week(before, day).associateBy { it.kid }
        val counted = holders(before).values.filter { it.status.counts }.map { it.id }.toSet()
        val fresh = holders(after).values.filter { t ->
            t.status.counts && t.id !in counted && (t.day == day || before.ticks[t.id]?.status == TickStatus.PENDING)
        }.map { it.kid }.toSet()
        return week(after, day).filter { w -> w.reached && was[w.kid]?.reached != true && w.kid in fresh }.map { it.kid }
    }

    /** The tick holding each (chore, day): among those still holding it, the lowest id wins. */
    fun holders(state: ChoreState): Map<String, ChoreTick> = state.ticks.values.filter { it.status.holds }
            .groupBy { slot(it.chore, it.day) }.mapValues { (_, ticks) -> ticks.minBy { it.id } }

    fun slot(chore: String, day: Int): String = "$chore@$day"

    private fun week(state: ChoreState, kid: ChoreKid, start: Int, holders: Map<String, ChoreTick>): Week {
        val mine = holders.values.filter { it.kid == kid.id && it.day in start until start + 7 }
        return Week(kid.id, start, stars = mine.filter { it.status.counts }.sumOf { it.stars },
                pending = mine.filter { it.status == TickStatus.PENDING }.sumOf { it.stars }, goal = kid.goal, reward = kid.reward)
    }

    /** "12 / 20 ★ — Movie night", "12 / 20 ★" or "12 ★ this week". */
    fun goalLine(w: Week): String = when {
        w.goal <= 0 -> "${w.stars} ★ this week"
        w.reward.isBlank() -> "${w.stars} / ${w.goal} ★"
        else -> "${w.stars} / ${w.goal} ★ — ${w.reward}"
    }

    /** 0..1 for the goal bar. */
    fun progress(w: Week): Float = if (w.goal <= 0) 0f else (w.stars.toFloat() / w.goal).coerceIn(0f, 1f)
}
