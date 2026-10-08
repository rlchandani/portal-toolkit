package com.portalinstaller.ui.devices

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.portalinstaller.R
import com.portalinstaller.ui.common.SectionCard
import com.portalinstaller.viewmodel.BackupState
import java.text.DateFormat
import java.util.Date

@Composable
internal fun BackupCard(
    isConnected: Boolean,
    backupState: BackupState,
    availablePackageCount: Int,
    isLoadingPackages: Boolean,
    selectedPackages: Set<String>?,
    onChooseApps: () -> Unit,
    onCreateBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
    onCancel: () -> Unit
) {
    val isWorking = backupState is BackupState.Creating ||
        backupState is BackupState.Restoring ||
        backupState is BackupState.Previewing

    SectionCard {
        Text(
            text = stringResource(R.string.backup_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.backup_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.backup_apps_label),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = when {
                        isLoadingPackages -> stringResource(R.string.loading)
                        selectedPackages == null && availablePackageCount > 0 -> stringResource(
                            R.string.backup_apps_all,
                            availablePackageCount
                        )
                        selectedPackages == null -> stringResource(R.string.backup_apps_all_simple)
                        else -> stringResource(
                            R.string.backup_apps_selected,
                            selectedPackages.size
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(
                onClick = onChooseApps,
                enabled = isConnected && !isWorking,
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(stringResource(R.string.choose))
            }
        }
        Spacer(modifier = Modifier.height(10.dp))

        if (isWorking) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            val progressText = when (backupState) {
                is BackupState.Creating -> stringResource(
                    R.string.backup_creating_progress,
                    backupState.current,
                    backupState.total,
                    backupState.packageName
                )
                is BackupState.Restoring -> stringResource(
                    R.string.backup_restoring_progress,
                    backupState.current,
                    backupState.total,
                    backupState.packageName
                )
                is BackupState.Previewing -> stringResource(R.string.backup_preview_progress)
                else -> ""
            }
            Text(
                text = progressText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            // Anularea are sens doar pentru operatiuni lungi (creare/restaurare),
            // nu pentru previzualizarea rapida a unei arhive.
            if (backupState is BackupState.Creating || backupState is BackupState.Restoring) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        Button(
            onClick = onCreateBackup,
            enabled = isConnected && !isWorking,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.backup_create))
        }
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedButton(
            onClick = onRestoreBackup,
            enabled = isConnected && !isWorking,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.backup_restore))
        }
        if (!isConnected) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.backup_connect_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.backup_limitations),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun BackupAppSelectionDialog(
    packages: List<String>,
    isLoading: Boolean,
    selectedPackages: Set<String>?,
    onUseAll: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var selection by remember(packages, selectedPackages) {
        mutableStateOf(
            selectedPackages
                ?.intersect(packages.toSet())
                ?: packages.toSet()
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_choose_apps_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.backup_choose_apps_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = { selection = packages.toSet() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.select_all))
                    }
                    TextButton(
                        onClick = { selection = emptySet() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.select_none))
                    }
                }
                when {
                    isLoading -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    packages.isEmpty() -> {
                        Text(
                            text = stringResource(R.string.portal_apps_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            items(packages, key = { it }) { packageName ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = packageName in selection,
                                        onCheckedChange = { checked ->
                                            selection = if (checked) {
                                                selection + packageName
                                            } else {
                                                selection - packageName
                                            }
                                        }
                                    )
                                    Text(
                                        text = packageName,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        R.string.backup_apps_selected,
                        selection.size
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (selection.size == packages.size) {
                        onUseAll()
                    } else {
                        onConfirm(selection)
                    }
                },
                enabled = !isLoading
            ) {
                Text(stringResource(R.string.save_selection))
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
internal fun BackupRestoreConfirmationDialog(
    ready: BackupState.ReadyToRestore,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val preview = ready.preview
    val deviceName = listOf(preview.manufacturer, preview.model)
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .ifBlank { stringResource(R.string.backup_preview_unknown_device) }
    val created = if (preview.createdAt > 0) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(preview.createdAt))
    } else {
        "-"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_preview_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.backup_preview_source, deviceName),
                    fontWeight = FontWeight.SemiBold
                )
                if (preview.sdk.isNotBlank()) {
                    Text(stringResource(R.string.backup_preview_sdk, preview.sdk))
                }
                Text(stringResource(R.string.backup_preview_date, created))
                Text(
                    stringResource(
                        R.string.backup_preview_contents,
                        preview.appCount,
                        preview.settingCount
                    )
                )
                Text(
                    text = stringResource(R.string.backup_preview_warning),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.backup_restore_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}
