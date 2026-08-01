package com.portalinstaller.data

import com.portalinstaller.ui.common.formatUptime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UptimeParserTest {

    @Test
    fun `parses the first float of proc uptime as whole seconds`() {
        assertEquals(12345L, parseUptimeSeconds("12345.67 23456.78"))
    }

    @Test
    fun `handles a single value without idle time`() {
        assertEquals(42L, parseUptimeSeconds("42.99"))
    }

    @Test
    fun `returns null for blank or malformed input`() {
        assertNull(parseUptimeSeconds(""))
        assertNull(parseUptimeSeconds("   "))
        assertNull(parseUptimeSeconds("not-a-number here"))
    }

    @Test
    fun `formats days and hours`() {
        // 14d 6h = 14*86400 + 6*3600 = 1_231_200 s
        assertEquals("14d 6h", formatUptime(1_231_200))
    }

    @Test
    fun `formats hours and minutes below a day`() {
        // 3h 25m = 3*3600 + 25*60 = 12_300 s
        assertEquals("3h 25m", formatUptime(12_300))
    }

    @Test
    fun `formats minutes below an hour`() {
        assertEquals("7m", formatUptime(7 * 60 + 30))
    }

    @Test
    fun `formats seconds below a minute`() {
        assertEquals("45s", formatUptime(45))
    }
}
