/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.house

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** "since 7:25 pm" today, "since Tue" this week, else "since 3 Nov" (the Portal's Security wording). */
internal object HouseTime {
    private val time = ThreadLocal.withInitial { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    private val day = ThreadLocal.withInitial { SimpleDateFormat("EEE", Locale.getDefault()) }
    private val date = ThreadLocal.withInitial { SimpleDateFormat("d MMM", Locale.getDefault()) }
    private const val WEEK_MS = 7 * 24 * 60 * 60 * 1000L

    fun clock(ms: Long): String = time.get()?.format(Date(ms)).orEmpty().lowercase()

    fun since(ms: Long, now: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): String? {
        if (ms <= 0) return null
        val a = Calendar.getInstance(zone).apply { timeInMillis = ms }
        val b = Calendar.getInstance(zone).apply { timeInMillis = now }
        val sameDay = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        return "since " + when {
            sameDay -> clock(ms)
            now - ms < WEEK_MS -> day.get()?.format(Date(ms)).orEmpty()
            else -> date.get()?.format(Date(ms)).orEmpty()
        }
    }
}
