/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.house

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portalinstaller.data.house.HouseAlert

/**
 * Alerts for every sensor the Portal watches, most serious first (mock phone Alerts): leaks and
 * smoke in red, things left open in amber, done and low battery quiet. Close / Shut off water act
 * through the Portal's policy (and its confirm); Dismiss, Snooze and Remind hide on this phone only.
 */
internal fun LazyListScope.houseAlerts(alerts: List<HouseAlert>, enabled: Boolean, onFix: (HouseAlert) -> Unit,
                                        onHide: (HouseAlert) -> Unit, onRoom: (String) -> Unit) {
    if (alerts.isEmpty()) item(key = "clear") { HouseCard { Text("All clear. Nothing open, wet or waiting.") } }
    items(alerts, key = { it.id }) { a -> AlertCard(a, enabled, onFix, onHide, onRoom) }
}

@Composable
private fun AlertCard(a: HouseAlert, enabled: Boolean, onFix: (HouseAlert) -> Unit, onHide: (HouseAlert) -> Unit, onRoom: (String) -> Unit) {
    val c = houseColors
    val (bg, ink) = when {
        a.critical -> c.redBg to c.red
        a.warning -> c.amberBg to c.amber
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurface
    }
    val border = if (a.critical) Modifier.border(2.dp, c.red, HouseShapes.card) else Modifier
    Box(Modifier.padding(bottom = 12.dp)) {
        HouseCard(border.clickable(enabled = a.room != null, role = Role.Button) { a.room?.let(onRoom) }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(38.dp).background(bg, HouseShapes.icon), contentAlignment = Alignment.Center) { HouseGlyph(icon(a), tint = ink) }
                Column(Modifier.weight(1f)) {
                    Text(a.title, fontWeight = FontWeight.Bold, fontSize = 14.5.sp)
                    Text(a.body, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when {
                    a.act != null && a.actEntity != null -> Pill(a.label ?: "Fix", enabled, warn = a.warning, danger = a.critical) { onFix(a) }
                    a.hidesLocally -> TextButton(onClick = { onHide(a) }) { Text(a.label ?: "Dismiss") }
                }
            }
        }
    }
}

/** The Portal's icon names, mapped to the phone's glyph set. */
private fun icon(a: HouseAlert): String = when (a.icon) {
    "leak", "drop", "water" -> "drop"
    "smoke", "warn" -> "warn"
    "garage" -> "garage"
    "door" -> "door"
    "window" -> "window"
    "battery" -> "bolt"
    "washer", "dryer", "dish", "oven" -> "washer"
    else -> if (a.kind == "leak") "drop" else a.icon
}
