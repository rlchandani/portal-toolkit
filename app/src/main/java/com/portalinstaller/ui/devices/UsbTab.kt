package com.portalinstaller.ui.devices

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.portalinstaller.R
import com.portalinstaller.ui.common.SectionCard
import com.portalinstaller.data.UsbPortalStatus
import com.portalinstaller.viewmodel.ConnectionState
import com.portalinstaller.viewmodel.WirelessAdbState

@Composable
internal fun WirelessAdbGuideTab(
    connectionState: ConnectionState,
    connectionMethod: String?,
    usbStatus: UsbPortalStatus,
    wirelessAdbState: WirelessAdbState,
    port: String,
    onRefreshUsb: () -> Unit,
    onRequestUsbPermission: () -> Unit,
    onConnectUsb: () -> Unit,
    onEnableWirelessAdb: () -> Unit
) {
    val context = LocalContext.current
    var showDiagnostics by remember { mutableStateOf(false) }
    val isUsbConnected = connectionState is ConnectionState.Connected &&
        connectionMethod == context.getString(R.string.connection_method_usb)
    val isConnecting = connectionState is ConnectionState.Connecting
    val isEnabling = wirelessAdbState is WirelessAdbState.Enabling
    val currentStep = when {
        isUsbConnected || isEnabling -> 4
        !usbStatus.portalDeviceDetected -> 1
        !usbStatus.adbCapableDeviceDetected -> 2
        else -> 3
    }
    val stepTitle = when (currentStep) {
        1 -> stringResource(R.string.usb_step_connect_title)
        2 -> stringResource(R.string.usb_step_enable_adb_title)
        3 -> stringResource(R.string.usb_step_authorize_title)
        else -> stringResource(R.string.usb_step_wireless_title)
    }
    val stepDescription = when (currentStep) {
        1 -> stringResource(R.string.usb_step_connect_description)
        2 -> stringResource(R.string.usb_step_enable_adb_description)
        3 -> stringResource(
            if (usbStatus.hasPermission) {
                R.string.usb_step_connect_app_description
            } else {
                R.string.usb_step_authorize_description
            }
        )
        else -> stringResource(R.string.usb_step_wireless_description)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            SectionCard {
                Text(
                    text = stringResource(R.string.wireless_setup_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.usb_step_counter, currentStep),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stepTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stepDescription,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (currentStep == 1) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.usb_otg_recommendation),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))
                when {
                    currentStep == 3 && !usbStatus.hasPermission -> {
                        Button(
                            onClick = onRequestUsbPermission,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Text(stringResource(R.string.grant_usb_permission))
                        }
                    }
                    currentStep == 3 -> {
                        Button(
                            onClick = onConnectUsb,
                            enabled = !isConnecting,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            if (isConnecting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.usb_connecting))
                            } else {
                                Text(stringResource(R.string.connect_usb))
                            }
                        }
                    }
                    currentStep == 4 -> {
                        Button(
                            onClick = onEnableWirelessAdb,
                            enabled = isUsbConnected && !isEnabling,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            if (isEnabling) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.enabling))
                            } else {
                                Text(stringResource(R.string.enable_wireless_adb))
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.wireless_setup_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = { showDiagnostics = !showDiagnostics },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(
                        stringResource(
                            if (showDiagnostics) {
                                R.string.usb_hide_diagnostics
                            } else {
                                R.string.usb_show_diagnostics
                            }
                        )
                    )
                }
                if (showDiagnostics) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            usbStatus.deviceLabel?.let { label ->
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Text(
                                text = usbStatus.details,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = stringResource(
                                    R.string.wireless_adb_port,
                                    port.ifBlank { "5555" }
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(
                                onClick = onRefreshUsb,
                                enabled = !isConnecting,
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text(stringResource(R.string.refresh_usb))
                            }
                        }
                    }
                }
            }
        }
    }
}
