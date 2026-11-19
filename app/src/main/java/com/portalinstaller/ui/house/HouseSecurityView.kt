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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portalinstaller.data.house.HouseAlarm
import com.portalinstaller.data.house.HouseSecItem
import com.portalinstaller.data.house.HouseSecSection
import com.portalinstaller.data.house.HouseSecurity

/**
 * Security (mock phone Security + the Portal's full sensor list): the alarm with its modes, who's
 * home, then every lock, garage, door, window, hazard, motion and tamper sensor grouped, attention
 * first, with inline Close / Lock. Disarming asks for the code (or the phone's confirm).
 */
internal fun LazyListScope.houseSecurity(sec: HouseSecurity, enabled: Boolean, onMode: (HouseAlarm, String) -> Unit,
                                          onRowAction: (HouseSecItem) -> Unit) {
    sec.alarm?.let { alarm -> item(key = "alarm") { AlarmCard(alarm, sec.readinessTitle, enabled, onMode) } }
    if (sec.alarm == null && sec.readinessTitle.isNotBlank()) item(key = "ready") { HouseCard { Text(sec.readinessTitle) } }
    if (sec.people.isNotEmpty()) {
        item(key = "who") { HouseSection("Who's home") }
        item(key = "people") {
            HouseCard(padding = 0) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                    sec.people.forEachIndexed { i, p ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(38.dp).background(if (p.home) Color(0xFF8FBCFF) else MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                                contentAlignment = Alignment.Center) { Text(p.name.take(1), color = Color(0xFF14161B), fontWeight = FontWeight.ExtraBold) }
                            Column(Modifier.weight(1f)) { Text(p.name, fontWeight = FontWeight.SemiBold); Text(p.line, fontSize = 12.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        }
    }
    if (sec.sections.isNotEmpty()) item(key = "sensors") { HouseSection("Sensors") }
    items(sec.sections, key = { "sec:${it.group}" }) { s -> SectionCard(s, enabled, onRowAction) }
}

@Composable
private fun AlarmCard(alarm: HouseAlarm, readiness: String, enabled: Boolean, onMode: (HouseAlarm, String) -> Unit) {
    val c = houseColors
    val tint = when (alarm.tone) { "armed" -> c.green; "alert" -> c.red; "arming" -> c.amber; else -> MaterialTheme.colorScheme.onSurface }
    HouseCard {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            HouseGlyph("shield", tint = tint, size = 30.dp)
            Text(alarm.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
            Text(alarm.subtitle.ifBlank { readiness }, textAlign = TextAlign.Center, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                alarm.modes.forEach { m ->
                    val on = m == alarm.current || m == "disarm" && alarm.state == "disarmed"
                    val label = MODE_WORDS[m] ?: m
                    Box(Modifier.weight(1f)) {
                        if (on) Button({}, enabled = false, modifier = Modifier.fillMaxWidth().height(44.dp)) { Text(label) }
                        else FilledTonalButton({ onMode(alarm, m) }, enabled = enabled, modifier = Modifier.fillMaxWidth().height(44.dp)) { Text(label) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(s: HouseSecSection, enabled: Boolean, onRowAction: (HouseSecItem) -> Unit) {
    val c = houseColors
    var open by rememberSaveable(s.group) { mutableStateOf(s.ok == false) }
    Box(Modifier.padding(bottom = 12.dp)) {
        HouseCard(padding = 0) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { open = !open }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HouseGlyph(s.icon)
                    Column(Modifier.weight(1f)) {
                        Text(s.title, fontWeight = FontWeight.Bold)
                        Text(s.summary, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text(s.tag, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                        color = when (s.ok) { true -> c.green; false -> c.amber; null -> MaterialTheme.colorScheme.onSurfaceVariant })
                }
                if (open) s.items.forEach { i -> SensorRow(i, enabled, onRowAction) }
            }
        }
    }
}

@Composable
private fun SensorRow(i: HouseSecItem, enabled: Boolean, onRowAction: (HouseSecItem) -> Unit) {
    val c = houseColors
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(i.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(i.area, i.state, HouseTime.since(i.since)).joinToString(" · "), fontSize = 12.sp,
                color = if (i.attention) c.amber else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when (i.action) {
            "close" -> Pill("Close", enabled, warn = true) { onRowAction(i) }
            "lock" -> Pill("Lock", enabled) { onRowAction(i) }
        }
    }
}

private val MODE_WORDS = mapOf("disarm" to "Off", "home" to "Home", "away" to "Away", "night" to "Night", "vacation" to "Away+")
