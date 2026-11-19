/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.house

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portalinstaller.data.house.HouseAct
import com.portalinstaller.data.house.HouseEntity
import com.portalinstaller.data.house.HouseKind
import com.portalinstaller.data.house.HouseWords

/**
 * One device row (mock `.prow`): icon tile, name, state in words and its one quick control —
 * a toggle, Lock / Unlock, Close for an open garage, Shut off for running water, play/pause.
 * Tapping the row opens the full control. Controls show disabled unless values are live.
 */
@Composable
internal fun HouseDeviceRow(e: HouseEntity, pending: Boolean, enabled: Boolean, onAct: (HouseAct) -> Unit, onOpen: (String) -> Unit) {
    val c = houseColors
    val danger = e.kind == HouseKind.LEAK && e.state == "on"
    val lit = e.kind == HouseKind.LIGHT && e.isOn
    Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).clickable(role = Role.Button) { onOpen(e.id) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(38.dp).background(if (lit) e.rgb?.let { rgbColor(it, 0.9f) } ?: WARM_LIGHT else MaterialTheme.colorScheme.surfaceVariant,
            HouseShapes.icon), contentAlignment = Alignment.Center) {
            HouseGlyph(e.kind.glyph, tint = if (lit) Color(0xFF2A1A05) else MaterialTheme.colorScheme.onSurface, size = 21.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(e.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(HouseWords.status(e), fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (danger) c.red else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (danger) FontWeight.Bold else null)
        }
        if (pending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else QuickControl(e, enabled && !e.unavailable, onAct)
    }
}

@Composable
private fun QuickControl(e: HouseEntity, enabled: Boolean, onAct: (HouseAct) -> Unit) {
    val c = houseColors
    when (e.kind) {
        HouseKind.LIGHT, HouseKind.SWITCH, HouseKind.FAN -> Switch(checked = e.isOn, enabled = enabled,
            onCheckedChange = { onAct(HouseAct.power(e, it)) }, colors = SwitchDefaults.colors(checkedTrackColor = c.green))
        HouseKind.APPLIANCE -> if (e.domain == "switch") Switch(checked = e.isOn, enabled = enabled, onCheckedChange = { onAct(HouseAct.power(e, it)) })
        HouseKind.LOCK -> when (e.state) {
            "locked" -> Pill("Unlock", enabled) { onAct(HouseAct.unlock(e)) }
            "unlocked", "open", "jammed" -> Pill("Lock", enabled) { onAct(HouseAct.lock(e)) }
            else -> Unit
        }
        HouseKind.GARAGE -> if (e.domain == "cover") when (e.state) {
            "open" -> Pill("Close", enabled, warn = true) { onAct(HouseAct.close(e)) }
            "closed" -> Pill("Open", enabled) { onAct(HouseAct.open(e)) }
            else -> Unit
        }
        HouseKind.VALVE -> if (e.domain == "valve") {
            if (e.isOpen) Pill("Shut off", enabled, danger = true) { onAct(HouseAct.close(e)) } else Pill("Turn on", enabled) { onAct(HouseAct.open(e)) }
        }
        HouseKind.MEDIA -> if (e.state == "playing" || e.state == "paused") IconPill(if (e.state == "playing") "pause" else "play", enabled) {
            onAct(HouseAct.playPause(e))
        }
        else -> Unit
    }
}

@Composable
internal fun Pill(text: String, enabled: Boolean, warn: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    val c = houseColors
    when {
        warn -> Button(onClick, enabled = enabled, colors = ButtonDefaults.buttonColors(containerColor = c.amber, contentColor = Color(0xFF1B120B)),
            modifier = Modifier.height(40.dp)) { Text(text, fontWeight = FontWeight.SemiBold) }
        danger -> Button(onClick, enabled = enabled, colors = ButtonDefaults.buttonColors(containerColor = c.red, contentColor = Color.White),
            modifier = Modifier.height(40.dp)) { Text(text, fontWeight = FontWeight.SemiBold) }
        else -> FilledTonalButton(onClick, enabled = enabled, modifier = Modifier.height(40.dp)) { Text(text, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun IconPill(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(onClick, enabled = enabled, modifier = Modifier.size(44.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
        HouseGlyph(glyph, size = 20.dp)
    }
}

private val WARM_LIGHT = Color(0xFFFFD18A)
