/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.reminders

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

/** Phone copy for reminders: short and warm, the same voice as chores. Pure (JVM-tested). */
object ReminderCopy {
    const val OFFLINE = "Couldn’t reach your Portals. Check that this phone is on home Wi-Fi."
    private const val WEEK_DAYS = 6
    private const val NOON = 12

    fun refused(code: String): String = when (code) {
        "unknown_reminder" -> "That reminder was removed on another device."
        "unknown_person" -> "That person isn’t on your Portals any more."
        "bad_title" -> "Give it a title (a short one)."
        "stale", "conflict" -> "It changed on another device. Here’s the latest."
        "not_ready" -> "Set up the household key first."
        "too_many" -> "That’s a lot of reminders. Clear some first."
        "unauthorized" -> "Pair this phone with the Portal again."
        else -> "The Portal couldn’t do that. Try again."
    }

    /** "For Maya · 6 pm · by Phone". */
    fun line(r: Reminder, nowMs: Long, zone: ZoneId): String = listOfNotNull(
        "For ${r.forName}",
        r.due.takeIf { it > 0 }?.let { due(it, nowMs, zone) },
        r.by.takeIf { it.isNotEmpty() }?.let { "by $it" },
    ).joinToString(" · ")

    /** "🔒 2 private for Maya — only on Maya’s Home". */
    fun privateLine(name: String, count: Int): String = "🔒 $count private for $name — only on $name’s Home"

    /** "6 pm" today, "Tomorrow 9 am", "Fri 6:30 pm" this week, otherwise "12 Dec 6 pm". */
    fun due(dueMs: Long, nowMs: Long, zone: ZoneId): String {
        val at = Instant.ofEpochMilli(dueMs).atZone(zone)
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val days = at.toLocalDate().toEpochDay() - today.toEpochDay()
        val time = time(at.toLocalTime())
        return when {
            days == 0L -> time
            days == 1L -> "Tomorrow $time"
            days == -1L -> "Yesterday $time"
            days in 2..WEEK_DAYS -> "${weekday(at.toLocalDate())} $time"
            else -> "${at.dayOfMonth} ${at.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} $time"
        }
    }

    /** "6 pm", "6:30 pm", "12 am". */
    fun time(t: LocalTime): String {
        val hour = (t.hour % NOON).let { if (it == 0) NOON else it }
        val half = if (t.hour < NOON) "am" else "pm"
        return if (t.minute == 0) "$hour $half" else "$hour:%02d $half".format(t.minute)
    }

    private fun weekday(d: LocalDate) = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

    fun overdue(r: Reminder, nowMs: Long): Boolean = !r.done && r.due in 1 until nowMs
}

/** Due-time helpers for the editor. Pure: the clock and zone are passed in. */
object ReminderDue {
    const val EVENING_HOUR = 18
    const val MORNING_HOUR = 9

    /** [daysAhead] from today at [hour]:[minute] on this phone's clock. */
    fun at(nowMs: Long, zone: ZoneId, daysAhead: Int, hour: Int, minute: Int = 0): Long {
        val day = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().plusDays(daysAhead.toLong())
        return ZonedDateTime.of(LocalDateTime.of(day, LocalTime.of(hour, minute)), zone).toInstant().toEpochMilli()
    }

    /** Today at 6 pm, or null once 6 pm has passed. */
    fun thisEvening(nowMs: Long, zone: ZoneId): Long? = at(nowMs, zone, 0, EVENING_HOUR).takeIf { it > nowMs }

    fun tomorrowMorning(nowMs: Long, zone: ZoneId): Long = at(nowMs, zone, 1, MORNING_HOUR)

    /** A Material date picker's UTC-midnight selection plus a local time. */
    fun combine(utcDateMs: Long, hour: Int, minute: Int, zone: ZoneId): Long {
        val day = Instant.ofEpochMilli(utcDateMs).atZone(ZoneId.of("UTC")).toLocalDate()
        return ZonedDateTime.of(LocalDateTime.of(day, LocalTime.of(hour, minute)), zone).toInstant().toEpochMilli()
    }

    /** The UTC-midnight form of [ms]'s local date, for seeding a date picker. */
    fun pickerDate(ms: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()

    fun localTime(ms: Long, zone: ZoneId): LocalTime = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime()
}
