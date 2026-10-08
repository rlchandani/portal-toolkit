/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.tools

import kotlin.math.roundToInt

/** Validated device dimensions shared by surface layout and input mapping. */
data class MirrorGeometry(val width: Int, val height: Int) {
    init {
        require(width in 1..8192 && height in 1..8192) { "Invalid mirror dimensions" }
    }

    val aspectRatio: Float get() = width.toFloat() / height

    /** Largest surface that fits; rounding introduces at most one pixel of aspect error. */
    fun fitWithin(availableWidth: Int, availableHeight: Int): Pair<Int, Int> {
        if (availableWidth <= 0 || availableHeight <= 0) return 0 to 0
        val scale = minOf(availableWidth.toDouble() / width, availableHeight.toDouble() / height)
        return (width * scale).roundToInt().coerceIn(1, availableWidth) to
            (height * scale).roundToInt().coerceIn(1, availableHeight)
    }

    /** Coordinates are local to the measured surface, with no letterbox inside it. */
    fun mapSurfacePoint(
        x: Float,
        y: Float,
        surfaceWidth: Int,
        surfaceHeight: Int
    ): Pair<Int, Int>? {
        if (surfaceWidth <= 0 || surfaceHeight <= 0 || !x.isFinite() || !y.isFinite()) return null
        return (x.toDouble() / surfaceWidth * width).toInt().coerceIn(0, width - 1) to
            (y.toDouble() / surfaceHeight * height).toInt().coerceIn(0, height - 1)
    }
}
