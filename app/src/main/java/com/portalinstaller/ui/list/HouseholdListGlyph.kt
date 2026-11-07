/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.list

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Line glyphs for the list surfaces (24-unit grid, same stroke as the control center's glyphs). */
@Composable
internal fun ListGlyph(name: String, tint: Color = MaterialTheme.colorScheme.onSurface, modifier: Modifier = Modifier.size(22.dp)) {
    Canvas(modifier) {
        val u = size.minDimension / 24f
        val w = 1.7f * u
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(tint, p(x1, y1), p(x2, y2), w)
        when (name) {
            "list" -> { listOf(6f, 12f, 18f).forEach { y -> drawCircle(tint, 1.3f * u, p(4f, y)); line(9f, y, 21f, y) } }
            "check" -> drawPath(Path().apply { moveTo(4 * u, 12.5f * u); lineTo(9.5f * u, 18 * u); lineTo(20 * u, 6.5f * u) }, tint, style = Stroke(w * 1.2f))
            "plus" -> { line(12f, 4f, 12f, 20f); line(4f, 12f, 20f, 12f) }
            "clock" -> { drawCircle(tint, 9 * u, p(12f, 12f), style = Stroke(w)); line(12f, 7f, 12f, 12f); line(12f, 12f, 15.5f, 14f) }
            "info" -> { drawCircle(tint, 9 * u, p(12f, 12f), style = Stroke(w)); line(12f, 11f, 12f, 17f); drawCircle(tint, 1.1f * u, p(12f, 7.5f)) }
            "trash" -> { line(4f, 6f, 20f, 6f); line(9f, 6f, 9f, 3.5f); line(9f, 3.5f, 15f, 3.5f); line(15f, 3.5f, 15f, 6f)
                line(6f, 6f, 7f, 21f); line(7f, 21f, 17f, 21f); line(17f, 21f, 18f, 6f) }
            "wifioff" -> { drawArc(tint, 200f, 140f, false, p(2f, 5f), androidx.compose.ui.geometry.Size(20 * u, 20 * u), style = Stroke(w))
                drawArc(tint, 205f, 130f, false, p(6f, 9f), androidx.compose.ui.geometry.Size(12 * u, 12 * u), style = Stroke(w))
                drawCircle(tint, 1.4f * u, p(12f, 18.5f)); line(3f, 3f, 21f, 21f) }
            "edit" -> { line(4f, 20f, 8f, 19f); line(8f, 19f, 19f, 8f); line(19f, 8f, 16f, 5f); line(16f, 5f, 5f, 16f); line(5f, 16f, 4f, 20f) }
            "up" -> { line(12f, 20f, 12f, 4f); line(6f, 10f, 12f, 4f); line(12f, 4f, 18f, 10f) }
            "chevron" -> { line(9f, 5f, 16f, 12f); line(16f, 12f, 9f, 19f) }
            "down" -> { line(5f, 9f, 12f, 16f); line(12f, 16f, 19f, 9f) }
            "refresh" -> { drawArc(tint, 300f, 300f, false, p(4f, 4f), androidx.compose.ui.geometry.Size(16 * u, 16 * u), style = Stroke(w))
                line(19f, 3.5f, 19.5f, 8.5f); line(19.5f, 8.5f, 14.5f, 8f) }
            "grid" -> listOf(5f, 12f, 19f).forEach { x -> listOf(5f, 12f, 19f).forEach { y -> drawCircle(tint, 1.4f * u, p(x, y)) } }
        }
    }
}
