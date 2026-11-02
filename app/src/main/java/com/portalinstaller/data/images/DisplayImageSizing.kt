/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.images

/**
 * Smallest positive Int power-of-two decoder sample whose ceiling-divided edges fit [maxEdge].
 * Returns 1 for images that already fit, or null for nonpositive bounds or an unrepresentable sample.
 * Checks at most 31 candidates, without imposing a source dimension or pixel-count limit.
 */
internal fun companionImageSampleSize(width: Int, height: Int, maxEdge: Int): Int? {
    if (width <= 0 || height <= 0 || maxEdge <= 0) return null

    val sourceWidth = width.toLong()
    val sourceHeight = height.toLong()
    val target = maxEdge.toLong()
    var sample = 1L
    while (sample <= Int.MAX_VALUE.toLong()) {
        val sampledWidth = (sourceWidth + sample - 1L) / sample
        val sampledHeight = (sourceHeight + sample - 1L) / sample
        if (sampledWidth <= target && sampledHeight <= target) return sample.toInt()
        sample *= 2L
    }
    return null
}
