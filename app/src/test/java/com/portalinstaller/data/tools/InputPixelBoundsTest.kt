/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class InputPixelBoundsTest {
    @Test
    fun rightLetterboxTapTargetsLastColumn() {
        // Content occupies x=80..720; this tap is still inside the 800px view.
        val (x, y) = InputInjector.mapPoint(799f, 200f, 800, 400, 1280, 800)
        assertEquals("input tap 1279 400", InputInjector.tapCommand(x, y))
    }

    @Test
    fun bottomLetterboxLongPressTargetsLastRow() {
        // Content occupies y=100..500; this press is inside the bottom bar.
        val (x, y) = InputInjector.mapPoint(320f, 599f, 640, 600, 1280, 800)
        assertEquals("input swipe 640 799 640 799 600", InputInjector.longPressCommand(x, y))
    }

    @Test
    fun dragBeyondViewTargetsValidPixelsAtBothEnds() {
        val (x1, y1) = InputInjector.mapPoint(-20f, -30f, 640, 400, 1280, 800)
        val (x2, y2) = InputInjector.mapPoint(700f, 450f, 640, 400, 1280, 800)
        assertEquals(
            "input swipe 0 0 1279 799 200",
            InputInjector.swipeCommand(x1, y1, x2, y2, 200)
        )
    }

    @Test
    fun onePixelDisplayHasOnlyTheOrigin() {
        val (x, y) = InputInjector.mapPoint(99f, 99f, 100, 100, 1, 1)
        assertEquals("input tap 0 0", InputInjector.tapCommand(x, y))
    }

    @Test
    fun interiorTapKeepsProportionalMapping() {
        val (x, y) = InputInjector.mapPoint(320f, 200f, 640, 400, 1280, 800)
        assertEquals("input tap 640 400", InputInjector.tapCommand(x, y))
    }

    @Test
    fun lastPixelInsideContentIsUnchanged() {
        val (x, y) = InputInjector.mapPoint(639.5f, 399.5f, 640, 400, 1280, 800)
        assertEquals("input tap 1279 799", InputInjector.tapCommand(x, y))
    }

    @Test
    fun negativePointsStillClampToOrigin() {
        assertEquals(0 to 0, InputInjector.mapPoint(-1f, -1f, 640, 400, 1280, 800))
    }

    @Test
    fun invalidDimensionsKeepExistingOriginFallback() {
        assertEquals(0 to 0, InputInjector.mapPoint(10f, 10f, 0, 400, 1280, 800))
        assertEquals(0 to 0, InputInjector.mapPoint(10f, 10f, 640, 0, 1280, 800))
        assertEquals(0 to 0, InputInjector.mapPoint(10f, 10f, 640, 400, 0, 800))
        assertEquals(0 to 0, InputInjector.mapPoint(10f, 10f, 640, 400, 1280, 0))
    }
}
