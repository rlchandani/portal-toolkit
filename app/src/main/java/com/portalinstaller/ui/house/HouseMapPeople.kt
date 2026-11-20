/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.house

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.portalinstaller.data.house.HouseFloor
import com.portalinstaller.data.house.HouseSnapshot

/** An anonymous "someone is here" ring at a room's centre, in plan cells; [strength] 0..1 is its opacity. */
internal data class HousePulse(val x: Float, val y: Float, val strength: Float)

/** A recognised person's chip (initial on their colour), in plan cells. */
internal data class HousePersonChip(val key: String, val x: Float, val y: Float, val initial: String, val rgb: Int)

/**
 * Where the Portal's people-in-rooms land on this floor, as on the Portal's plan (the mock's `.mo` ripple
 * 2 cells under a room's centre, `.pp` chips 3.1 cells under it, 2.6 apart). A room with chips has no ring.
 * Pure; the Portal has already applied the household's privacy policy to what it sent.
 */
internal object HousePeopleLayout {
    private const val PULSE_DROP = 2f
    private const val CHIP_DROP = 3.1f
    private const val CHIP_STEP = 2.6f

    fun pulses(floor: HouseFloor, s: HouseSnapshot): List<HousePulse> = floor.rooms.mapNotNull { r ->
        val v = s.room(r.id)?.takeIf { it.occupancy > 0f && it.people.isEmpty() } ?: return@mapNotNull null
        HousePulse(r.x + r.w / 2f, r.y + r.h / 2f + PULSE_DROP, v.occupancy)
    }

    fun chips(floor: HouseFloor, s: HouseSnapshot): List<HousePersonChip> = floor.rooms.flatMap { r ->
        val ps = s.room(r.id)?.people.orEmpty()
        val cx = r.x + r.w / 2f
        ps.mapIndexed { i, p -> HousePersonChip(p.key, cx - (ps.size - 1) * CHIP_STEP / 2f + i * CHIP_STEP, r.y + r.h / 2f + CHIP_DROP, p.initial, p.rgb) }
    }
}

/**
 * The only animated part of the phone's map: a separate canvas over the static plan, composed only while
 * a room on this floor is occupied, so a quiet house draws no frames at all. One clock drives every ring.
 */
@Composable
internal fun HousePulseLayer(pulses: List<HousePulse>, fit: PlanFit, modifier: Modifier) {
    if (pulses.isEmpty()) return
    val t = rememberInfiniteTransition(label = "housePulse")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(PULSE_MS, easing = LinearEasing)), label = "housePulseT")
    val color = houseColors.motion
    Canvas(modifier) {
        val f = fit.frame(size.width, size.height)
        pulses.forEachIndexed { i, p ->
            val k = (t.value + i * STAGGER) % 1f
            val r = PULSE_CELLS * f.px * (FROM + GROW * k)
            drawCircle(color, r, f.point(fit, p.x, p.y), alpha = ALPHA * p.strength * (1f - k))
        }
    }
}

/** Chips on the static canvas (drawn once per snapshot, never per frame). */
internal fun DrawScope.drawChips(m: TextMeasurer, chips: List<HousePersonChip>, f: PlanFrame, fit: PlanFit, ring: Color) {
    val r = CHIP_DP * density
    chips.forEach { c ->
        val at: Offset = f.point(fit, c.x, c.y)
        drawCircle(Color(0xFF000000.toInt() or c.rgb), r, at)
        drawCircle(ring, r, at, style = Stroke(2f * density))
        val layout = m.measure(c.initial, TextStyle(fontSize = 11.sp, color = INK, fontWeight = FontWeight.ExtraBold))
        drawText(layout, topLeft = Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f))
    }
}

@Composable
internal fun rememberChips(floor: HouseFloor, s: HouseSnapshot): List<HousePersonChip> = remember(floor, s.rooms) { HousePeopleLayout.chips(floor, s) }

@Composable
internal fun rememberPulses(floor: HouseFloor, s: HouseSnapshot): List<HousePulse> = remember(floor, s.rooms) { HousePeopleLayout.pulses(floor, s) }

private val INK = Color(0xFF14161B)
/** The Portal's ring: 2.2 s from 0.35× to 1.6× of 3 cells, fading from 0.55. */
private const val PULSE_MS = 2_200
private const val PULSE_CELLS = 3f
private const val FROM = 0.35f
private const val GROW = 1.25f
private const val ALPHA = 0.55f
private const val STAGGER = 0.37f
private const val CHIP_DP = 9f
