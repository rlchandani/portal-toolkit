package com.portalinstaller.data.screensaver

import org.junit.Assert.assertEquals
import org.junit.Test

class DedupeMatcherTest {
    @Test
    fun `matches by name and size, skips duplicates`() {
        val existing = setOf(DedupeKey("a.jpg", 100L))
        val (fresh, dup) = DedupeMatcher.partition(
            candidates = listOf("a.jpg" to 100L, "a.jpg" to 200L, "b.jpg" to 50L),
            existing = existing
        )
        // Acelasi nume dar dimensiune diferita = fisier nou.
        assertEquals(listOf("a.jpg" to 200L, "b.jpg" to 50L), fresh)
        assertEquals(listOf("a.jpg" to 100L), dup)
    }

    @Test
    fun `empty existing set means everything is fresh`() {
        val (fresh, dup) = DedupeMatcher.partition(
            candidates = listOf("x.png" to 1L, "y.png" to 2L),
            existing = emptySet()
        )
        assertEquals(listOf("x.png" to 1L, "y.png" to 2L), fresh)
        assertEquals(emptyList<Pair<String, Long>>(), dup)
    }
}
