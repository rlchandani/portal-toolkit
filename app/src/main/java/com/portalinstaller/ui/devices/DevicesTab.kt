package com.portalinstaller.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.portalinstaller.R
import com.portalinstaller.ui.common.SectionCard
import com.portalinstaller.ui.common.SectionCardShape
import com.portalinstaller.ui.common.formatRam
import com.portalinstaller.ui.common.formatStorage
import com.portalinstaller.data.DeviceStatus
import com.portalinstaller.data.DiscoveredPortal
import com.portalinstaller.data.store.SavedPortal
import com.portalinstaller.data.UsbPortalStatus
import com.portalinstaller.viewmodel.BackupState
import com.portalinstaller.viewmodel.ConnectionMethod
import com.portalinstaller.viewmodel.ConnectionState
import com.portalinstaller.viewmodel.OsUpdateState
import com.portalinstaller.viewmodel.PortalDiscoveryState
import com.portalinstaller.viewmodel.WirelessAdbState

@Composable
internal fun DevicesTab(
    ipAddress: String,
    port: String,
    connectionState: ConnectionState,
    connectionMethod: ConnectionMethod?,
    deviceStatus: DeviceStatus?,
    savedPortals: List<SavedPortal>,
    usbStatus: UsbPortalStatus,
    wirelessAdbState: WirelessAdbState,
    osUpdateState: OsUpdateState,
    backupState: BackupState,
    portalPackages: List<String>,
    isLoadingPortalPackages: Boolean,
    selectedBackupPackages: Set<String>?,
    connectionEvents: List<com.portalinstaller.data.ConnectionEvent>,
    onRefreshDashboard: () -> Unit,
    onReboot: () -> Unit,
    onRebootRecovery: () -> Unit,
    onScreenOn: () -> Unit,
    onScreenOff: () -> Unit,
    onLaunchApp: (String) -> Unit,
    onIpChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onConnectManual: () -> Unit,
    onConnectSaved: (SavedPortal) -> Unit,
    onRemoveSaved: (SavedPortal) -> Unit,
    onRenameSaved: (SavedPortal, String) -> Unit,
    onDisconnect: () -> Unit,
    onScanPortals: () -> Unit,
    onRefreshUsb: () -> Unit,
    onRequestUsbPermission: () -> Unit,
    onConnectUsb: () -> Unit,
    onEnableWirelessAdb: () -> Unit,
    onRefreshOsUpdateStatus: () -> Unit,
    onSetOsUpdatesBlocked: (Boolean) -> Unit,
    onChooseBackupApps: () -> Unit,
    onCreateBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
    onCancelBackup: () -> Unit
) {
    var selectedSection by rememberSaveable { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedSection) {
            Tab(
                selected = selectedSection == 0,
                onClick = { selectedSection = 0 },
                text = { Text(stringResource(R.string.devices_wifi)) }
            )
            Tab(
                selected = selectedSection == 1,
                onClick = { selectedSection = 1 },
                text = { Text(stringResource(R.string.devices_backup)) }
            )
            Tab(
                selected = selectedSection == 2,
                onClick = { selectedSection = 2 },
                text = { Text(stringResource(R.string.devices_usb)) }
            )
        }

        if (selectedSection == 0) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    ConnectionSection(
                        ipAddress = ipAddress,
                        port = port,
                        connectionState = connectionState,
                        connectionMethod = connectionMethod,
                        deviceStatus = deviceStatus,
                        onIpChange = onIpChange,
                        onPortChange = onPortChange,
                        onConnect = onConnectManual,
                        onDisconnect = onDisconnect,
                        onScanPortals = onScanPortals
                    )
                }
                if (connectionState is ConnectionState.Connected) {
                    item {
                        DashboardCard(
                            deviceStatus = deviceStatus,
                            portalPackages = portalPackages,
                            onRefresh = onRefreshDashboard,
                            onReboot = onReboot,
                            onRebootRecovery = onRebootRecovery,
                            onScreenOn = onScreenOn,
                            onScreenOff = onScreenOff,
                            onLaunchApp = onLaunchApp
                        )
                    }
                }
                item {
                    OsUpdateBlockCard(
                        connectionState = connectionState,
                        state = osUpdateState,
                        onRefresh = onRefreshOsUpdateStatus,
                        onSetBlocked = onSetOsUpdatesBlocked
                    )
                }
                item {
                    SavedPortalsCard(
                        portals = savedPortals,
                        currentAddress = if (connectionState is ConnectionState.Connected) {
                            "$ipAddress:${port.ifBlank { "5555" }}"
                        } else {
                            null
                        },
                        onConnect = onConnectSaved,
                        onRemove = onRemoveSaved,
                        onRename = onRenameSaved
                    )
                }
                if (connectionEvents.isNotEmpty()) {
                    item {
                        ConnectionLogCard(events = connectionEvents)
                    }
                }
            }
        } else if (selectedSection == 1) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp)
            ) {
                item {
                    BackupCard(
                        isConnected = connectionState is ConnectionState.Connected,
                        backupState = backupState,
                        availablePackageCount = portalPackages.size,
                        isLoadingPackages = isLoadingPortalPackages,
                        selectedPackages = selectedBackupPackages,
                        onChooseApps = onChooseBackupApps,
                        onCreateBackup = onCreateBackup,
                        onRestoreBackup = onRestoreBackup,
                        onCancel = onCancelBackup
                    )
                }
            }
        } else {
            WirelessAdbGuideTab(
                connectionState = connectionState,
                connectionMethod = connectionMethod,
                usbStatus = usbStatus,
                wirelessAdbState = wirelessAdbState,
                port = port,
                onRefreshUsb = onRefreshUsb,
                onRequestUsbPermission = onRequestUsbPermission,
                onConnectUsb = onConnectUsb,
                onEnableWirelessAdb = onEnableWirelessAdb
            )
        }
    }
}

