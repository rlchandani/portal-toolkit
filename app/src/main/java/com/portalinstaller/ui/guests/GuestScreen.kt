/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.guests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.portalinstaller.data.guests.GuestCopy
import com.portalinstaller.data.guests.GuestRepository
import com.portalinstaller.data.guests.GuestRoom
import com.portalinstaller.data.guests.GuestStart
import com.portalinstaller.data.guests.GuestState
import com.portalinstaller.ui.controlcenter.EmptyMessage
import com.portalinstaller.ui.controlcenter.GroupCard
import com.portalinstaller.ui.controlcenter.PageSections
import com.portalinstaller.ui.controlcenter.PageTitle
import com.portalinstaller.ui.controlcenter.SectionTitle
import kotlinx.coroutines.launch

/**
 * Manage → Guest card: choose one Portal or all, turn Guest mode on or off, and edit the Wi-Fi
 * and house basics the Portal shows visitors. Renders inside the control-center page column.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GuestScreen(repository: GuestRepository, modifier: Modifier = Modifier) {
    val state by repository.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(repository) { repository.refresh() }
    PageSections(modifier) {
        PageTitle("Guest card", "A Wi-Fi QR code and house basics on the Portal, with your photos, calendar and notes hidden.")
        state.error?.let { EmptyMessage("Something went wrong", it) }
        if (state.rooms.isEmpty() && !state.loading) {
            EmptyMessage("No Portals paired yet", "Pair a Portal from Settings, then set up the guest card here.")
            return@PageSections
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = state.target == null, onClick = { repository.selectTarget(null) }, label = { Text("All Portals") })
            state.rooms.forEach { room ->
                FilterChip(selected = state.target == room.id, onClick = { repository.selectTarget(room.id) },
                    label = { Text(roomLabel(room)) })
            }
        }
        state.message?.let { message ->
            Text(message, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        GuestModeCard(state, onStart = { scope.launch { repository.start(it) } }, onEnd = { scope.launch { repository.end() } })
        val shown = state.shown
        if (shown == null) {
            if (!state.loading) EmptyMessage(unreachableTitle(state), "Open Calder Pocket on home Wi-Fi, and update Calder on the Portal if needed.")
        } else {
            SectionTitle("Set up the guest card")
            if (shown.needsUpdate) {
                EmptyMessage("Update Calder on ${targetName(state)}", "Update Calder on the Portal to use the new guest setup. Guest mode still works.")
            } else {
                GuestSetup(shown, targetName(state), state.busy, state.targets.count { !it.needsUpdate } > 1) { draft ->
                    scope.launch { repository.save(draft.card, draft.password, draft.doorCodeChange) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GuestModeCard(state: GuestState, onStart: (GuestStart) -> Unit, onEnd: () -> Unit) {
    val online = state.targets.filter { it.online }
    val anyOn = online.any { it.snapshot?.active == true }
    GroupCard {
        Text("Guest mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        online.forEach { room ->
            val s = room.snapshot ?: return@forEach
            Text("${room.name}: ${GuestCopy.status(s.active, s.untilMs)}", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (online.any { it.snapshot?.ready == false }) {
            Text("Set up the Wi-Fi below so the card can show a QR code.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GuestStart.choices.forEach { (label, start) ->
                Button(onClick = { onStart(start) }, enabled = online.isNotEmpty() && !state.busy,
                    modifier = Modifier.heightIn(min = 56.dp).semantics {
                        contentDescription = "Start Guest mode ${label.lowercase()} on ${targetName(state)}"
                    }) { Text(label) }
            }
            if (anyOn) OutlinedButton(onClick = onEnd, enabled = !state.busy, modifier = Modifier.heightIn(min = 56.dp)) {
                Text("End Guest mode")
            }
        }
    }
}

private fun roomLabel(room: GuestRoom): String = when {
    room.outdated || room.needsUpdate -> "${room.name} (update Calder)"
    !room.online -> "${room.name} (offline)"
    room.snapshot?.active == true -> "${room.name} · Guests"
    else -> room.name
}

internal fun targetName(state: GuestState): String =
    if (state.target == null) "all Portals" else state.targets.firstOrNull()?.name ?: "Portal"

private fun unreachableTitle(state: GuestState): String =
    if (state.targets.any { it.outdated }) "Update Calder on ${targetName(state)}" else "Couldn’t reach ${targetName(state)}"
