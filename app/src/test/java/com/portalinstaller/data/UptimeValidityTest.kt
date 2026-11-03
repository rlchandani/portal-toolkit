/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UptimeValidityTest {
    @Test
    fun `NaN does not become a just booted uptime`() {
        assertNull(parseUptimeSeconds("NaN 12.00"))
    }

    @Test
    fun `positive infinity does not become an enormous uptime`() {
        assertNull(parseUptimeSeconds("Infinity 12.00"))
    }

    @Test
    fun `negative infinity is unavailable`() {
        assertNull(parseUptimeSeconds("-Infinity 12.00"))
    }

    @Test
    fun `negative fractional seconds do not truncate to zero`() {
        assertNull(parseUptimeSeconds("-0.50 12.00"))
    }

    @Test
    fun `zero seconds remains valid after boot`() {
        assertEquals(0L, parseUptimeSeconds("0.00 0.00"))
    }

    @Test
    fun `positive fractional seconds retain whole second truncation`() {
        assertEquals(12345L, parseUptimeSeconds("12345.67 23456.78"))
    }

    @Test
    fun `positive subsecond uptime remains valid`() {
        assertEquals(0L, parseUptimeSeconds("0.99 0.00"))
    }
}
