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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portalinstaller.data.house.HouseAct
import com.portalinstaller.data.house.HouseEntity
import com.portalinstaller.data.house.HouseKind
import com.portalinstaller.data.house.HousePlan
import com.portalinstaller.data.house.HouseScene
import com.portalinstaller.data.house.HouseSnapshot
import com.portalinstaller.data.house.HouseWords

/** What the Map screen needs, worked out once per snapshot (not inside item lambdas). */
internal data class HouseMapModel(val openGarages: List<HouseEntity>, val rooms: List<Pair<String, String>>, val floorName: String)

internal fun mapModel(plan: HousePlan?, s: HouseSnapshot, floorId: String?): HouseMapModel {
    val floor = plan?.floor(floorId)
    val garages = s.entities.values.filter { it.kind == HouseKind.GARAGE && it.domain == "cover" && it.isOpen }.sortedBy { it.id }
    return HouseMapModel(garages, floor?.rooms.orEmpty().map { it.id to it.name }, floor?.name.orEmpty())
}

/** The Map screen (mock phone "Home"): the plan, an open garage, scenes, then the floor's rooms. */
internal fun LazyListScope.houseMap(
    plan: HousePlan?, s: HouseSnapshot, model: HouseMapModel, floorId: String?, lens: HouseLens, enabled: Boolean,
    onFloor: (String) -> Unit, onLens: (HouseLens) -> Unit, onRoom: (String) -> Unit, onAct: (HouseAct) -> Unit,
    onScene: (HouseScene) -> Unit, onRoomLights: (String, Boolean) -> Unit,
) {
    if (plan != null && !plan.isEmpty) item(key = "plan") {
        HouseMapCard(plan.floors, floorId, lens, s, onFloor, onLens, onRoom)
    } else item(key = "noplan") {
        HouseCard { Text("No floor plan yet. Draw it once on any Portal (Control Center › Edit); every Portal and this phone then share it.") }
    }
    items(model.openGarages, key = { "garage:${it.id}" }) { g -> GarageCard(g, enabled) { onAct(HouseAct.close(g)) } }
    if (s.scenes.isNotEmpty()) item(key = "scenes") { SceneStrip(s.scenes, enabled, onScene) }
    if (model.rooms.isNotEmpty()) item(key = "floor") { HouseSection(model.floorName) }
    items(model.rooms, key = { "room:${it.first}" }) { (id, name) ->
        val first = model.rooms.first().first == id
        val last = model.rooms.last().first == id
        RoomRow(name, s.room(id)?.let { v -> HouseWords.roomLine(v, emptyList()) } ?: "", s.room(id)?.lightsOn ?: 0,
            s.room(id)?.lights ?: 0, enabled, roomShape(first, last), { onRoom(id) }) { on -> onRoomLights(id, on) }
    }
}

private fun roomShape(first: Boolean, last: Boolean) = RoundedCornerShape(
    topStart = if (first) 20.dp else 0.dp, topEnd = if (first) 20.dp else 0.dp,
    bottomStart = if (last) 20.dp else 0.dp, bottomEnd = if (last) 20.dp else 0.dp)

@androidx.compose.runtime.Composable
private fun GarageCard(g: HouseEntity, enabled: Boolean, onClose: () -> Unit) {
    val c = houseColors
    HouseCard(Modifier.padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).background(c.amberBg, HouseShapes.icon), contentAlignment = Alignment.Center) {
                HouseGlyph("garage", tint = c.amber)
            }
            Column(Modifier.weight(1f)) {
                Text("${g.name} ${if (g.state == "closing") "closing…" else "open"}", fontWeight = FontWeight.Bold)
                Text(HouseWords.status(g), fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (g.state == "open") Pill("Close", enabled, warn = true, onClick = onClose)
        }
    }
}

@androidx.compose.runtime.Composable
private fun SceneStrip(scenes: List<HouseScene>, enabled: Boolean, onScene: (HouseScene) -> Unit) {
    LazyRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(scenes, key = { it.id }) { sc ->
            val (top, bottom, ink) = sceneColors(sc.tone)
            Column(Modifier.width(112.dp).height(64.dp).background(Brush.linearGradient(listOf(top, bottom)), HouseShapes.tile)
                .clickable(enabled = enabled, role = Role.Button) { onScene(sc) }.padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.Center) {
                HouseGlyph(sc.icon, tint = ink, size = 20.dp)
                Text(sc.name, color = ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun RoomRow(name: String, line: String, lightsOn: Int, lights: Int, enabled: Boolean, shape: RoundedCornerShape,
                    onOpen: () -> Unit, onLights: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, shape).clickable(role = Role.Button, onClick = onOpen)
        .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(38.dp).background(if (lightsOn > 0) Color(0xFFFFD18A) else MaterialTheme.colorScheme.surfaceVariant, HouseShapes.icon),
            contentAlignment = Alignment.Center) {
            HouseGlyph("bulb", tint = if (lightsOn > 0) Color(0xFF2A1A05) else MaterialTheme.colorScheme.onSurface, size = 21.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (lights > 0) Switch(lightsOn > 0, enabled = enabled, onCheckedChange = onLights,
            colors = SwitchDefaults.colors(checkedTrackColor = houseColors.green))
    }
}
