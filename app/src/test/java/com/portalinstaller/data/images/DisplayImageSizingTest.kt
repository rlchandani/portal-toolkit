/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.images

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayImageSizingTest {
    @Test
    fun `exact fits keep the original resolution`() {
        assertEquals(1, companionImageSampleSize(512, 512, 512))
        assertEquals(1, companionImageSampleSize(512, 384, 512))
        assertEquals(1, companionImageSampleSize(384, 512, 512))
    }

    @Test
    fun `smaller images are never upscaled`() {
        assertEquals(1, companionImageSampleSize(320, 240, 512))
        assertEquals(1, companionImageSampleSize(1, 1, 512))
        assertEquals(1, companionImageSampleSize(1, 512, 1024))
    }

    @Test
    fun `4096 by 3072 thumbnail fits a 512 edge with sample eight`() {
        assertEquals(8, companionImageSampleSize(4096, 3072, 512))
    }

    @Test
    fun `4096 by 3072 preview fits a 1024 edge with sample four`() {
        assertEquals(4, companionImageSampleSize(4096, 3072, 1024))
    }

    @Test
    fun `4096 by 3072 small thumbnail fits a 256 edge with sample sixteen`() {
        assertEquals(16, companionImageSampleSize(4096, 3072, 256))
    }

    @Test
    fun `one pixel beyond either original edge requires downsampling`() {
        assertEquals(2, companionImageSampleSize(513, 512, 512))
        assertEquals(2, companionImageSampleSize(512, 513, 512))
    }

    @Test
    fun `ceiling division prevents an odd sampled edge exceeding the target`() {
        assertEquals(2, companionImageSampleSize(1023, 511, 512))
        assertEquals(2, companionImageSampleSize(1024, 511, 512))
        assertEquals(4, companionImageSampleSize(1025, 511, 512))
        assertEquals(4, companionImageSampleSize(511, 1025, 512))
        assertEquals(16, companionImageSampleSize(4097, 3072, 512))
    }

    @Test
    fun `portrait sampling is constrained by height`() {
        assertEquals(8, companionImageSampleSize(3072, 4096, 512))
        assertEquals(4, companionImageSampleSize(3072, 4096, 1024))
        assertEquals(16, companionImageSampleSize(3072, 4096, 256))
    }

    @Test
    fun `extreme panoramas remain valid in either orientation`() {
        assertEquals(4_194_304, companionImageSampleSize(Int.MAX_VALUE, 1, 512))
        assertEquals(4_194_304, companionImageSampleSize(1, Int.MAX_VALUE, 512))
        assertEquals(128, companionImageSampleSize(65, 1, 1))
        assertEquals(128, companionImageSampleSize(1, 65, 1))
    }

    @Test
    fun `large source pixel counts do not reject a representable sample`() {
        assertEquals(4_194_304, companionImageSampleSize(Int.MAX_VALUE, Int.MAX_VALUE, 512))
        assertEquals(128, companionImageSampleSize(65_536, 65_536, 512))
    }

    @Test
    fun `non power of two targets still return decoder power of two samples`() {
        assertEquals(8, companionImageSampleSize(4096, 3072, 1000))
        assertEquals(2, companionImageSampleSize(513, 257, 257))
        assertEquals(4, companionImageSampleSize(10, 7, 3))
    }

    @Test
    fun `zero dimensions are invalid`() {
        assertNull(companionImageSampleSize(0, 512, 512))
        assertNull(companionImageSampleSize(512, 0, 512))
        assertNull(companionImageSampleSize(0, 0, 512))
    }

    @Test
    fun `negative dimensions including minimum Int are invalid`() {
        for (dimension in listOf(-1, Int.MIN_VALUE)) {
            assertNull(companionImageSampleSize(dimension, 512, 512))
            assertNull(companionImageSampleSize(512, dimension, 512))
        }
    }

    @Test
    fun `nonpositive targets including minimum Int are invalid`() {
        for (target in listOf(0, -1, Int.MIN_VALUE)) {
            assertNull(companionImageSampleSize(512, 512, target))
        }
    }

    @Test
    fun `largest positive Int power of two is a valid sample`() {
        assertEquals(1 shl 30, companionImageSampleSize(1 shl 30, 1, 1))
        assertEquals(1 shl 30, companionImageSampleSize(1, 1 shl 30, 1))
        assertEquals(1 shl 30, companionImageSampleSize((1 shl 30) - 1, 1, 1))
    }

    @Test
    fun `ceiling arithmetic near maximum Int does not overflow`() {
        assertEquals(1 shl 30, companionImageSampleSize(Int.MAX_VALUE, Int.MAX_VALUE, 2))
        assertEquals(2, companionImageSampleSize(Int.MAX_VALUE, 1, 1 shl 30))
        assertEquals(2, companionImageSampleSize(1, Int.MAX_VALUE, Int.MAX_VALUE - 1))
    }

    @Test
    fun `maximum Int target accepts maximum dimensions without sampling`() {
        assertEquals(1, companionImageSampleSize(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(1, companionImageSampleSize(1, 1, Int.MAX_VALUE))
    }

    @Test
    fun `required samples beyond the positive Int range return null`() {
        assertNull(companionImageSampleSize((1 shl 30) + 1, 1, 1))
        assertNull(companionImageSampleSize(1, (1 shl 30) + 1, 1))
        assertNull(companionImageSampleSize(Int.MAX_VALUE, 1, 1))
        assertNull(companionImageSampleSize(1, Int.MAX_VALUE, 1))
        assertNull(companionImageSampleSize(Int.MAX_VALUE, Int.MAX_VALUE, 1))
    }

    @Test
    fun `boundary matrix satisfies decoder power of two bounds and minimality`() {
        val dimensions = listOf(
            1, 2, 3, 255, 256, 257, 511, 512, 513, 1023, 1024, 1025,
            4096, 65_537, 1 shl 30, (1 shl 30) + 1, Int.MAX_VALUE
        )
        val targets = listOf(1, 2, 3, 255, 256, 257, 512, 1000, 1024, Int.MAX_VALUE)
        for (width in dimensions) {
            for (height in dimensions) {
                for (target in targets) {
                    val input = "${width}x$height at $target"
                    val sample = companionImageSampleSize(width, height, target)
                    // Multiplication gives an independent check of ceil(dimension / sample) <= target.
                    val largestCapacity = (1L shl 30) * target.toLong()
                    if (width.toLong() > largestCapacity || height.toLong() > largestCapacity) {
                        assertNull(input, sample)
                        continue
                    }
                    assertNotNull(input, sample)
                    val value = checkNotNull(sample)
                    assertTrue("$input has a positive sample", value > 0)
                    assertEquals("$input has a decoder power of two", 0, value and (value - 1))
                    val capacity = value.toLong() * target.toLong()
                    assertTrue("$input fits width", width.toLong() <= capacity)
                    assertTrue("$input fits height", height.toLong() <= capacity)
                    if (value > 1) {
                        val previousCapacity = (value / 2).toLong() * target.toLong()
                        assertTrue(
                            "$input uses the smallest fitting decoder sample",
                            width.toLong() > previousCapacity || height.toLong() > previousCapacity
                        )
                    }
                }
            }
        }
    }
}
