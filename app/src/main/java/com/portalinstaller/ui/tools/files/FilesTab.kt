package com.portalinstaller.ui.tools.files

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import com.portalinstaller.data.AppContainer
import com.portalinstaller.data.files.RemoteFile
import com.portalinstaller.ui.common.formatFileSize
import com.portalinstaller.ui.tools.shell.ToolTabLayout
import com.portalinstaller.viewmodel.FileTransferState
import com.portalinstaller.viewmodel.FileManagerViewModel

/**
 * Navigatorul de fisiere: breadcrumbs, lista cu selectie prin apasare lunga,
 * bara de actiuni (trage/trimite/sterge/muta/redenumeste/folder nou) si un
 * comutator „mod avansat” pentru caile din afara /sdcard.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FilesTab(
    context: Context,
    viewModel: FileManagerViewModel
) {
    val browser by viewModel.browser.collectAsStateWithLifecycle()
    val transfer by viewModel.transferState.collectAsStateWithLifecycle()
    val results by viewModel.itemResults.collectAsStateWithLifecycle()
    val holder = remember(context) { AppContainer.from(context).connectionHolder }
    var downloadGeneration by remember { mutableStateOf<Long?>(null) }
    var downloadSelection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var destinationError by remember { mutableStateOf<String?>(null) }
    val destinationPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            if (holder.capture()?.generation == downloadGeneration && browser.selection == downloadSelection) {
                runCatching { context.contentResolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                viewModel.pullSelected(context, uri)
            } else destinationError = "The device or selection changed. Choose the files again."
        }
    }
    fun download() {
        destinationError = null
        if (Build.VERSION.SDK_INT >= 29) viewModel.pullSelected(context)
        else {
            downloadGeneration = holder.capture()?.generation
            downloadSelection = browser.selection.toSet()
            destinationPicker.launch(null)
        }
    }

    var showNewFolder by remember { mutableStateOf(false) }
    var showMove by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<RemoteFile?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // Memoizam pe (intrari, selectie) ca sa nu refiltram la fiecare recompozitie.
    val selectedFiles = remember(browser.entries, browser.selection) {
        browser.entries.filter { it.path in browser.selection }
    }
    val selectedBytes = remember(selectedFiles) { selectedFiles.sumOf { it.sizeBytes } }

    // Transfer feedback can add several status/error rows above the file list.
    ToolTabLayout(minimumExpandedHeight = if (results.isNotEmpty()) 560.dp else 400.dp) { filesModifier ->
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
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            AssistChip(onClick = { viewModel.navigateUp() }, label = { Text(stringResource(R.string.files_up)) })
            AssistChip(onClick = { showNewFolder = true }, label = { Text(stringResource(R.string.files_new_folder)) })
        }
        if (browser.selection.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                AssistChip(onClick = { download() }, label = { Text(stringResource(R.string.files_pull)) })
                AssistChip(onClick = { showMove = true }, label = { Text(stringResource(R.string.files_move)) })
                AssistChip(onClick = { showDeleteConfirm = true }, label = { Text(stringResource(R.string.files_delete)) })
                if (selectedFiles.size == 1) {
                    AssistChip(onClick = { renameTarget = selectedFiles.first() }, label = { Text(stringResource(R.string.files_rename)) })
                }
            }
        }
        destinationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (results.isNotEmpty() && transfer !is FileTransferState.Working) {
            Text("Last transfer · ${results.count { it.succeeded }} completed · ${results.count { !it.succeeded }} need attention",
                style = MaterialTheme.typography.labelMedium)
            results.filterNot { it.succeeded }.take(4).forEach { result ->
                Text("${result.path.substringAfterLast('/')}: ${result.message.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            val retry = results.filter { !it.succeeded && it.operation == "pull" }
            val currentSession = holder.capture()
            if (retry.isNotEmpty()) TextButton(onClick = {
                val fresh = holder.capture()
                if (fresh != null && retry.all { it.sessionGeneration == fresh.generation }) {
                    viewModel.clearSelection()
                    retry.filter { result -> browser.entries.any { it.path == result.path } }.forEach { viewModel.toggleSelect(it.path) }
                    if (Build.VERSION.SDK_INT >= 29) viewModel.pullSelected(context)
                    else {
                        downloadGeneration = fresh.generation
                        downloadSelection = retry.map { it.path }.toSet()
                        destinationPicker.launch(null)
                    }
                }
            }, enabled = currentSession != null && retry.all { it.sessionGeneration == currentSession.generation }
                && retry.all { result -> browser.entries.any { it.path == result.path } }) { Text("Retry failed downloads") }
        }

        (transfer as? FileTransferState.Working)?.let { w ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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

        Box(modifier = filesModifier) {
            if (browser.entries.isEmpty() && !browser.isLoading) {
                Text(
                    text = browser.error ?: stringResource(R.string.files_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (browser.error != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
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
                modifier = Modifier.fillMaxWidth(),
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
