package com.portalinstaller.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileApplyStateTest {
    @Test fun `folds mixed results into succeeded and failed`() {
        val done = foldProfileResults(listOf("a" to true, "b" to false, "c" to true))
        assertEquals(listOf("a", "c"), done.succeeded)
        assertEquals(1, done.failed.size)
        assertEquals("b", done.failed.first().first)
    }

    @Test fun `all success has empty failed`() {
        val done = foldProfileResults(listOf("a" to true))
        assertEquals(emptyList<Pair<String, String>>(), done.failed)
    }
}
