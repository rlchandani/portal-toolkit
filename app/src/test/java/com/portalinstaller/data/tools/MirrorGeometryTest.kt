/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class MirrorGeometryTest {
    private val portal = MirrorGeometry(2160, 1440)

    @Test
    fun `folded Pixel gives Portal a 1080 by 720 surface`() {
        assertEquals(1080 to 720, portal.fitWithin(1080, 2424))
        assertEquals(1.5f, portal.aspectRatio, 0f)
    }

    @Test
    fun `phone rotation fits by height instead of stretching by width`() {
        assertEquals(1620 to 1080, portal.fitWithin(2424, 1080))
    }

    @Test
    fun `reconnected portrait device uses its new dimensions`() {
        val portrait = MirrorGeometry(1440, 2160)
        assertEquals(1080 to 1620, portrait.fitWithin(1080, 2424))
        assertEquals(720 to 1080, portrait.fitWithin(2424, 1080))
        assertEquals(720 to 1080, portrait.mapSurfacePoint(540f, 810f, 1080, 1620))
    }

    @Test
    fun `quarter height tap maps relative to fitted surface without letterbox offsets`() {
        assertEquals(1080 to 360, portal.mapSurfacePoint(540f, 180f, 1080, 720))
        assertEquals(1080 to 1080, portal.mapSurfacePoint(540f, 540f, 1080, 720))
    }

    @Test
    fun `rounded layout pixels map using the exact measured surface`() {
        assertEquals(1000 to 667, portal.fitWithin(1000, 2424))
        assertEquals(1080 to 360, portal.mapSurfacePoint(500f, 166.75f, 1000, 667))
        assertEquals(1080 to 1080, portal.mapSurfacePoint(500f, 500.25f, 1000, 667))
    }

    @Test
    fun `drag endpoints clamp to valid device pixels`() {
        assertEquals(0 to 0, portal.mapSurfacePoint(-20f, -10f, 1080, 720))
        assertEquals(2159 to 1439, portal.mapSurfacePoint(1100f, 740f, 1080, 720))
        assertEquals(2159 to 1439, portal.mapSurfacePoint(1080f, 720f, 1080, 720))
    }

    @Test
    fun `empty layout cannot size or map input`() {
        assertEquals(0 to 0, portal.fitWithin(0, 100))
        assertEquals(0 to 0, portal.fitWithin(100, 0))
        assertNull(portal.mapSurfacePoint(0f, 0f, 0, 720))
        assertNull(portal.mapSurfacePoint(0f, 0f, 1080, 0))
        assertNull(portal.mapSurfacePoint(0f, 0f, -1, 720))
    }

    @Test
    fun `nonfinite gesture coordinates are rejected`() {
        assertNull(portal.mapSurfacePoint(Float.NaN, 0f, 1080, 720))
        assertNull(portal.mapSurfacePoint(0f, Float.POSITIVE_INFINITY, 1080, 720))
    }

    @Test
    fun `invalid device dimensions cannot be published`() {
        listOf(0 to 1440, 2160 to 0, -1 to 1440, 8193 to 1440, 2160 to 8193).forEach {
            assertThrows(IllegalArgumentException::class.java) { MirrorGeometry(it.first, it.second) }
        }
    }

    @Test
    fun `small available bounds never overflow or produce negative sizes`() {
        assertEquals(1 to 1, portal.fitWithin(1, 1))
        assertEquals(1 to 1, MirrorGeometry(1, 8192).fitWithin(1, 1))
        assertEquals(1 to 1, MirrorGeometry(8192, 1).fitWithin(1, 1))
    }
}
