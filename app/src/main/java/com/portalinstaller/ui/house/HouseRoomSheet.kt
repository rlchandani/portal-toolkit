/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.house

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portalinstaller.data.house.HouseAct
import com.portalinstaller.data.house.HouseEntity
import com.portalinstaller.data.house.HouseKind
import com.portalinstaller.data.house.HousePlan
import com.portalinstaller.data.house.HouseRepository
import com.portalinstaller.data.house.HouseSnapshot
import com.portalinstaller.data.house.HouseWords

/** A room's devices, controllable first; its camera on top (the mock's phone room view). */
internal object HouseRoomLogic {
    fun devices(plan: HousePlan?, s: HouseSnapshot, roomId: String): List<HouseEntity> {
        val room = plan?.room(roomId) ?: return emptyList()
        return room.devices.mapNotNull { s.entity(it.entityId) }.filter { it.kind != HouseKind.CAMERA }.distinctBy { it.id }
            .sortedWith(compareBy({ it.kind.order }, { it.name.lowercase() }))
    }

    fun camera(plan: HousePlan?, s: HouseSnapshot, roomId: String): String? =
        plan?.room(roomId)?.devices?.firstOrNull { it.entityId.startsWith("camera.") && s.entity(it.entityId) != null }?.entityId

    /** The room's lights, for the one-tap room toggle. */
    fun lights(plan: HousePlan?, s: HouseSnapshot, roomId: String): List<HouseEntity> =
        devices(plan, s, roomId).filter { it.kind == HouseKind.LIGHT && !it.unavailable }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HouseRoomSheet(roomId: String, plan: HousePlan?, s: HouseSnapshot, repository: HouseRepository, pending: Set<String>,
                            enabled: Boolean, onAct: (HouseAct) -> Unit, onDevice: (String) -> Unit, onCamera: (String) -> Unit,
                            onDismiss: () -> Unit) {
    val room = plan?.room(roomId) ?: return
    val devices = remember(s.entities, plan, roomId) { HouseRoomLogic.devices(plan, s, roomId) }
    val camera = remember(s.entities, plan, roomId) { HouseRoomLogic.camera(plan, s, roomId) }
    ModalBottomSheet(onDismissRequest = onDismiss, shape = HouseShapes.sheet) {
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
            item(key = "title") {
                Text(room.name, fontFamily = FontFamily.Serif, fontSize = 24.sp, fontWeight = FontWeight.Medium)
                Text(HouseWords.roomLine(s.room(roomId), emptyList()), Modifier.padding(bottom = 10.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            camera?.let { cam ->
                item(key = "camera") {
                    HouseCameraPicture(cam, repository, Modifier.fillMaxWidth().height(170.dp).padding(bottom = 10.dp)
                        .clickable { onCamera(cam) }, captured = s.cameras.firstOrNull { it.id == cam }?.captured)
                }
            }
            if (devices.isEmpty()) item(key = "empty") { Text("Nothing placed in this room yet. Place devices on a Portal: Control Center › Edit.") }
            items(devices, key = { it.id }) { e ->
                HouseDeviceRow(e, e.id in pending, enabled, onAct, onDevice)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}
