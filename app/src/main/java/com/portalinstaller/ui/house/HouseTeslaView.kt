/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.house

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portalinstaller.data.house.HouseCarAction
import com.portalinstaller.data.house.HouseTesla

/**
 * The Tesla screen (replacing the mock's Energy): the car in its paint with battery, range and
 * what it is doing, the charge limit, the car's control tiles (unlock and open always ask), the
 * status grid and this week's home charging. All from the Portal's own Tesla screen logic.
 */
internal fun LazyListScope.houseTesla(t: HouseTesla?, enabled: Boolean, onAction: (HouseCarAction) -> Unit, onLimit: (Int) -> Unit) {
    if (t == null) { item(key = "nocar") { HouseCard { Text("No Tesla in Home Assistant. Add Tesla Fleet, Teslemetry or Tessie there and it appears here.") } }; return }
    item(key = "hero") { Hero(t) }
    if (t.limit != null) item(key = "limit") { LimitCard(t, enabled, onLimit) }
    if (t.actions.isNotEmpty()) item(key = "controlsTitle") { HouseSection("Controls") }
    items(t.actions.chunked(ACTIONS_PER_ROW), key = { r -> "car:" + r.joinToString { it.key } }) { row ->
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { a -> ActionTile(a, enabled, Modifier.weight(1f)) { onAction(a) } }
            repeat(ACTIONS_PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    if (t.rows.isNotEmpty()) item(key = "status") {
        HouseSection("Status")
        HouseCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                t.rows.forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        HouseGlyph(r.icon, size = 18.dp)
                        Text(r.label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        Text(r.value, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = tone(r.tone))
                    }
                }
            }
        }
    }
    t.week?.takeIf { it.kwh.isNotEmpty() }?.let { w -> item(key = "week") { WeekCard(w.kwh, w.days, w.price) } }
}

@Composable
private fun Hero(t: HouseTesla) {
    HouseCard {
        Column {
            CarArt(t)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(t.battery?.let { "$it%" } ?: "—", fontFamily = FontFamily.Serif, fontSize = 40.sp)
                t.range?.let { Text("  $it", Modifier.padding(bottom = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Text(t.headline, fontWeight = FontWeight.SemiBold)
            t.battery?.let { LinearProgressIndicator(progress = { it / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                color = if (t.charging) houseColors.green else MaterialTheme.colorScheme.primary) }
            val detail = if (t.charging) listOfNotNull(t.addedKwh?.let { "+${"%.1f".format(it)} kWh" }, t.toFull?.let { "$it to full" })
                else listOfNotNull(t.idleTitle, t.idleDetail)
            if (detail.isNotEmpty()) Text(detail.joinToString(" · "), Modifier.padding(top = 6.dp), fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            t.charger?.let { c -> Text(listOfNotNull(c.source, c.status, c.powerKw?.takeIf { it > 0 }?.let { "${"%.1f".format(it)} kW" })
                .joinToString(" · "), fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** A side-on silhouette in the car's own paint (the Portal draws a detailed Model Y; this is the phone's small version). */
@Composable
private fun CarArt(t: HouseTesla) {
    val top = rgbColor(t.paintTop)
    val bottom = rgbColor(t.paintBottom)
    val wheel = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.fillMaxWidth().height(96.dp).semantics { contentDescription = "${t.label} in ${t.paintName.ifBlank { "its paint" }}" }) {
        val w = size.width
        val h = size.height
        val body = Path().apply {
            moveTo(w * 0.08f, h * 0.72f); lineTo(w * 0.1f, h * 0.5f); cubicTo(w * 0.22f, h * 0.44f, w * 0.3f, h * 0.18f, w * 0.48f, h * 0.16f)
            cubicTo(w * 0.66f, h * 0.15f, w * 0.78f, h * 0.36f, w * 0.9f, h * 0.46f); lineTo(w * 0.93f, h * 0.72f); close()
        }
        drawPath(body, Brush.verticalGradient(listOf(top, bottom)))
        drawRoundRect(bottom.copy(alpha = 0.6f), Offset(w * 0.34f, h * 0.24f), Size(w * 0.3f, h * 0.16f), CornerRadius(8f))
        listOf(0.25f, 0.76f).forEach { x -> drawCircle(wheel, h * 0.15f, Offset(w * x, h * 0.74f)); drawCircle(bottom, h * 0.07f, Offset(w * x, h * 0.74f)) }
    }
}

@Composable
private fun LimitCard(t: HouseTesla, enabled: Boolean, onLimit: (Int) -> Unit) {
    val limit = t.limit ?: return
    HouseCard(Modifier.padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) { Text("Charge limit", fontWeight = FontWeight.Bold); Text("$limit%", fontSize = 13.sp) }
            val can = enabled && t.limitSettable
            Pill("−", can && limit - STEP >= t.limitMin) { onLimit((limit - STEP).coerceAtLeast(t.limitMin)) }
            Pill("+", can && limit + STEP <= t.limitMax) { onLimit((limit + STEP).coerceAtMost(t.limitMax)) }
        }
    }
}

@Composable
private fun ActionTile(a: HouseCarAction, enabled: Boolean, modifier: Modifier, onTap: () -> Unit) {
    Column(modifier.height(78.dp).background(if (a.on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, HouseShapes.tile)
        .clickable(enabled = enabled, role = Role.Button, onClick = onTap).padding(10.dp), verticalArrangement = Arrangement.SpaceBetween) {
        HouseGlyph(a.icon, size = 20.dp)
        Text(a.label, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun WeekCard(kwh: List<Double>, days: List<String>, price: Double?) {
    val bar = MaterialTheme.colorScheme.primary
    val max = (kwh.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
    HouseSection("Home charging this week")
    HouseCard {
        Column {
            Text("${"%.1f".format(kwh.sum())} kWh" + (price?.let { " · ${"$%.2f".format(it * kwh.sum())}" } ?: ""), fontWeight = FontWeight.Bold)
            Canvas(Modifier.fillMaxWidth().height(70.dp).padding(top = 8.dp)) {
                val step = size.width / kwh.size
                kwh.forEachIndexed { i, v ->
                    val bh = (v / max).toFloat() * size.height
                    drawRoundRect(bar, Offset(i * step + step * 0.2f, size.height - bh), Size(step * 0.6f, bh), CornerRadius(4f))
                }
            }
            Row { days.forEach { Text(it, Modifier.weight(1f), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        }
    }
}

@Composable
private fun tone(t: String) = when (t) {
    "green" -> houseColors.green
    "amber" -> houseColors.amber
    "red" -> houseColors.red
    else -> MaterialTheme.colorScheme.onSurface
}

private const val ACTIONS_PER_ROW = 3
private const val STEP = 5
