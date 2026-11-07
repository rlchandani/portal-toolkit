/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.routines

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.portalinstaller.data.routines.RoutineKid
import com.portalinstaller.data.routines.RoutinePictures
import com.portalinstaller.data.routines.RoutineSnapshot
import com.portalinstaller.data.routines.RoutineTemplates
import com.portalinstaller.viewmodel.RoutinesController

private const val MAX_KIDS = 4
private const val KID_NAME_MAX = 16

/**
 * The kids a routine can be for ("Who it's for"). Two or more kids on one routine give each its
 * own tab on the Portal. Avatars alternate fox/owl (the bundled set); no photos of people.
 */
@Composable
internal fun KidsSection(controller: RoutinesController, snapshot: RoutineSnapshot) {
    val state by controller.state.collectAsState()
    var name by remember { mutableStateOf("") }
    Text("Kids", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (snapshot.kids.isEmpty()) Text("No kids yet. Routines are for everyone until you add some.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            snapshot.kids.forEach { k ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RoutineAvatar(k.avatar)
                    Text(k.name, modifier = Modifier.weight(1f))
                    TextButton(onClick = { controller.saveKids(snapshot.kids - k) }, enabled = !state.sending) { Text("Remove") }
                }
            }
            if (snapshot.kids.size < MAX_KIDS) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(KID_NAME_MAX) }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Kid’s name") })
                Button(onClick = {
                    val avatar = RoutinePictures.avatars[snapshot.kids.size % RoutinePictures.avatars.size]
                    controller.saveKids(snapshot.kids + RoutineKid(RoutineTemplates.newId("k"), name.trim(), avatar))
                    name = ""
                }, enabled = name.isNotBlank() && !state.sending) { Text("Add") }
            }
        }
    }
}
