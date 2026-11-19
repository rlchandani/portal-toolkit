/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.calls

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Calling colours from the approved mock (`--okc`, `--badc`, `.vidscr`). */
internal object CallColors {
    val Ok = Color(0xFF2E9E6A)
    val Bad = Color(0xFFD93F4C)
    val Busy = Color(0xFFE0A43A)
    val Quiet = Color(0xFF8B7FD6)
    val Off = Color(0xFF9AA3AD)
    val VideoBg = Color(0xFF0B0C10)
    val Bar = Color(0xBD0E0F13)
    val Glass = Color(0x24FFFFFF)
    val LiveRed = Color(0xFFFF4D4D)
    private val avatars = listOf(
        Color(0xFFF4B26A) to Color(0xFFC8622E), Color(0xFF86B9DD) to Color(0xFF2F5F92),
        Color(0xFFC9A0DC) to Color(0xFF7A3E9D), Color(0xFF9ED9B5) to Color(0xFF2F8A5B),
        Color(0xFF7ED6C6) to Color(0xFF1F7F6E), Color(0xFFF29E8E) to Color(0xFFC2414F),
        Color(0xFFF2D06B) to Color(0xFFC08A1E))

    /** Same palette and pick as the Portal, so a room keeps its colour on both screens. */
    fun avatar(name: String): Pair<Color, Color> = avatars[Math.floorMod(name.lowercase().hashCode(), avatars.size)]
}

/** A room avatar: a gradient tile with initials. */
@Composable
internal fun RoomAvatar(name: String, size: Dp) {
    val (a1, a2) = remember(name) { CallColors.avatar(name) }
    Box(Modifier.size(size).background(Brush.linearGradient(listOf(a1, a2)), RoundedCornerShape(percent = AVATAR_RADIUS_PCT))
        .semantics { contentDescription = name }, contentAlignment = Alignment.Center) {
        Text(initials(name), color = Color.White, fontSize = (size.value * INITIALS_SHARE).sp, fontWeight = FontWeight.Bold)
    }
}

internal fun initials(name: String): String =
    name.split(' ', '’', '\'').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "?" }

/** Small line icons drawn on a 24-unit grid (video, mic, speaker, end, lock, phone, and the "off" slashes). */
@Composable
internal fun CallIcon(name: String, tint: Color, size: Dp = 26.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / GRID
        val stroke = Stroke(STROKE * u, cap = StrokeCap.Round)
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(tint, p(x1, y1), p(x2, y2), STROKE * u, StrokeCap.Round)
        fun slash() = line(3f, 3f, 21f, 21f)
        when (name) {
            "video", "videoOff" -> {
                drawRoundRect(tint, p(2f, 6f), Size(13 * u, 12 * u), CornerRadius(2.5f * u), style = stroke)
                drawPath(Path().apply { moveTo(15 * u, 10 * u); lineTo(22 * u, 6.5f * u); lineTo(22 * u, 17.5f * u); lineTo(15 * u, 14 * u) }, tint, style = stroke)
                if (name == "videoOff") slash()
            }
            "mic", "micOff" -> {
                drawRoundRect(tint, p(9f, 2.5f), Size(6 * u, 11 * u), CornerRadius(3 * u), style = stroke)
                drawArc(tint, 0f, 180f, false, p(5f, 7f), Size(14 * u, 11 * u), style = stroke)
                line(12f, 18f, 12f, 21.5f)
                if (name == "micOff") slash()
            }
            "speaker" -> {
                drawPath(Path().apply { moveTo(3 * u, 9 * u); lineTo(7 * u, 9 * u); lineTo(12 * u, 4.5f * u); lineTo(12 * u, 19.5f * u)
                    lineTo(7 * u, 15 * u); lineTo(3 * u, 15 * u); close() }, tint, style = stroke)
                drawArc(tint, -50f, 100f, false, p(11f, 7f), Size(7 * u, 10 * u), style = stroke)
                drawArc(tint, -55f, 110f, false, p(11f, 4f), Size(11 * u, 16 * u), style = stroke)
            }
            "end", "phone" -> {
                val handset = Path().apply {
                    moveTo(5 * u, 4 * u); lineTo(8.5f * u, 4 * u); lineTo(10.5f * u, 9 * u); lineTo(8 * u, 10.6f * u)
                    cubicTo(9.2f * u, 13 * u, 11 * u, 14.8f * u, 13.4f * u, 16 * u); lineTo(15 * u, 13.5f * u); lineTo(20 * u, 15.5f * u)
                    lineTo(20 * u, 19 * u); cubicTo(20 * u, 20.1f * u, 19.1f * u, 21 * u, 18 * u, 21 * u)
                    cubicTo(10.8f * u, 20.4f * u, 3.6f * u, 13.2f * u, 3 * u, 6 * u); cubicTo(3 * u, 4.9f * u, 3.9f * u, 4 * u, 5 * u, 4 * u); close()
                }
                if (name == "end") rotateHandset(handset, tint, stroke) else drawPath(handset, tint, style = stroke)
            }
            "lock" -> {
                drawRoundRect(tint, p(5f, 10.5f), Size(14 * u, 10 * u), CornerRadius(2 * u), style = stroke)
                drawArc(tint, 180f, 180f, false, p(8f, 4f), Size(8 * u, 9 * u), style = stroke)
                line(8f, 8.5f, 8f, 10.5f); line(16f, 8.5f, 16f, 10.5f)
            }
            else -> drawCircle(tint, 8 * u, p(12f, 12f), style = stroke)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.rotateHandset(path: Path, tint: Color, stroke: Stroke) {
    rotate(END_ROTATION) { drawPath(path, tint, style = stroke) }
}

private const val GRID = 24f
private const val STROKE = 1.8f
private const val END_ROTATION = 135f
private const val AVATAR_RADIUS_PCT = 28
private const val INITIALS_SHARE = 0.38f
