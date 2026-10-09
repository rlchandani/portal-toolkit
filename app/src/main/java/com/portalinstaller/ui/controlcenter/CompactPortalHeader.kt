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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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

/** One native app bar owns Portal selection, connection status, and the current-page refresh. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CompactPortalHeader(
    state: ControlCenterState,
    refreshing: Boolean,
    onSelectDevice: (String) -> Unit,
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
                ) {
                    state.devices.forEach { device ->
                        val current = device.id == state.activeDeviceId
                        DropdownMenuItem(
                            text = {
                                Text(device.name, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal)
                            },
                            onClick = { expanded = false; onSelectDevice(device.id) },
                            modifier = Modifier.background(
                                if (current) MaterialTheme.colorScheme.secondaryContainer
                                else Color.Transparent,
                            ).semantics { selected = current },
                        )
                    }
                    if (state.devices.isNotEmpty()) HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Add a Portal") },
                        onClick = { expanded = false; onAddPortal() },
                    )
                    // A saved selection can be disconnected or forgotten even while offline/busy.
                    activeDevice?.let { device ->
                        DropdownMenuItem(
                            text = { Text("Disconnect") },
                            onClick = { expanded = false; onDisconnect() },
                        )
                        DropdownMenuItem(
                            text = { Text("Forget this Portal") },
                            onClick = { expanded = false; onForgetPortal(device.id) },
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
