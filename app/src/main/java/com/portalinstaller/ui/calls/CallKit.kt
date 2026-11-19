/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.calls

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** `.ringbg`: near-black with two slow blobs in the room's colours. */
@Composable
internal fun CallRingBackground(name: String) {
    val (a1, a2) = remember(name) { CallColors.avatar(name) }
    val drift by rememberInfiniteTransition(label = "blobs")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(BLOB_MS, easing = LinearEasing), RepeatMode.Reverse), label = "d")
    Box(Modifier.fillMaxSize().background(CallColors.VideoBg).drawBehind {
        val r = size.minDimension * BLOB_RADIUS
        val c1 = Offset(size.width * (0.25f + 0.1f * drift), size.height * (0.28f + 0.08f * drift))
        val c2 = Offset(size.width * (0.75f - 0.1f * drift), size.height * (0.7f - 0.08f * drift))
        drawCircle(Brush.radialGradient(listOf(a1.copy(alpha = BLOB_ALPHA), Color.Transparent), c1, r), r, c1)
        drawCircle(Brush.radialGradient(listOf(a2.copy(alpha = BLOB_ALPHA), Color.Transparent), c2, r), r, c2)
    })
}

/** `.ripple`: two expanding rings around the ringing avatar. */
@Composable
internal fun CallRipple(size: Dp, content: @Composable () -> Unit) {
    val p by rememberInfiniteTransition(label = "ripple")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(RIPPLE_MS, easing = FastOutSlowInEasing)), label = "p")
    Box(Modifier.size(size).drawBehind {
        val base = this.size.minDimension / 2
        for (offset in listOf(0f, 0.5f)) {
            val f = (p + offset) % 1f
            drawCircle(Color.White.copy(alpha = RIPPLE_ALPHA * (1f - f)), base * (1f + RIPPLE_GROWTH * f), style = Stroke(3.dp.toPx()))
        }
    }, contentAlignment = Alignment.Center) { content() }
}

/** `.bigb`: a 76 dp round action with its label under it. */
@Composable
internal fun BigCallButton(icon: String, label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(BIG_BUTTON).background(color, CircleShape).callClick(label, onClick), contentAlignment = Alignment.Center) {
            CallIcon(icon, Color.White, 34.dp)
        }
        Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** `.gchip`: white text on glass. */
@Composable
internal fun GlassChip(text: String, icon: String? = "lock", modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(50)
    Row(modifier.height(40.dp).background(CallColors.Glass, shape).border(1.dp, Color(0x38FFFFFF), shape)
        .let { if (onClick != null) it.callClick(text, onClick) else it }.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        icon?.let { CallIcon(it, Color.White, 18.dp) }
        Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** `.ind`: the live bar, always on screen in a call. */
@Composable
internal fun LiveBar(cameraOn: Boolean, micOn: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.background(CallColors.Bar, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(10.dp).background(if (cameraOn || micOn) CallColors.LiveRed else CallColors.Off, CircleShape))
        Text("${if (cameraOn) "Camera on" else "Camera off"} · ${if (micOn) "Mic on" else "Mic muted"}",
            color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Box(Modifier.size(width = 1.dp, height = 14.dp).background(Color(0x33FFFFFF)))
        CallIcon("lock", Color.White, 14.dp)
        Text("E2E", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

internal fun Modifier.callClick(label: String, onClick: () -> Unit): Modifier =
    semantics { role = Role.Button; contentDescription = label }.clickable(onClick = onClick)

internal fun clock(ms: Long): String {
    val s = (ms / MS_PER_S).coerceAtLeast(0)
    return "%d:%02d".format(s / SECONDS_PER_MINUTE, s % SECONDS_PER_MINUTE)
}

private val BIG_BUTTON = 76.dp
private const val BLOB_MS = 22_000
private const val BLOB_RADIUS = 0.6f
private const val BLOB_ALPHA = 0.55f
private const val RIPPLE_MS = 2_400
private const val RIPPLE_ALPHA = 0.35f
private const val RIPPLE_GROWTH = 0.5f
private const val MS_PER_S = 1_000L
private const val SECONDS_PER_MINUTE = 60
