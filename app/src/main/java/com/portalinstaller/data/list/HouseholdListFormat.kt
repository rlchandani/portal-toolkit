/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.list

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "just now" / "7:02 AM" / "yesterday" / "Thursday", and the longer "today 7:02 AM" form. */
class HouseholdListFormat(private val zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()) {
    private val time = DateTimeFormatter.ofPattern("h:mm a", locale)
    private val weekday = DateTimeFormatter.ofPattern("EEEE", locale)

    fun short(ms: Long, now: Long): String = when (daysAgo(ms, now)) {
        null -> "just now"
        0L -> time.format(at(ms))
        1L -> "yesterday"
        else -> weekday.format(at(ms))
    }

    fun long(ms: Long, now: Long): String = when (daysAgo(ms, now)) {
        null -> "just now"
        0L -> "today ${time.format(at(ms))}"
        1L -> "yesterday ${time.format(at(ms))}"
        else -> "${weekday.format(at(ms))} ${time.format(at(ms))}"
    }

    /** Null for the last minute; otherwise whole calendar days between [ms] and [now]. */
    private fun daysAgo(ms: Long, now: Long): Long? {
        if (now - ms in 0 until MINUTE) return null
        return java.time.temporal.ChronoUnit.DAYS.between(at(ms).toLocalDate(), at(now).toLocalDate()).coerceAtLeast(0)
    }

    private fun at(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone)

    private companion object { const val MINUTE = 60_000L }
}
