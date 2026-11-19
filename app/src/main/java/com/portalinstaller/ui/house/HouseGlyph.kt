/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.house

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Line icons on a 24-unit grid (the mock's icon names: bulb, lock, garage, cam, shield, car…).
 * Drawn, not decoded, so a list of fifty rows costs no bitmaps; unknown names draw a dot ring.
 */
@Composable
internal fun HouseGlyph(name: String, modifier: Modifier = Modifier, size: Dp = 22.dp, tint: Color = LocalContentColor.current) {
    Canvas(modifier.size(size)) { GlyphPen(this, tint).draw(name) }
}

private class GlyphPen(private val d: DrawScope, private val tint: Color) {
    private val u = d.size.minDimension / 24f
    private val stroke = Stroke(1.7f * u, cap = StrokeCap.Round)

    private fun p(x: Float, y: Float) = Offset(x * u, y * u)
    private fun line(x1: Float, y1: Float, x2: Float, y2: Float) = d.drawLine(tint, p(x1, y1), p(x2, y2), 1.7f * u, StrokeCap.Round)
    private fun circle(x: Float, y: Float, r: Float, fill: Boolean = false) =
        if (fill) d.drawCircle(tint, r * u, p(x, y)) else d.drawCircle(tint, r * u, p(x, y), style = stroke)
    private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float = 1.5f) =
        d.drawRoundRect(tint, p(x, y), Size(w * u, h * u), CornerRadius(r * u), style = stroke)
    private fun arc(x: Float, y: Float, w: Float, h: Float, start: Float, sweep: Float) =
        d.drawArc(tint, start, sweep, false, p(x, y), Size(w * u, h * u), style = stroke)
    private fun path(vararg pts: Float, close: Boolean = false) {
        val path = Path().apply {
            moveTo(pts[0] * u, pts[1] * u)
            var i = 2
            while (i + 1 < pts.size) { lineTo(pts[i] * u, pts[i + 1] * u); i += 2 }
            if (close) close()
        }
        d.drawPath(path, tint, style = stroke)
    }

    fun draw(name: String) = when (name) {
        "bulb" -> { arc(6f, 2f, 12f, 12f, 140f, 260f); path(9f, 13f, 9f, 17f, 15f, 17f, 15f, 13f); line(10f, 20f, 14f, 20f) }
        "power" -> { arc(5f, 5f, 14f, 14f, 300f, 300f); line(12f, 3f, 12f, 11f) }
        "fan" -> { circle(12f, 12f, 2f); arc(4f, 4f, 16f, 16f, 0f, 60f); arc(4f, 4f, 16f, 16f, 120f, 60f); arc(4f, 4f, 16f, 16f, 240f, 60f) }
        "lock" -> { rect(5f, 11f, 14f, 10f); arc(8f, 4f, 8f, 10f, 180f, 180f); line(8f, 9f, 8f, 11f); line(16f, 9f, 16f, 11f) }
        "unlock" -> { rect(5f, 11f, 14f, 10f); arc(8f, 3f, 8f, 10f, 180f, 160f); line(8f, 8f, 8f, 11f) }
        "garage" -> { path(3f, 10f, 12f, 4f, 21f, 10f, 21f, 21f, 3f, 21f, close = true); line(7f, 13f, 17f, 13f); line(7f, 16f, 17f, 16f); line(7f, 19f, 17f, 19f) }
        "blinds" -> { line(4f, 4f, 20f, 4f); for (y in listOf(8f, 12f, 16f)) line(5f, y, 19f, y); line(12f, 4f, 12f, 20f) }
        "therm" -> { rect(10f, 3f, 4f, 12f, 2f); circle(12f, 18f, 3f) }
        "tv" -> { rect(3f, 5f, 18f, 12f); line(8f, 21f, 16f, 21f) }
        "cam" -> { rect(3f, 7f, 13f, 10f); path(16f, 10f, 21f, 7f, 21f, 17f, 16f, 14f) }
        "valve" -> { line(3f, 14f, 21f, 14f); rect(9f, 10f, 6f, 8f); line(12f, 10f, 12f, 5f); line(8f, 5f, 16f, 5f) }
        "shield" -> path(12f, 3f, 20f, 6f, 20f, 12f, 12f, 21f, 4f, 12f, 4f, 6f, close = true)
        "door" -> { rect(6f, 3f, 12f, 18f, 1f); circle(15f, 12f, 0.9f, fill = true) }
        "window" -> { rect(4f, 4f, 16f, 16f, 1f); line(12f, 4f, 12f, 20f); line(4f, 12f, 20f, 12f) }
        "motion" -> { circle(12f, 12f, 2f, fill = true); arc(6f, 6f, 12f, 12f, 200f, 140f); arc(2f, 2f, 20f, 20f, 200f, 140f) }
        "drop" -> path(12f, 3f, 18f, 12f, 18f, 15f, 15f, 20f, 9f, 20f, 6f, 15f, 6f, 12f, close = true)
        "warn" -> { path(12f, 3f, 22f, 20f, 2f, 20f, close = true); line(12f, 9f, 12f, 14f); circle(12f, 17f, 0.9f, fill = true) }
        "washer", "dryer", "dish", "oven" -> { rect(4f, 3f, 16f, 18f); circle(12f, 13f, 4f); line(7f, 6f, 9f, 6f) }
        "sparkle" -> { path(12f, 3f, 14f, 10f, 21f, 12f, 14f, 14f, 12f, 21f, 10f, 14f, 3f, 12f, 10f, 10f, close = true) }
        "moon" -> arc(4f, 4f, 16f, 16f, 60f, 260f)
        "film" -> { rect(3f, 5f, 18f, 14f); line(7f, 5f, 7f, 19f); line(17f, 5f, 17f, 19f) }
        "leave" -> { path(14f, 4f, 20f, 4f, 20f, 20f, 14f, 20f); line(4f, 12f, 15f, 12f); path(11f, 8f, 15f, 12f, 11f, 16f) }
        "sun" -> { circle(12f, 12f, 4f); for (a in 0 until 8) ray(a) }
        "car" -> { path(3f, 16f, 3f, 12f, 6f, 7f, 18f, 7f, 21f, 12f, 21f, 16f, close = true); circle(7f, 17f, 2f); circle(17f, 17f, 2f) }
        "bolt", "charger" -> path(13f, 2f, 5f, 13f, 11f, 13f, 10f, 22f, 19f, 10f, 13f, 10f, close = true)
        "home" -> { path(3f, 11f, 12f, 3f, 21f, 11f); path(5f, 10f, 5f, 21f, 19f, 21f, 19f, 10f) }
        "person" -> { circle(12f, 8f, 4f); arc(4f, 14f, 16f, 14f, 180f, 180f) }
        "check" -> path(5f, 12f, 10f, 17f, 19f, 7f)
        "chevL" -> path(15f, 5f, 8f, 12f, 15f, 19f)
        "chevR" -> path(9f, 5f, 16f, 12f, 9f, 19f)
        "play" -> path(7f, 4f, 19f, 12f, 7f, 20f, close = true)
        "pause" -> { line(8f, 5f, 8f, 19f); line(16f, 5f, 16f, 19f) }
        "next" -> { path(5f, 5f, 15f, 12f, 5f, 19f, close = true); line(19f, 5f, 19f, 19f) }
        "prev" -> { path(19f, 5f, 9f, 12f, 19f, 19f, close = true); line(5f, 5f, 5f, 19f) }
        "refresh" -> { arc(4f, 4f, 16f, 16f, 35f, 290f); path(20f, 4f, 20f, 10f, 14f, 10f) }
        "cube" -> { path(12f, 3f, 20f, 7f, 20f, 17f, 12f, 21f, 4f, 17f, 4f, 7f, close = true); path(4f, 7f, 12f, 11f, 20f, 7f); line(12f, 11f, 12f, 21f) }
        "vol" -> { path(4f, 9f, 8f, 9f, 13f, 5f, 13f, 19f, 8f, 15f, 4f, 15f, close = true); arc(12f, 8f, 8f, 8f, 300f, 120f) }
        "eye" -> { arc(2f, 6f, 20f, 16f, 200f, 140f); arc(2f, 2f, 20f, 16f, 20f, 140f); circle(12f, 12f, 3f) }
        "hand", "fingerprint" -> { arc(5f, 4f, 14f, 16f, 180f, 180f); arc(8f, 7f, 8f, 10f, 180f, 180f); line(5f, 12f, 5f, 17f); line(19f, 12f, 19f, 15f); line(12f, 12f, 12f, 20f); line(8f, 12f, 8f, 18f); line(16f, 12f, 16f, 19f) }
        "clock" -> { circle(12f, 12f, 8f); path(12f, 7f, 12f, 12f, 15f, 14f) }
        else -> { circle(12f, 12f, 7f); circle(12f, 12f, 1.5f, fill = true) }
    }

    private fun ray(i: Int) {
        val a = Math.toRadians(i * 45.0)
        line(12f + 6.5f * Math.cos(a).toFloat(), 12f + 6.5f * Math.sin(a).toFloat(), 12f + 9f * Math.cos(a).toFloat(), 12f + 9f * Math.sin(a).toFloat())
    }
}
