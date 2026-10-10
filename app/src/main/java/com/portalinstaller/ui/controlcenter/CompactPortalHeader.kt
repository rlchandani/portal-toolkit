/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.controlcenter

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portalinstaller.data.companion.CompanionConnection
import com.portalinstaller.data.companion.ControlCenterState
import com.portalinstaller.ui.common.PremiumDropdownMenu

/** One native app bar owns viewing and write targets independently, plus status and refresh. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CompactPortalHeader(
    state: ControlCenterState,
    refreshing: Boolean,
    onSelectDevice: (String) -> Unit,
    onSelectTargets: (Set<String>) -> Unit,
    onAddPortal: () -> Unit,
    onDisconnect: () -> Unit,
    onForgetPortal: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val activeDevice = state.activeDevice
    val connectionLabel = when (state.connection) {
        CompanionConnection.UNPAIRED -> "No Portal selected"
        CompanionConnection.CONNECTING -> "Connecting…"
        CompanionConnection.CONNECTED -> "Connected"
        CompanionConnection.OFFLINE -> "Offline · Waiting to reconnect"
        CompanionConnection.UNAUTHORIZED -> "Pairing expired · Pair again"
    }
    val connectionColor = when (state.connection) {
        CompanionConnection.CONNECTED -> MaterialTheme.colorScheme.primary
        CompanionConnection.CONNECTING -> MaterialTheme.colorScheme.tertiary
        CompanionConnection.UNAUTHORIZED -> MaterialTheme.colorScheme.error
        CompanionConnection.UNPAIRED, CompanionConnection.OFFLINE ->
            MaterialTheme.colorScheme.onSurfaceVariant
    }
    val canRefresh = activeDevice != null && !state.busy && !refreshing
    // Two 18sp name lines plus a 14sp status line fit at ordinary scale.
    // At 2x, one name line and the status occupy the native 64dp app bar.
    val nameMaxLines = if (LocalDensity.current.fontScale <= 1.25f) 2 else 1

    TopAppBar(
        title = {
            Box(Modifier.fillMaxWidth().padding(end = 16.dp)) {
                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp, max = 64.dp)
                        .testTag("manage-portals")
                        .semantics {
                            role = Role.DropdownList
                            contentDescription = "Active Portal"
                            // Preserve the complete status even when its visual label ellipsizes.
                            stateDescription = "$connectionLabel, ${if (expanded) "Expanded" else "Collapsed"}"
                        },
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            activeDevice?.name ?: "Immortal",
                            style = MaterialTheme.typography.titleSmall.copy(lineHeight = 18.sp),
                            maxLines = nameMaxLines,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).background(connectionColor, CircleShape))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                connectionLabel,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelSmall.copy(lineHeight = 14.sp),
                                color = if (state.connection == CompanionConnection.UNAUTHORIZED)
                                    MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded)
                }
                PremiumDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.widthIn(max = 280.dp),
                ) {
                    Text("View Portal", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.titleSmall)
                    if (state.devices.isEmpty()) {
                        Text("No paired Portals. Add a Portal to get started.",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    state.devices.forEach { device ->
                        val current = device.id == state.activeDeviceId
                        DropdownMenuItem(
                            text = {
                                Text(device.name, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal)
                            },
                            leadingIcon = { RadioButton(selected = current, onClick = null) },
                            onClick = { expanded = false; onSelectDevice(device.id) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).background(
                                if (current) MaterialTheme.colorScheme.secondaryContainer
                                else Color.Transparent,
                            ).testTag("view-portal-${device.id}").semantics {
                                role = Role.RadioButton
                                selected = current
                            },
                        )
                    }
                    HorizontalDivider()
                    Text("Send changes to", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.titleSmall)
                    DropdownMenuItem(
                        text = { Text("Select all") },
                        onClick = { onSelectTargets(state.devices.map { it.id }.toSet()) },
                        enabled = state.devices.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .testTag("select-all-portals"),
                    )
                    DropdownMenuItem(
                        text = { Text("Clear selection") },
                        onClick = { onSelectTargets(emptySet()) },
                        enabled = state.selectedTargetIds.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .testTag("clear-portal-selection"),
                    )
                    if (state.selectedTargets.isEmpty()) {
                        Text("No targets selected. Choose Portals before applying changes.",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    state.devices.forEach { device ->
                        val checked = device.id in state.selectedTargetIds
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .toggleable(checked, role = Role.Checkbox, onValueChange = { selected ->
                                    onSelectTargets(if (selected) state.selectedTargetIds + device.id
                                        else state.selectedTargetIds - device.id)
                                }).testTag("group-target-${device.id}")
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(device.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("Imports: ${device.photoFolder}",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (state.targetSelectionSaving) {
                        Text("Saving selection…", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Add a Portal") },
                        onClick = { expanded = false; onAddPortal() },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    )
                    // A saved selection can be disconnected or forgotten even while offline/busy.
                    activeDevice?.let { device ->
                        DropdownMenuItem(
                            text = { Text("Disconnect") },
                            onClick = { expanded = false; onDisconnect() },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        )
                        DropdownMenuItem(
                            text = { Text("Forget this Portal") },
                            onClick = { expanded = false; onForgetPortal(device.id) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        )
                    }
                }
            }
        },
        actions = {
            IconButton(
                onClick = { if (canRefresh) onRefresh() },
                enabled = canRefresh,
                modifier = Modifier.size(48.dp)
                    .semantics { contentDescription = "Refresh current page" },
            ) {
                ControlGlyph("refresh",
                    tint = if (canRefresh) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f),
                    modifier = Modifier.size(22.dp))
            }
        },
    )
}
