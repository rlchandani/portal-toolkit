/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.house

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portalinstaller.data.house.HouseFloor
import com.portalinstaller.data.house.HouseRoom
import com.portalinstaller.data.house.HouseSnapshot

/** The plan's two lenses on the same map, as on the Portal. */
internal enum class HouseLens(val label: String) { LIVE("Live"), CLIMATE("Climate") }

/**
 * The live map card (mock `.pplan`): floor tabs, Live / Climate lenses, rooms glowing with their
 * lights' real colours, amber open doors and windows, blue motion, and — as the Portal allows — a soft
 * ring where someone is and recognised people's chips. Tap a room for its sheet. Drawn on one static
 * canvas from the Portal's room summaries, plus [HousePulseLayer] only while someone is home; nothing
 * here reads the network.
 */
@Composable
internal fun HouseMapCard(floors: List<HouseFloor>, floorId: String?, lens: HouseLens, s: HouseSnapshot,
                          onFloor: (String) -> Unit, onLens: (HouseLens) -> Unit, onRoom: (String) -> Unit) {
    val floor = floors.firstOrNull { it.id == floorId } ?: floors.firstOrNull() ?: return
    val colors = houseColors
    val measurer = rememberTextMeasurer()
    val label = MaterialTheme.colorScheme.onSurface
    val fit = remember(floor) { PlanFit.of(floor) }
    val chips = rememberChips(floor, s)
    val pulses = rememberPulses(floor, s)
    val ring = colors.planBg
    Box(Modifier.fillMaxWidth().height(MAP_HEIGHT.dp).clip(HouseShapes.card).background(colors.planBg)
        .semantics { contentDescription = planDescription(floor, s) }) {
        Canvas(Modifier.fillMaxSize().padding(start = 6.dp, end = 6.dp, top = 44.dp, bottom = 6.dp)
            .pointerInput(floor) { detectTapGestures { p -> fit.roomAt(floor, p, size.width.toFloat(), size.height.toFloat())?.let(onRoom) } }) {
            val f = fit.frame(size.width, size.height)
            floor.rooms.forEach { r -> drawRoom(r, f, fit, s, lens, colors) }
            floor.openings.forEach { o ->
                val r = floor.rooms.firstOrNull { it.id == o.roomId } ?: return@forEach
                val open = s.entity(o.entityId)?.isOpen == true
                val (a, b) = fit.wall(r, o.side, o.at, o.span, f)
                drawLine(if (open) colors.amber else colors.roomLine, a, b, if (open) 5f * density else 3f * density)
            }
            floor.rooms.forEach { r -> drawLabel(measurer, r, f, fit, s, lens, label) }
            if (lens == HouseLens.LIVE) drawChips(measurer, chips, f, fit, ring)
        }
        if (lens == HouseLens.LIVE) HousePulseLayer(pulses, fit, Modifier.fillMaxSize().padding(start = 6.dp, end = 6.dp, top = 44.dp, bottom = 6.dp))
        Row(Modifier.align(Alignment.TopStart).padding(8.dp).horizontalScroll(rememberScrollState())) {
            if (floors.size > 1) Segments(floors.map { it.id to it.name.take(SHORT_NAME) }, floor.id, onFloor)
            Box(Modifier.padding(start = 8.dp)) { Segments(HouseLens.entries.map { it.name to it.label }, lens.name) { onLens(HouseLens.valueOf(it)) } }
        }
    }
}

