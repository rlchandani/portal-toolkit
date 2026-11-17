/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.chores

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portalinstaller.data.chores.ChoreBoard
import com.portalinstaller.data.chores.ChoreLimits

/** Chores sizes and colours (the kid colours match the Portal's, light and dark). */
internal object ChoreUi {
    val Avatar = 44.dp
    val AvatarLarge = 64.dp
    val Picture = 40.dp
    val MinTouch = 48.dp
    val Bar = 10.dp
    val Star = Color(0xFFF2B630)
    val Done = Color(0xFF34A877)
    private val LightFills = listOf(0xFFFFD9C7, 0xFFFFE9A8, 0xFFCDEFD9, 0xFFC9E3FB, 0xFFE4D6FA, 0xFFFAD2E4, 0xFFD3EFEE, 0xFFE6E0D6)
    private val DarkFills = listOf(0xFF7A3E25, 0xFF6E5A12, 0xFF2D5E40, 0xFF244E77, 0xFF4E3A79, 0xFF74304F, 0xFF22605D, 0xFF5A5246)

    fun fill(index: Int, dark: Boolean): Color = Color((if (dark) DarkFills else LightFills)[Math.floorMod(index, ChoreLimits.COLORS)])
}

@Composable
internal fun isDarkSurface(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

/** A kid's emoji on their colour. */
@Composable
internal fun KidAvatar(avatar: String, color: Int, size: Dp = ChoreUi.Avatar, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(ChoreUi.fill(color, isDarkSurface())), contentAlignment = Alignment.Center) {
        Text(avatar, fontSize = (size.value * 0.5f).sp)
    }
}

/** Progress toward the weekly goal (hidden when there is no goal). */
@Composable
internal fun GoalBar(week: ChoreBoard.Week) {
    if (week.goal <= 0) return
    val shape = RoundedCornerShape(ChoreUi.Bar / 2)
    Box(Modifier.fillMaxWidth().height(ChoreUi.Bar).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant)) {
        Box(Modifier.fillMaxWidth(ChoreBoard.progress(week)).height(ChoreUi.Bar).clip(shape)
            .background(if (week.reached) ChoreUi.Done else ChoreUi.Star))
    }
}
