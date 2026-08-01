package com.portalinstaller.ui.screensaver

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.portalinstaller.R
import com.portalinstaller.data.files.RemoteFile
import com.portalinstaller.ui.common.formatFileSize
import com.portalinstaller.viewmodel.ScreensaverState
import com.portalinstaller.viewmodel.ScreensaverViewModel
import com.portalinstaller.viewmodel.UploadSummaryState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Managerul de screensaver: alege folderul de imagini de pe Portal, afiseaza
 * un contor de stocare, o grila de miniaturi cu selectie multipla si o bara de
 * actiuni (adauga/sterge/muta/salveaza). Inlocuieste vechiul tab „Poze”.
 */
@Composable
internal fun ScreensaverTab(
    state: ScreensaverState,
    uploadState: UploadSummaryState,
    isWifiConnected: Boolean,
    viewModel: ScreensaverViewModel,
    onOpenDevices: () -> Unit,
    onPickPhotos: () -> Unit
) {
    val context = LocalContext.current
    var showFolderPicker by remember { mutableStateOf(false) }
    var showMove by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        if (!isWifiConnected) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.photos_connect_hint),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onOpenDevices) {
                        Text(stringResource(R.string.open_devices))
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Antet: folderul curent + buton de schimbare.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.screensaver_folder, state.folder ?: "—"),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { showFolderPicker = true }, enabled = isWifiConnected) {
                Text(stringResource(R.string.screensaver_change_folder))
            }
        }

        // Contor de stocare.
        Text(
            text = stringResource(
                R.string.screensaver_storage,
                formatFileSize(state.folderBytes),
                formatFileSize(state.deviceFreeBytes)
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val usedFraction = run {
            val total = state.folderBytes + state.deviceFreeBytes
            if (total > 0L) (state.folderBytes.toFloat() / total).coerceIn(0f, 1f) else 0f
        }
        LinearProgressIndicator(
            progress = { usedFraction },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        )

        // Bara de actiuni.
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AssistChip(
                onClick = onPickPhotos,
                enabled = isWifiConnected,
                label = { Text(stringResource(R.string.screensaver_add_photos)) }
            )
            if (state.selection.isNotEmpty()) {
                AssistChip(onClick = { viewModel.pullSelected(context) }, label = { Text(stringResource(R.string.screensaver_pull)) })
                AssistChip(onClick = { showMove = true }, label = { Text(stringResource(R.string.screensaver_move)) })
                AssistChip(onClick = { viewModel.deleteSelected(context) }, label = { Text(stringResource(R.string.screensaver_delete)) })
            }
        }

        (uploadState as? UploadSummaryState.Uploading)?.let { u ->
            Text(
                text = stringResource(R.string.uploading_progress, u.current, u.total),
                style = MaterialTheme.typography.labelSmall
            )
            LinearProgressIndicator(
                progress = { if (u.progress.isDeterminate) u.progress.fraction else 0f },
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (state.contents.isEmpty() && !state.isLoading) {
                Text(
                    text = stringResource(R.string.screensaver_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    gridItems(state.contents, key = { it.path }) { file ->
                        RemoteThumbnail(
                            file = file,
                            selected = file.path in state.selection,
                            viewModel = viewModel,
                            onClick = { viewModel.toggleSelect(file.path) }
                        )
                    }
                }
            }
        }
    }

    if (showFolderPicker) {
        AlertDialog(
            onDismissRequest = { showFolderPicker = false },
            title = { Text(stringResource(R.string.screensaver_choose_folder)) },
            text = {
                Column {
                    state.suggestions.forEach { folder ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.chooseFolder(context, folder.path)
                                    showFolderPicker = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = folder.path, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = stringResource(R.string.screensaver_folder_image_count, folder.imageCount),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFolderPicker = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showMove) {
        var dest by remember { mutableStateOf(state.folder ?: "") }
        AlertDialog(
            onDismissRequest = { showMove = false },
            title = { Text(stringResource(R.string.screensaver_move)) },
            text = {
                OutlinedTextField(
                    value = dest,
                    onValueChange = { dest = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.screensaver_move_hint)) }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (dest.isNotBlank()) viewModel.moveSelected(context, dest.trim())
                    showMove = false
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showMove = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun RemoteThumbnail(
    file: RemoteFile,
    selected: Boolean,
    viewModel: ScreensaverViewModel,
    onClick: () -> Unit
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, file.path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val cached = viewModel.thumbnailFile(file) ?: return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(cached.absolutePath, bounds)
                val sample = calculateInSampleSize(bounds, 256, 256)
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeFile(cached.absolutePath, opts)?.asImageBitmap()
            }.getOrNull()
        }
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
    ) {
        val current = bitmap
        if (current != null) {
            Image(
                bitmap = current,
                contentDescription = file.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        if (selected) {
            Text(
                text = "✓",
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
            )
        }
    }
}

/**
 * Factor de subesantionare pentru a decoda o miniatura la rezolutie redusa,
 * evitand incarcarea integrala in memorie a unei imagini mari.
 */
private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    val height = options.outHeight
    val width = options.outWidth
    var inSampleSize = 1
    if (height > reqHeight || width > reqWidth) {
        val halfHeight = height / 2
        val halfWidth = width / 2
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}