@Composable
private fun Segments(items: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    Row(Modifier.clip(HouseShapes.pill).background(MaterialTheme.colorScheme.surfaceVariant).padding(3.dp)) {
        items.forEach { (id, text) ->
            val on = id == selected
            Text(text, Modifier.clip(HouseShapes.pill).background(if (on) MaterialTheme.colorScheme.surface else Color.Transparent)
                .clickable { onPick(id) }.padding(horizontal = 12.dp, vertical = 5.dp),
                fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun DrawScope.drawRoom(r: HouseRoom, f: PlanFrame, fit: PlanFit, s: HouseSnapshot, lens: HouseLens, c: HouseColors) {
    val view = s.room(r.id)
    val px = f.px
    val tl = f.point(fit, r.x.toFloat(), r.y.toFloat())
    val sz = Size(r.w * px, r.h * px)
    val fill = when {
        lens == HouseLens.CLIMATE && view?.temp != null -> lerp(COLD, WARM, heat(view.temp, s.fahrenheit)).copy(alpha = 0.55f)
        r.outdoor -> c.outdoor
        else -> c.roomFill
    }
    drawRect(fill, tl, sz)
    if (lens == HouseLens.LIVE) view?.glows?.forEach { g ->
        val center = Offset(tl.x + g.fx * sz.width, tl.y + g.fy * sz.height)
        val radius = maxOf(sz.minDimension * GLOW_ROOM, px * GLOW_CELLS) * (0.6f + 0.4f * g.strength)
        drawCircle(Brush.radialGradient(listOf(rgbColor(g.rgb, 0.85f * g.strength), Color.Transparent), center, radius), radius, center)
    }
    if (lens == HouseLens.LIVE && view?.motion == true) drawRect(c.motion.copy(alpha = 0.18f), tl, sz)
    drawRect(if ((view?.open ?: 0) > 0) c.amber else c.roomLine, tl, sz, style = Stroke(1.5f * density))
}

private fun DrawScope.drawLabel(m: TextMeasurer, r: HouseRoom, f: PlanFrame, fit: PlanFit, s: HouseSnapshot, lens: HouseLens, color: Color) {
    val px = f.px
    if (r.w * px < MIN_LABEL_PX * density) return
    val view = s.room(r.id)
    val second = when {
        lens == HouseLens.CLIMATE -> view?.temp?.let { "${Math.round(it)}°" }
        (view?.lightsOn ?: 0) > 0 -> "${view?.lightsOn} on"
        else -> null
    }
    val text = listOfNotNull(r.name, second).joinToString("\n")
    val tl = f.point(fit, r.x.toFloat(), r.y.toFloat())
    val layout = m.measure(text, TextStyle(fontSize = 10.sp, color = color, fontWeight = FontWeight.SemiBold),
        overflow = TextOverflow.Ellipsis, maxLines = 2,
        constraints = androidx.compose.ui.unit.Constraints(maxWidth = (r.w * px - 6 * density).toInt().coerceAtLeast(1)))
    drawText(layout, topLeft = Offset(tl.x + 3 * density, tl.y + 2 * density))
}

/** "Floor plan, Ground, 6 rooms, someone in Kitchen, Maya in Living" (names only when the Portal sent them). */
internal fun planDescription(floor: HouseFloor, s: HouseSnapshot): String {
    val who = floor.rooms.mapNotNull { r ->
        val v = s.room(r.id) ?: return@mapNotNull null
        when {
            v.people.isNotEmpty() -> "${v.people.joinToString(" and ") { it.name }} in ${r.name}"
            v.occupancy > 0f -> "someone in ${r.name}"
            else -> null
        }
    }
    return (listOf("Floor plan, ${floor.name}, ${floor.rooms.size} rooms") + who).joinToString(", ")
}

/** Climate lens: 0 (cold) … 1 (warm) between 16 °C/61 °F and 24 °C/75 °F, as on the Portal. */
internal fun heat(temp: Double, fahrenheit: Boolean): Float {
    val (lo, hi) = if (fahrenheit) 61.0 to 75.0 else 16.0 to 24.0
    return ((temp - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f)
}

/** Where a floor's rooms land on a canvas: cell size [px] and the top-left [origin]. */
internal data class PlanFrame(val px: Float, val origin: Offset) {
    fun point(fit: PlanFit, x: Float, y: Float): Offset = Offset(origin.x + (x - fit.minX) * px, origin.y + (y - fit.minY) * px)
}

/** Maps a floor's room bounding box onto a canvas, centred (pure geometry, unit tested). */
internal class PlanFit private constructor(val minX: Int, val minY: Int, val w: Int, val h: Int) {
    fun frame(width: Float, height: Float): PlanFrame {
        val px = minOf(width / w, height / h)
        return PlanFrame(px, Offset((width - w * px) / 2f, (height - h * px) / 2f))
    }

    fun roomAt(floor: HouseFloor, p: Offset, width: Float, height: Float): String? {
        val f = frame(width, height)
        val gx = (p.x - f.origin.x) / f.px + minX
        val gy = (p.y - f.origin.y) / f.px + minY
        return floor.rooms.lastOrNull { gx >= it.x && gx <= it.x + it.w && gy >= it.y && gy <= it.y + it.h }?.id
    }

    fun wall(r: HouseRoom, side: Char, at: Float, span: Float, f: PlanFrame): Pair<Offset, Offset> {
        val half = span / 2f
        val a = (at - half).coerceAtLeast(0f)
        val b = (at + half).coerceAtMost(1f)
        val right = (r.x + r.w).toFloat()
        val bottom = (r.y + r.h).toFloat()
        return when (side) {
            'N' -> f.point(this, r.x + a * r.w, r.y.toFloat()) to f.point(this, r.x + b * r.w, r.y.toFloat())
            'S' -> f.point(this, r.x + a * r.w, bottom) to f.point(this, r.x + b * r.w, bottom)
            'W' -> f.point(this, r.x.toFloat(), r.y + a * r.h) to f.point(this, r.x.toFloat(), r.y + b * r.h)
            else -> f.point(this, right, r.y + a * r.h) to f.point(this, right, r.y + b * r.h)
        }
    }

    companion object {
        fun of(floor: HouseFloor): PlanFit {
            if (floor.rooms.isEmpty()) return PlanFit(0, 0, 1, 1)
            val minX = floor.rooms.minOf { it.x }
            val minY = floor.rooms.minOf { it.y }
            return PlanFit(minX, minY, (floor.rooms.maxOf { it.x + it.w } - minX).coerceAtLeast(1),
                (floor.rooms.maxOf { it.y + it.h } - minY).coerceAtLeast(1))
        }
    }
}

private val COLD = Color(0xFF4C7FD9)
private val WARM = Color(0xFFF0A65A)
private const val MAP_HEIGHT = 250
private const val SHORT_NAME = 10
private const val GLOW_ROOM = 0.55f
private const val GLOW_CELLS = 3f
private const val MIN_LABEL_PX = 34
