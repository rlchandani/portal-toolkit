package com.portalinstaller.viewmodel

import org.junit.Assert.*
import org.junit.Test

class ReliabilityOutcomesTest {
    @Test fun `interrupted file batch retains success and failure and marks remaining items retryable`() {
        val results = interruptedFileResults(
            listOf("one", "two", "three"), "push",
            listOf(FileItemResult("one", "push", true), FileItemResult("two", "push", false, "disk full"))
        )
        assertTrue(results[0].succeeded)
        assertEquals("disk full", results[1].message)
        assertEquals("three", results[2].path)
        assertFalse(results[2].succeeded)
        assertTrue(results[2].cancelled)
    }

    @Test fun `cancelled profile cannot report success for missing results`() {
        val result = foldProfileResults(listOf("one" to true), "profile", emptyMap(),
            listOf("one", "two", "three"), cancelled = true)
        assertTrue(result.cancelled)
        assertEquals(listOf("one"), result.succeeded)
        assertEquals(listOf("two", "three"), result.failed.map { it.first })
    }
}
