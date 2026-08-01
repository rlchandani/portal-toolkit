package com.portalinstaller.data.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppMetadataCacheTest {
    @Test
    fun `key combines package and version code`() {
        assertEquals("com.x.y@42", AppMetadataCache.keyFor("com.x.y", 42L))
    }

    @Test
    fun `different version yields different key`() {
        assertTrue(AppMetadataCache.keyFor("com.x", 1L) != AppMetadataCache.keyFor("com.x", 2L))
    }
}