@Composable
internal fun SavedPortalsCard(
    portals: List<SavedPortal>,
    currentAddress: String?,
    onConnect: (SavedPortal) -> Unit,
    onRemove: (SavedPortal) -> Unit,
    onRename: (SavedPortal, String) -> Unit
) {
    var portalToRename by remember { mutableStateOf<SavedPortal?>(null) }

    SectionCard {
        Text(
            text = stringResource(R.string.saved_portals_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (portals.isEmpty()) {
            Text(
                text = stringResource(R.string.saved_portals_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            portals.forEachIndexed { index, portal ->
                if (index > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                }
                val isCurrent = currentAddress == portal.id
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isCurrent) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                        } else {
                            MaterialTheme.colorScheme.surface
                        }
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = portal.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (isCurrent) {
                                Text(
                                    text = stringResource(R.string.active),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Text(
                            text = portal.id,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (portal.model.isNotBlank() && portal.model != portal.name) {
                            Text(
                                text = portal.model,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = { onConnect(portal) },
                                enabled = !isCurrent,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text(
                                    if (isCurrent) {
                                        stringResource(R.string.active)
                                    } else {
                                        stringResource(R.string.connect)
                                    }
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = { portalToRename = portal }) {
                                Text(stringResource(R.string.rename))
                            }
                            TextButton(
                                onClick = { onRemove(portal) },
                                enabled = !isCurrent
                            ) {
                                Text(stringResource(R.string.remove))
                            }
                        }
                    }
                }
            }
        }
    }

    // -- dialog redenumire (in afara cardului) --
    portalToRename?.let { portal ->
        RenamePortalDialog(
            portal = portal,
            onDismiss = { portalToRename = null },
            onSave = { name ->
                onRename(portal, name)
                portalToRename = null
            }
        )
    }
}

@Composable
internal fun RenamePortalDialog(
    portal: SavedPortal,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var name by remember(portal.id) { mutableStateOf(portal.name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.portal_rename_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.portal_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = portal.id,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name) },
                enabled = name.isNotBlank()
            ) {
                Text(stringResource(R.string.profile_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

@Composable
internal fun ConnectionSection(
    ipAddress: String,
    port: String,
    connectionState: ConnectionState,
    connectionMethod: ConnectionMethod?,
    deviceStatus: DeviceStatus?,
    onIpChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onScanPortals: () -> Unit
) {
    val isConnected = connectionState is ConnectionState.Connected
    val ipSummary = ipAddress.ifBlank { "-" }
    val portSummary = port.ifBlank { "5555" }

    // Latimea orizontala este detinuta de LazyColumn (contentPadding); aici
    // adaugam doar spatiere verticala ca sa nu dublam inset-ul si sa iasa
    // sectiunea mai ingusta decat cardurile de dedesubt.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        if (isConnected) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.connection_status_connected),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "$ipSummary:$portSummary",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        TextButton(
                            onClick = onDisconnect,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(stringResource(R.string.disconnect))
                        }
                    }

                    if (deviceStatus != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = connectionMethod?.let { stringResource(it.labelRes) }
                                ?: stringResource(R.string.connection_method_adb),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.storage_label),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = formatStorage(deviceStatus),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.ram_label),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = formatRam(deviceStatus.ramFreeBytes, deviceStatus.ramTotalBytes),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onScanPortals,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(stringResource(R.string.network_portals_title))
                    }
                }
            }
        } else {
            Text(
                text = stringResource(R.string.connect_over_wifi),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = ipAddress,
                    onValueChange = onIpChange,
                    label = { Text(stringResource(R.string.ip_address_label)) },
                    placeholder = { Text(stringResource(R.string.ip_address_placeholder)) },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp),
                    enabled = connectionState !is ConnectionState.Connecting
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { if (it.all { c -> c.isDigit() }) onPortChange(it) },
                    label = { Text(stringResource(R.string.port_label)) },
                    singleLine = true,
                    modifier = Modifier
                        .width(92.dp)
                        .heightIn(min = 56.dp),
                    enabled = connectionState !is ConnectionState.Connecting
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = SectionCardShape,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                )
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    val statusText = when (connectionState) {
                        is ConnectionState.Connected -> stringResource(R.string.connection_status_connected)
                        is ConnectionState.Connecting -> stringResource(R.string.connection_status_connecting)
                        is ConnectionState.Reconnecting ->
                            if (connectionState.attempt > 0) {
                                stringResource(
                                    R.string.conn_status_reconnecting_attempt,
                                    connectionState.attempt
                                )
                            } else {
                                stringResource(R.string.conn_status_reconnecting)
                            }
                        is ConnectionState.Error -> stringResource(R.string.connection_status_failed)
                        ConnectionState.Disconnected -> stringResource(R.string.connection_status_ready)
                    }

                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "IP: ${ipAddress.ifBlank { "-" }} • ${port.ifBlank { "5555" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            when (connectionState) {
                is ConnectionState.Disconnected -> {
                    Button(
                        onClick = onConnect,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = ipAddress.isNotBlank(),
                        shape = RoundedCornerShape(18.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(stringResource(R.string.connect_to_portal))
                    }
                }
                is ConnectionState.Connecting -> {
                    Button(
                        onClick = {},
                        modifier = Modifier.fillMaxWidth(),
                        enabled = false,
                        shape = RoundedCornerShape(18.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.connection_status_connecting))
                    }
                }
                is ConnectionState.Reconnecting -> {
                    Button(
                        onClick = {},
                        modifier = Modifier.fillMaxWidth(),
                        enabled = false,
                        shape = RoundedCornerShape(18.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.conn_status_reconnecting))
                    }
                }
                is ConnectionState.Connected -> Unit
                is ConnectionState.Error -> {
                    Button(
                        onClick = onConnect,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(stringResource(R.string.retry))
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onScanPortals,
                modifier = Modifier.fillMaxWidth(),
                enabled = connectionState !is ConnectionState.Connecting,
                shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(stringResource(R.string.network_portals_title))
            }
        }
    }
}

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
