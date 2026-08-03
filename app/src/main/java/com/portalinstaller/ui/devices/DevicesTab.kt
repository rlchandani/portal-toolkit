package com.portalinstaller.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.portalinstaller.R
import com.portalinstaller.data.DeviceStatus
import com.portalinstaller.data.store.SavedPortal
import com.portalinstaller.data.UsbPortalStatus
import com.portalinstaller.viewmodel.BackupState
import com.portalinstaller.viewmodel.ConnectionMethod
import com.portalinstaller.viewmodel.ConnectionState
import com.portalinstaller.viewmodel.OsUpdateState
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
