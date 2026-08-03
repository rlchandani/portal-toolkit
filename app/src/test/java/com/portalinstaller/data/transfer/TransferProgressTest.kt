package com.portalinstaller.data.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferProgressTest {

    @Test fun `fraction is half at midpoint`() =
        assertEquals(0.5f, TransferProgress(50L, 100L).fraction, 0.0001f)

    @Test fun `fraction is zero when total unknown`() =
        assertEquals(0f, TransferProgress(50L, 0L).fraction, 0.0001f)

    @Test fun `fraction is zero when total negative`() =
        assertEquals(0f, TransferProgress(50L, -1L).fraction, 0.0001f)

    @Test fun `fraction clamps above one`() =
        assertEquals(1f, TransferProgress(150L, 100L).fraction, 0.0001f)

    @Test fun `fraction clamps below zero`() =
        assertEquals(0f, TransferProgress(-10L, 100L).fraction, 0.0001f)

    @Test fun `determinate only when total positive`() {
        assertTrue(TransferProgress(0L, 100L).isDeterminate)
        assertFalse(TransferProgress(0L, 0L).isDeterminate)
        assertFalse(TransferProgress(0L, -5L).isDeterminate)
    }
}
