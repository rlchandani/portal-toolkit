package com.portalinstaller.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectBackoffTest {

    private val backoff = ReconnectBackoff()

    @Test
    fun `doubles from base up to the cap`() {
        assertEquals(2_000L, backoff.delayFor(1))
        assertEquals(4_000L, backoff.delayFor(2))
        assertEquals(8_000L, backoff.delayFor(3))
        assertEquals(16_000L, backoff.delayFor(4))
    }

    @Test
    fun `caps at maxMs for high attempt counts`() {
        assertEquals(30_000L, backoff.delayFor(5))
        assertEquals(30_000L, backoff.delayFor(6))
        assertEquals(30_000L, backoff.delayFor(50))
    }

    @Test
    fun `treats non-positive attempts as the first attempt`() {
        assertEquals(2_000L, backoff.delayFor(0))
        assertEquals(2_000L, backoff.delayFor(-3))
    }

    @Test
    fun `honours custom base and cap`() {
        val custom = ReconnectBackoff(baseMs = 1_000, maxMs = 5_000)
        assertEquals(1_000L, custom.delayFor(1))
        assertEquals(2_000L, custom.delayFor(2))
        assertEquals(4_000L, custom.delayFor(3))
        assertEquals(5_000L, custom.delayFor(4))
        assertEquals(5_000L, custom.delayFor(10))
    }
}
