package com.portalinstaller.viewmodel

import com.portalinstaller.data.screensaver.DedupeKey
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreensaverUploadPlanTest {
    @Test
    fun `override forces a duplicate back into the upload plan`() {
        val existing = setOf(DedupeKey("a.jpg", 100L))
        val plan = planUpload(
            picked = listOf("a.jpg" to 100L, "b.jpg" to 5L),
            existing = existing,
            overrides = setOf("a.jpg")
        )
        assertEquals(
            listOf("a.jpg" to 100L, "b.jpg" to 5L).sortedBy { it.first },
            plan.sortedBy { it.first }
        )
    }

    @Test
    fun `duplicates skipped by default`() {
        val existing = setOf(DedupeKey("a.jpg", 100L))
        val plan = planUpload(listOf("a.jpg" to 100L, "b.jpg" to 5L), existing, emptySet())
        assertEquals(listOf("b.jpg" to 5L), plan)
    }
}
