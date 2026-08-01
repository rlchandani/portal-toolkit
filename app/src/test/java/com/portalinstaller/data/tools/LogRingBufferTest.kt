package com.portalinstaller.data.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class LogRingBufferTest {

    private fun line(msg: String) = LogLine('I', "T", msg, "I/T( 1): $msg")

    @Test
    fun `append accumulates below capacity`() {
        val b = LogRingBuffer(3).append(line("a")).append(line("b"))
        assertEquals(listOf("a", "b"), b.lines.map { it.message })
    }

    @Test
    fun `append evicts oldest beyond capacity`() {
        val b = LogRingBuffer(2).append(line("a")).append(line("b")).append(line("c"))
        assertEquals(listOf("b", "c"), b.lines.map { it.message })
    }

    @Test
    fun `append returns a new instance leaving the original unchanged`() {
        val first = LogRingBuffer(2).append(line("a"))
        val second = first.append(line("b"))
        assertEquals(listOf("a"), first.lines.map { it.message })
        assertEquals(listOf("a", "b"), second.lines.map { it.message })
    }
}
