package com.portalinstaller.ui.tools.files

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.portalinstaller.R
import com.portalinstaller.data.files.RemoteFile
import com.portalinstaller.ui.common.formatFileSize
import com.portalinstaller.viewmodel.FileTransferState
import com.portalinstaller.viewmodel.FileManagerViewModel

/**
 * Navigatorul de fisiere: breadcrumbs, lista cu selectie prin apasare lunga,
 * bara de actiuni (trage/trimite/sterge/muta/redenumeste/folder nou) si un
 * comutator „mod avansat” pentru caile din afara /sdcard.
 */
@Composable
internal fun FilesTab(
    context: Context,
    viewModel: FileManagerViewModel
) {
    val browser by viewModel.browser.collectAsStateWithLifecycle()
    val transfer by viewModel.transferState.collectAsStateWithLifecycle()

    var showNewFolder by remember { mutableStateOf(false) }
    var showMove by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<RemoteFile?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val selectedFiles = browser.entries.filter { it.path in browser.selection }
    val selectedBytes = selectedFiles.sumOf { it.sizeBytes }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Text(
            text = browser.path,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.files_advanced_mode),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = browser.advancedMode,
                onCheckedChange = { viewModel.setAdvancedMode(it) }
            )
        }

        // Bara de actiuni.
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AssistChip(onClick = { viewModel.navigateUp() }, label = { Text(stringResource(R.string.files_up)) })
            AssistChip(onClick = { showNewFolder = true }, label = { Text(stringResource(R.string.files_new_folder)) })
        }
        if (browser.selection.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AssistChip(onClick = { viewModel.pullSelected(context) }, label = { Text(stringResource(R.string.files_pull)) })
                AssistChip(onClick = { showMove = true }, label = { Text(stringResource(R.string.files_move)) })
                AssistChip(onClick = { showDeleteConfirm = true }, label = { Text(stringResource(R.string.files_delete)) })
                if (selectedFiles.size == 1) {
                    AssistChip(onClick = { renameTarget = selectedFiles.first() }, label = { Text(stringResource(R.string.files_rename)) })
                }
            }
        }

        (transfer as? FileTransferState.Working)?.let { w ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.files_transfer_progress, w.current, w.total),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { viewModel.cancelTransfer() }) {
                    Text(stringResource(R.string.cancel))
                }
            }
            LinearProgressIndicator(
                progress = { if (w.progress.isDeterminate) w.progress.fraction else 0f },
                modifier = Modifier.fillMaxWidth()
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (browser.entries.isEmpty() && !browser.isLoading) {
                Text(
                    text = stringResource(R.string.files_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(browser.entries, key = { it.path }) { file ->
                        FileRow(
                            file = file,
                            selected = file.path in browser.selection,
                            onOpen = { if (file.isDirectory) viewModel.open(file.path) else viewModel.toggleSelect(file.path) },
                            onToggleSelect = { viewModel.toggleSelect(file.path) }
                        )
                    }
                }
            }
        }
    }

    if (showNewFolder) {
        TextInputDialog(
            titleRes = R.string.files_new_folder,
            hintRes = R.string.files_new_folder_hint,
            onConfirm = { name -> viewModel.mkdir(context, name); showNewFolder = false },
            onDismiss = { showNewFolder = false }
        )
    }
    if (showMove) {
        TextInputDialog(
            titleRes = R.string.files_move,
            hintRes = R.string.files_move_hint,
            initial = browser.path,
            onConfirm = { dest -> viewModel.moveSelected(context, dest); showMove = false },
            onDismiss = { showMove = false }
        )
    }
    renameTarget?.let { target ->
        TextInputDialog(
            titleRes = R.string.files_rename,
            hintRes = R.string.files_rename_hint,
            initial = target.name,
            onConfirm = { newName -> viewModel.rename(context, target, newName); renameTarget = null },
            onDismiss = { renameTarget = null }
        )
    }
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.files_delete)) },
            text = {
                Text(
                    stringResource(
                        R.string.files_delete_confirm,
                        browser.selection.size,
                        formatFileSize(selectedBytes)
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteSelected(context); showDeleteConfirm = false }) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun FileRow(
    file: RemoteFile,
    selected: Boolean,
    onOpen: () -> Unit,
    onToggleSelect: () -> Unit
) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClick = onOpen)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Glyph text simplu (fara dependenta material-icons).
        Text(
            text = when {
                file.isDirectory -> "📁"
                file.isSymlink -> "🔗"
                else -> "📄"
            }
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = file.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = if (file.isDirectory) file.modifiedAt else "${formatFileSize(file.sizeBytes)} · ${file.modifiedAt}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!file.isDirectory) {
            TextButton(onClick = onToggleSelect) {
                Text(if (selected) "✓" else "○")
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun TextInputDialog(
    titleRes: Int,
    hintRes: Int,
    initial: String = "",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text(stringResource(hintRes)) }
            )
        },
        confirmButton = {
            TextButton(onClick = { if (value.isNotBlank()) onConfirm(value.trim()) }) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
