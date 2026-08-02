package com.portalinstaller.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemInfoParserTest {

    // Iesire reala de pe Portal (kB); vrem total si disponibil in bytes.
    private val portalSample = """
        MemTotal:        2848792 kB
        MemFree:           91852 kB
        MemAvailable:     853620 kB
        Buffers:           12345 kB
    """.trimIndent()

    @Test
    fun `parses total and available in bytes`() {
        val info = MemInfoParser.parse(portalSample)
        assertEquals(2848792L * 1024, info?.totalBytes)
        assertEquals(853620L * 1024, info?.availableBytes)
    }

    @Test
    fun `used fraction is total minus available over total`() {
        val info = MemInfoParser.parse(portalSample)!!
        // used = 2848792 - 853620 = 1995172 kB; fraction ~= 0.70
        assertEquals(0.70f, info.usedFraction, 0.01f)
    }

    @Test
    fun `falls back to MemFree when MemAvailable is absent`() {
        val noAvailable = "MemTotal: 2000000 kB\nMemFree: 500000 kB"
        val info = MemInfoParser.parse(noAvailable)
        assertEquals(2000000L * 1024, info?.totalBytes)
        assertEquals(500000L * 1024, info?.availableBytes)
    }

    @Test
    fun `returns null when MemTotal is missing`() {
        assertNull(MemInfoParser.parse("MemFree: 500000 kB"))
    }

    @Test
    fun `returns null on blank input`() {
        assertNull(MemInfoParser.parse(""))
    }
}
