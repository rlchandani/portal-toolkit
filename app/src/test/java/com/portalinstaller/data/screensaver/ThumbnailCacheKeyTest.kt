package com.portalinstaller.data.screensaver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailCacheKeyTest {
    @Test
    fun `key changes when mtime or size changes`() {
        val a = ThumbnailCache.keyFor("/sdcard/x.jpg", "2024-08-01 12:04", 100L)
        val b = ThumbnailCache.keyFor("/sdcard/x.jpg", "2024-08-02 12:04", 100L)
        val c = ThumbnailCache.keyFor("/sdcard/x.jpg", "2024-08-01 12:04", 200L)
        assertTrue(a != b && a != c && b != c)
    }

    @Test
    fun `key stable for same inputs`() {
        assertEquals(
            ThumbnailCache.keyFor("/sdcard/x.jpg", "2024-08-01 12:04", 100L),
            ThumbnailCache.keyFor("/sdcard/x.jpg", "2024-08-01 12:04", 100L)
        )
    }
}
