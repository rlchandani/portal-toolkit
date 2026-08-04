package com.portalinstaller.data.screensaver

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreensaverHintTest {

    @Test
    fun `zero photos shows no hint (empty-state text covers it)`() {
        assertEquals(HintLevel.None, screensaverHint(0, 20))
    }

    @Test
    fun `one photo suggests adding the remainder`() {
        assertEquals(HintLevel.AddMore(19), screensaverHint(1, 20))
    }

    @Test
    fun `just below threshold suggests adding one`() {
        assertEquals(HintLevel.AddMore(1), screensaverHint(19, 20))
    }

    @Test
    fun `exactly at threshold is ok`() {
        assertEquals(HintLevel.Ok, screensaverHint(20, 20))
    }

    @Test
    fun `above threshold is ok`() {
        assertEquals(HintLevel.Ok, screensaverHint(50, 20))
    }
}
