package com.portalinstaller.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.portalinstaller.R
import com.portalinstaller.data.DiscoveredPortal
import com.portalinstaller.viewmodel.PortalDiscoveryState

@Composable
internal fun PortalScannerDialog(
    discoveryState: PortalDiscoveryState,
    port: String,
    onScan: () -> Unit,
    onConnect: (DiscoveredPortal) -> Unit,
    onDismiss: () -> Unit
) {
    val isScanning = discoveryState is PortalDiscoveryState.Scanning

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.network_portals_title)) },
        text = {
            when (discoveryState) {
                is PortalDiscoveryState.Idle -> {
                    Text(stringResource(R.string.network_portals_description))
                }
                is PortalDiscoveryState.Scanning -> {
                    Column {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            stringResource(
                                R.string.discovery_progress,
                                discoveryState.scanned,
                                discoveryState.total,
                                discoveryState.found
                            )
                        )
                    }
                }
                is PortalDiscoveryState.Success -> {
                    if (discoveryState.portals.isEmpty()) {
                        Text(
                            stringResource(
                                R.string.discovery_no_portals,
                                port.ifBlank { "5555" }
                            )
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(
                                items = discoveryState.portals,
                                key = { "${it.ip}:${it.port}" }
                            ) { portal ->
                                DiscoveredPortalItem(
                                    portal = portal,
                                    onConnect = { onConnect(portal) }
                                )
                            }
                        }
                    }
                }
                is PortalDiscoveryState.Failed -> {
                    Text(discoveryState.message)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onScan, enabled = !isScanning) {
                Text(
                    if (isScanning) {
                        stringResource(R.string.searching)
                    } else {
                        stringResource(R.string.search)
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
internal fun DiscoveredPortalItem(
    portal: DiscoveredPortal,
    onConnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = portal.displayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "IP: ${portal.ip}:${portal.port}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val subtitle = listOf(portal.brand, portal.model)
                .filter { it.isNotBlank() && it != "unknown" }
                .joinToString(" • ")
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(
                onClick = onConnect,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(stringResource(R.string.connect))
            }
        }
    }
}
