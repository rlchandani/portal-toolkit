/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.house

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/** The mock's phone palette (`.phone[data-theme]`): status tones and the plan's own colours. */
@Immutable
internal data class HouseColors(
    val green: Color, val greenBg: Color, val red: Color, val redBg: Color, val amber: Color, val amberBg: Color,
    val planBg: Color, val roomFill: Color, val roomLine: Color, val outdoor: Color, val motion: Color, val dotOff: Color,
)

private val DarkHouse = HouseColors(
    green = Color(0xFF66C49E), greenBg = Color(0xFF173328), red = Color(0xFFFF747D), redBg = Color(0xFF43191E),
    amber = Color(0xFFF0A65A), amberBg = Color(0xFF3B2A15), planBg = Color(0xFF1B1E24), roomFill = Color(0xFF262A31),
    roomLine = Color(0xFF3F444D), outdoor = Color(0xFF1F2A22), motion = Color(0xFF6FA8FF), dotOff = Color(0xFF4A4E56),
)

private val LightHouse = HouseColors(
    green = Color(0xFF288B61), greenBg = Color(0xFFDDF1E7), red = Color(0xFFD33D4C), redBg = Color(0xFFFBE1E4),
    amber = Color(0xFFB9741A), amberBg = Color(0xFFFBEBD3), planBg = Color(0xFFE7ECF2), roomFill = Color(0xFFFFFFFF),
    roomLine = Color(0xFFC9D2DC), outdoor = Color(0xFFE2EDE3), motion = Color(0xFF176ABB), dotOff = Color(0xFFC3CBD4),
)

internal val houseColors: HouseColors
    // Follows the app's own theme choice (System / Light / Dark), not only the system setting.
    @Composable @ReadOnlyComposable get() = if (MaterialTheme.colorScheme.background.luminance() < HALF) DarkHouse else LightHouse

private const val HALF = 0.5f

internal object HouseShapes {
    val card = RoundedCornerShape(20.dp)
    val tile = RoundedCornerShape(16.dp)
    val icon = RoundedCornerShape(12.dp)
    val pill = RoundedCornerShape(999.dp)
    val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
}

/** Scene tiles: the mock's gradients per tone (night, movie, leave, morning, neutral). */
internal fun sceneColors(tone: String): Triple<Color, Color, Color> = when (tone) {
    "night" -> Triple(Color(0xFF262A47), Color(0xFF151729), Color(0xFFE9EBFF))
    "movie" -> Triple(Color(0xFF3B1F3F), Color(0xFF1D1022), Color(0xFFFFE9F6))
    "leave" -> Triple(Color(0xFF16352C), Color(0xFF0F211B), Color(0xFFDCFFF1))
    "morning" -> Triple(Color(0xFFFFD7A1), Color(0xFFF6A96B), Color(0xFF3A1F08))
    else -> Triple(Color(0xFF2E3440), Color(0xFF1B1F27), Color(0xFFEFF2F7))
}

internal fun rgbColor(rgb: Int, alpha: Float = 1f): Color = Color(0xFF000000.toInt() or (rgb and 0xFFFFFF)).copy(alpha = alpha)
