/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.list

import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class HouseholdListFormatTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private fun ms(day: Int, h: Int, m: Int) = LocalDateTime.of(2026, 11, day, h, m).atZone(zone).toInstant().toEpochMilli()
    private val now = ms(7, 8, 4) // Saturday 7 November 2026, 8:04
    private val format = HouseholdListFormat(zone, Locale.US)

    @Test fun shortWhen() {
        assertEquals("just now", format.short(now - 20_000, now))
        assertEquals("7:02 AM", format.short(ms(7, 7, 2), now))
        assertEquals("yesterday", format.short(ms(6, 21, 0), now))
        assertEquals("Thursday", format.short(ms(5, 9, 0), now))
    }

    @Test fun longWhen() {
        assertEquals("today 7:02 AM", format.long(ms(7, 7, 2), now))
        assertEquals("yesterday 9:00 PM", format.long(ms(6, 21, 0), now))
        assertEquals("Thursday 9:00 AM", format.long(ms(5, 9, 0), now))
    }
}
