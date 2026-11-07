/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Generated proc values; no shell, kernel or device access. */
class MemInfoValidityTest {
    private val largestKb = Long.MAX_VALUE / 1024

    @Test fun negativeTotalDoesNotBecomePositiveMemory() {
        assertNull(MemInfoParser.parse("MemTotal: -100 kB\nMemAvailable: 20 kB"))
    }

    @Test fun negativeAvailableWithoutFallbackIsUnavailable() {
        assertNull(MemInfoParser.parse("MemTotal: 100 kB\nMemAvailable: -20 kB"))
    }

    @Test fun negativeFreeFallbackIsUnavailable() {
        assertNull(MemInfoParser.parse("MemTotal: 100 kB\nMemFree: -20 kB"))
    }

    @Test fun overflowingTotalDoesNotWrapBytes() {
        assertNull(MemInfoParser.parse("MemTotal: ${largestKb + 1} kB\nMemAvailable: 20 kB"))
    }

    @Test fun overflowingAvailableDoesNotWrapBytes() {
        assertNull(MemInfoParser.parse("MemTotal: 100 kB\nMemAvailable: ${largestKb + 1} kB"))
    }

    @Test fun overflowingFreeFallbackDoesNotWrapBytes() {
        assertNull(MemInfoParser.parse("MemTotal: 100 kB\nMemFree: ${largestKb + 1} kB"))
    }

    @Test fun digitsInsideAnErrorAreNotAValue() {
        assertNull(MemInfoParser.parse("MemTotal: unavailable (error 12)\nMemAvailable: 20 kB"))
    }

    @Test fun invalidAvailableStillUsesValidFreeFallback() {
        assertEquals(MemInfo(100 * 1024L, 30 * 1024L),
            MemInfoParser.parse("MemTotal: 100 kB\nMemAvailable: -20 kB\nMemFree: 30 kB"))
    }

    @Test fun overflowingAvailableStillUsesValidFreeFallback() {
        assertEquals(MemInfo(100 * 1024L, 30 * 1024L), MemInfoParser.parse(
            "MemTotal: 100 kB\nMemAvailable: ${largestKb + 1} kB\nMemFree: 30 kB"))
    }

    @Test fun largestRepresentableByteValueRemainsExact() {
        assertEquals(MemInfo(largestKb * 1024, largestKb * 1024),
            MemInfoParser.parse("MemTotal: $largestKb kB\nMemAvailable: $largestKb kB"))
    }

    @Test fun zeroAndExistingClampingRemainCompatible() {
        assertEquals(MemInfo(0, 0), MemInfoParser.parse("MemTotal: 0 kB\nMemAvailable: 0 kB"))
        val info = MemInfoParser.parse("MemTotal: 10 kB\nMemAvailable: 20 kB")!!
        assertEquals(MemInfo(10 * 1024L, 20 * 1024L), info)
        assertEquals(0f, info.usedFraction, 0f)
    }

    @Test fun whitespaceAndUnitlessCompatibilityRemain() {
        assertEquals(MemInfo(100 * 1024L, 30 * 1024L),
            MemInfoParser.parse(" \tMemTotal:\t100\n\tMemAvailable:  30 kB"))
    }
}
