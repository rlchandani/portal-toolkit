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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portalinstaller.data.house.HouseAct
import com.portalinstaller.data.house.HouseEntity
import com.portalinstaller.data.house.HouseKind
import com.portalinstaller.data.house.HouseWords
import com.portalinstaller.ui.controlcenter.ReleaseOnlySlider

/** A device's full control (brightness and colour, fan speed, blinds, thermostat, media, locks, valves). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HouseDeviceSheet(e: HouseEntity, enabled: Boolean, reason: String?, onAct: (HouseAct) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, shape = HouseShapes.sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(e.name, fontFamily = FontFamily.Serif, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                    Text(HouseWords.status(e), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (e.kind in setOf(HouseKind.LIGHT, HouseKind.SWITCH, HouseKind.FAN) || e.kind == HouseKind.MEDIA)
                    Switch(e.isOn || e.state == "playing" || e.state == "paused", enabled = enabled && !e.unavailable,
                        onCheckedChange = { onAct(HouseAct.power(e, it)) })
            }
            if (!enabled && reason != null) Text(reason, color = houseColors.amber, style = MaterialTheme.typography.bodySmall)
            val on = enabled && !e.unavailable
            when (e.kind) {
                HouseKind.LIGHT -> LightControls(e, on, onAct)
                HouseKind.FAN -> Level("Speed", e.percentage ?: if (e.isOn) 100 else 0, 0..100, "%", on) { onAct(HouseAct.fan(e, it)) }
                HouseKind.COVER -> CoverControls(e, on, onAct)
                HouseKind.GARAGE -> if (e.domain == "cover") Buttons(on, "Open" to { onAct(HouseAct.open(e)) }, "Stop" to { onAct(HouseAct.stop(e)) },
                    "Close" to { onAct(HouseAct.close(e)) })
                HouseKind.LOCK -> Buttons(on, "Lock" to { onAct(HouseAct.lock(e)) }, "Unlock" to { onAct(HouseAct.unlock(e)) })
                HouseKind.VALVE -> if (e.domain == "valve") Buttons(on, "Shut off" to { onAct(HouseAct.close(e)) }, "Turn on" to { onAct(HouseAct.open(e)) })
                HouseKind.CLIMATE -> ClimateControls(e, on, onAct)
                HouseKind.MEDIA -> MediaControls(e, on, onAct)
                else -> Unit
            }
        }
    }
}

@Composable
private fun LightControls(e: HouseEntity, on: Boolean, onAct: (HouseAct) -> Unit) {
    if (e.dimmable) Level("Brightness", if (e.isOn) e.brightnessPct ?: 100 else 0, 0..100, "%", on) { onAct(HouseAct.brightness(e, it)) }
    if (e.supportsColorTemp) Level("White", e.colorTempK ?: e.minKelvin, e.minKelvin..e.maxKelvin, " K", on) { onAct(HouseAct.kelvin(e, it)) }
    if (e.supportsColor) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SWATCHES.forEach { rgb ->
            Box(Modifier.size(36.dp).background(rgbColor(rgb), CircleShape)
                .border(2.dp, if (e.rgb == rgb) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                .clickable(enabled = on) { onAct(HouseAct.color(e, rgb)) }.semantics { contentDescription = "Colour" })
        }
    }
}

@Composable
private fun CoverControls(e: HouseEntity, on: Boolean, onAct: (HouseAct) -> Unit) {
    Buttons(on, "Open" to { onAct(HouseAct.open(e)) }, "Stop" to { onAct(HouseAct.stop(e)) }, "Close" to { onAct(HouseAct.close(e)) })
    e.position?.let { Level("Open", it, 0..100, "%", on) { p -> onAct(HouseAct.position(e, p)) } }
}

@Composable
private fun ClimateControls(e: HouseEntity, on: Boolean, onAct: (HouseAct) -> Unit) {
    val target = e.targetTemp
    if (target != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Pill("−", on && target - e.tempStep >= e.minTemp) { onAct(HouseAct.temperature(e, target - e.tempStep)) }
        Text("${if (target % 1.0 == 0.0) target.toInt() else target}°", fontFamily = FontFamily.Serif, fontSize = 44.sp)
        Pill("+", on && target + e.tempStep <= e.maxTemp) { onAct(HouseAct.temperature(e, target + e.tempStep)) }
    }
    if (e.hvacModes.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        e.hvacModes.take(MAX_MODES).forEach { m ->
            FilterChip(selected = e.state == m, enabled = on, onClick = { onAct(HouseAct.hvac(e, m)) }, label = { Text(HouseWords.pretty(m)) })
        }
    }
}

@Composable
private fun MediaControls(e: HouseEntity, on: Boolean, onAct: (HouseAct) -> Unit) {
    Buttons(on, "Previous" to { onAct(HouseAct.previous(e)) }, (if (e.state == "playing") "Pause" else "Play") to { onAct(HouseAct.playPause(e)) },
        "Next" to { onAct(HouseAct.next(e)) })
    e.volume?.let { v -> Level("Volume", Math.round(v * 100), 0..100, "%", on) { onAct(HouseAct.volume(e, it / 100f)) } }
}

@Composable
private fun Buttons(enabled: Boolean, vararg items: Pair<String, () -> Unit>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (label, act) -> Box(Modifier.weight(1f)) { Pill(label, enabled, onClick = act) } }
    }
}

/** A slider that sends only when released (a cancelled drag sends nothing) and follows live values otherwise. */
@Composable
private fun Level(title: String, value: Int, range: IntRange, unit: String, enabled: Boolean, onCommit: (Int) -> Unit) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val shown = drag ?: value.coerceIn(range).toFloat()
    Column {
        Row { Text(title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold); Text("${Math.round(shown)}$unit") }
        ReleaseOnlySlider(value = shown, onValueChange = { drag = it }, onCommit = { drag?.let { onCommit(Math.round(it)) }; drag = null },
            onCancel = { drag = null }, enabled = enabled, modifier = Modifier.fillMaxWidth(),
            valueRange = range.first.toFloat()..range.last.toFloat())
    }
}

private val SWATCHES = listOf(0xFFB46B, 0xFFE3A8, 0xFF5A5A, 0x6FA8FF, 0x7FE0B0, 0xC58BFF)
private const val MAX_MODES = 5
