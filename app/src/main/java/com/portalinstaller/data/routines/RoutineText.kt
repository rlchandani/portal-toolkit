/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.routines

/** The prototype's editor validation, in its words. Null when [r] can be saved. */
object RoutineValidation {
    const val NAME_MAX = 24
    const val LABEL_MAX = 14
    const val MAX_CARDS = 8
    const val MAX_LEN = 180

    fun check(r: Routine, routines: List<Routine>): String? {
        if (r.name.isBlank()) return "Give the routine a name."
        if (r.cards.isEmpty()) return "Add at least one card."
        if (r.cards.any { it.label.isBlank() }) return "Every card needs a label."
        if (r.days.none { it }) return "Pick at least one day."
        if (r.end <= r.start) return "“Ready by” must be after the start time."
        if (r.end - r.start < 5) return "Give it at least 5 minutes."
        if (r.end - r.start > MAX_LEN) return "Keep it under 3 hours."
        val clash = routines.firstOrNull { o -> o.id != r.id && o.days.indices.any { o.days[it] && r.days[it] } && r.start < o.end && o.start < r.end }
        if (clash != null) return "Overlaps ${clash.name} (${RoutineDates.time12(clash.start)} to ${RoutineDates.time12(clash.end)})."
        return null
    }
}

/** Phone copy: day summaries, skip hints and the routine's status from the Portal's last report. */
object RoutineText {
    enum class Kind { RUNNING, DONE, CLOSED, OFF, SKIP, NEXT, NONE }
    data class Status(val kind: Kind, val text: String, val run: RoutineRunInfo? = null)
    data class Next(val day: Int, val label: String)

    fun daysText(days: List<Boolean>): String = when {
        days.all { it } -> "Every day"
        days.take(5).all { it } && !days[5] && !days[6] -> "Mon to Fri"
        days.none { it } -> "No days"
        days.take(5).none { it } && days[5] && days[6] -> "Weekends"
        else -> days.indices.filter { days[it] }.joinToString(", ") { RoutineDates.DAY_NAMES[it].take(3) }
    }

    fun skipHint(days: List<Boolean>): String {
        val off = RoutineDates.DAY_NAMES.filterIndexed { i, _ -> !days[i] }
        return when {
            off.isEmpty() -> "Runs every day."
            off.size == 7 -> "Pick at least one day."
            off.size > 2 -> "Skips ${off.dropLast(1).joinToString(", ")} and ${off.last()}."
            else -> "Skips ${off.joinToString(" and ")}."
        }
    }

    fun shortDate(day: Int): String {
        val (_, m, d) = RoutineDates.civil(day)
        return "${RoutineDates.DAY_NAMES[RoutineDates.weekday(day)].take(3)} $d ${RoutineDates.MONTHS[m - 1].take(3)}"
    }

    fun dayRowLabel(day: Int, today: Int) = when (day - today) { 0 -> "Today"; 1 -> "Tomorrow"; else -> shortDate(day) }

    private fun skipped(r: Routine, day: Int): Boolean =
        !r.enabled || !r.days[RoutineDates.weekday(day)] || RoutineDates.isoOf(day) in r.skips

    fun next(s: RoutineSnapshot, r: Routine): Next? {
        for (d in 0 until 14) {
            val day = s.day + d
            if (skipped(r, day)) continue
            if (d == 0 && (s.minute >= r.end || s.today.any { it.rid == r.id })) continue
            return Next(day, when { d == 0 -> "Today"; d == 1 -> "Tomorrow"; d < 7 -> RoutineDates.DAY_NAMES[RoutineDates.weekday(day)]; else -> shortDate(day) })
        }
        return null
    }

    fun status(s: RoutineSnapshot, r: Routine, room: String): Status {
        s.today.firstOrNull { it.rid == r.id }?.let { run ->
            val at = run.endedAt?.let(RoutineDates::time12) ?: ""
            return when (run.status) {
                "running" -> Status(Kind.RUNNING, "On $room now, ${run.done} of ${run.total} done", run)
                "done" -> Status(Kind.DONE, "Done today at $at", run)
                else -> Status(Kind.CLOSED, when (run.endedBy) {
                    "auto" -> "Closed itself at $at, ${run.done} of ${run.total} done"
                    "phone" -> "Closed from Portal Toolkit at $at"
                    else -> "Closed on $room at $at"
                }, run)
            }
        }
        if (!r.enabled) return Status(Kind.OFF, "Off. It won’t start until you switch it on.")
        if (RoutineDates.isoOf(s.day) in r.skips && s.minute < r.end) return Status(Kind.SKIP, "Skipped today")
        val n = next(s, r) ?: return Status(Kind.NONE, "Nothing scheduled in the next 2 weeks")
        return Status(Kind.NEXT, "Next: ${n.label}, ${RoutineDates.time12(r.start)}")
    }

    fun savedText(s: RoutineSnapshot, r: Routine, room: String): String {
        if (!r.enabled) return "Saved. ${r.name} is off, so it won’t start."
        val n = next(s, r) ?: return "Saved."
        val whenText = if (n.label == "Today" || n.label == "Tomorrow") n.label.lowercase() else n.label
        return "Saved. Starts on $room $whenText at ${RoutineDates.time12(r.start)}."
    }
}
