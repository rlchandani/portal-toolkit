package com.portalinstaller.ui.devices

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.RoundedCornerShape
import com.portalinstaller.R
import com.portalinstaller.data.DeviceStatus
import com.portalinstaller.ui.common.SectionCard
import com.portalinstaller.ui.common.formatFileSize
import com.portalinstaller.ui.common.formatUptime

/**
 * Panoul de stare al Portal-ului: gauge-uri tip donut pentru stocare și RAM,
 * uptime, SDK și un grid de comenzi de alimentare (repornire/recovery/ecran/
 * lansare aplicație). Repornirile cer confirmare, fiindcă deconectează Portal-ul.
 */
@Composable
internal fun DashboardCard(
    deviceStatus: DeviceStatus?,
    portalPackages: List<String>,
    onRefresh: () -> Unit,
    onReboot: () -> Unit,
    onRebootRecovery: () -> Unit,
    onScreenOn: () -> Unit,
    onScreenOff: () -> Unit,
    onLaunchApp: (String) -> Unit
) {
    var confirmReboot by remember { mutableStateOf(false) }
    var confirmRecovery by remember { mutableStateOf(false) }
    var showLaunchPicker by remember { mutableStateOf(false) }

    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.dashboard_title),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
            TextButton(onClick = onRefresh) {
                Text(stringResource(R.string.dashboard_refresh))
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val storageFree = deviceStatus?.storageFreeBytes
            val storageTotal = deviceStatus?.storageTotalBytes
            val storageUsedFraction = if (storageFree != null && storageTotal != null && storageTotal > 0) {
                ((storageTotal - storageFree).toFloat() / storageTotal).coerceIn(0f, 1f)
            } else {
                0f
            }
            DonutGauge(
                fraction = storageUsedFraction,
                label = stringResource(R.string.dashboard_storage),
                center = if (storageFree != null && storageTotal != null) {
                    "${(storageUsedFraction * 100).toInt()}%"
                } else {
                    stringResource(R.string.unavailable)
                },
                subtitle = if (storageFree != null && storageTotal != null) {
                    "${formatFileSize(storageFree)} / ${formatFileSize(storageTotal)}"
                } else {
                    ""
                },
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )

            val ramFree = deviceStatus?.ramFreeBytes
            val ramTotal = deviceStatus?.ramTotalBytes
            // Ca la stocare: inelul arata RAM folosit / total, nu un inel plin fals.
            val ramUsedFraction = if (ramFree != null && ramTotal != null && ramTotal > 0) {
                ((ramTotal - ramFree).toFloat() / ramTotal).coerceIn(0f, 1f)
            } else {
                0f
            }
            DonutGauge(
                fraction = ramUsedFraction,
                label = stringResource(R.string.dashboard_ram),
                center = if (ramFree != null && ramTotal != null) {
                    "${(ramUsedFraction * 100).toInt()}%"
                } else {
                    stringResource(R.string.unavailable)
                },
                subtitle = if (ramFree != null && ramTotal != null) {
                    "${formatFileSize(ramTotal - ramFree)} / ${formatFileSize(ramTotal)}"
                } else {
                    ""
                },
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        InfoRow(
            stringResource(R.string.dashboard_uptime),
            deviceStatus?.uptimeSeconds?.let { formatUptime(it) } ?: stringResource(R.string.unavailable)
        )
        InfoRow(
            stringResource(R.string.dashboard_sdk),
            deviceStatus?.sdk?.takeIf { it.isNotBlank() } ?: stringResource(R.string.unavailable)
        )

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.power_controls_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = { confirmReboot = true }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.power_reboot))
            }
            OutlinedButton(onClick = { confirmRecovery = true }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.power_reboot_recovery))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = onScreenOn, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.power_screen_on))
            }
            OutlinedButton(onClick = onScreenOff, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.power_screen_off))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = { showLaunchPicker = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = portalPackages.isNotEmpty()
        ) {
            Text(stringResource(R.string.power_launch_app))
        }
    }

    if (confirmReboot) {
        PowerConfirmDialog(
            title = stringResource(R.string.power_reboot_confirm_title),
            message = stringResource(R.string.power_reboot_confirm_message),
            onConfirm = {
                confirmReboot = false
                onReboot()
            },
            onDismiss = { confirmReboot = false }
        )
    }

    if (confirmRecovery) {
        PowerConfirmDialog(
            title = stringResource(R.string.power_recovery_confirm_title),
            message = stringResource(R.string.power_recovery_confirm_message),
            onConfirm = {
                confirmRecovery = false
                onRebootRecovery()
            },
            onDismiss = { confirmRecovery = false }
        )
    }

    if (showLaunchPicker) {
        LaunchAppPickerDialog(
            packages = portalPackages,
            onPick = { pkg ->
                showLaunchPicker = false
                onLaunchApp(pkg)
            },
            onDismiss = { showLaunchPicker = false }
        )
    }
}

@Composable
private fun DonutGauge(
    fraction: Float,
    label: String,
    center: String,
    subtitle: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(96.dp)) {
                val stroke = Stroke(width = 14f)
                val inset = stroke.width / 2
                val arcSize = Size(size.width - stroke.width, size.height - stroke.width)
                val topLeft = Offset(inset, inset)
                drawArc(
                    color = trackColor,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = stroke
                )
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = 360f * fraction.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = stroke
                )
            }
            Text(
                text = center,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(text = label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun PowerConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun LaunchAppPickerDialog(
    packages: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.power_launch_pick_title)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                items(packages) { pkg ->
                    Text(
                        text = pkg,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onPick(pkg) }
                            .padding(vertical = 10.dp, horizontal = 4.dp)
                    )
                    HorizontalDivider()
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
