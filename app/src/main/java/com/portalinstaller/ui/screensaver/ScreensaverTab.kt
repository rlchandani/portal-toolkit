package com.portalinstaller.ui.screensaver

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showActionsMenu by remember { mutableStateOf(false) }
    // Marimea miniaturilor: cicleaza Mic (5) / Mediu (3) / Mare (2) coloane.
    var gridSize by remember { mutableStateOf(GridSize.MEDIUM) }
    // Poza deschisa la marime mare (previzualizare); null cand e inchisa.
    var previewFile by remember { mutableStateOf<RemoteFile?>(null) }

    // Modul de selectie e derivat: activ cat timp exista macar o poza selectata.
    val selectionMode = state.selection.isNotEmpty()
    val selectedFiles = state.contents.filter { it.path in state.selection }
    val selectedBytes = selectedFiles.sumOf { it.sizeBytes }

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
            AssistChip(
                onClick = { gridSize = gridSize.next() },
                label = {
                    Text(
                        stringResource(R.string.screensaver_grid_size_label, stringResource(gridSize.labelRes)),
                        maxLines = 1,
                        softWrap = false
                    )
                }
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

        // Bara de actiuni: in mod normal doar „Adauga poze”; in mod selectie
        // aratam numarul, „Deselecteaza tot” si un meniu ⋮ cu actiunile, ca
        // butoanele sa nu se reverse in afara ecranului.
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionMode) {
                Text(
                    text = stringResource(R.string.screensaver_selected_count, state.selection.size),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { viewModel.clearSelection() }) {
                    Text(stringResource(R.string.screensaver_deselect_all))
                }
                Box {
                    IconButton(onClick = { showActionsMenu = true }) {
                        Text(text = "⋮", style = MaterialTheme.typography.titleLarge)
                    }
                    DropdownMenu(
                        expanded = showActionsMenu,
                        onDismissRequest = { showActionsMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.screensaver_pull)) },
                            onClick = { showActionsMenu = false; viewModel.pullSelected(context) }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.screensaver_move)) },
                            onClick = { showActionsMenu = false; showMove = true }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.screensaver_delete)) },
                            onClick = { showActionsMenu = false; showDeleteConfirm = true }
                        )
                    }
                }
            } else {
                AssistChip(
                    onClick = onPickPhotos,
                    enabled = isWifiConnected,
                    label = { Text(stringResource(R.string.screensaver_add_photos), maxLines = 1, softWrap = false) }
                )
            }
        }

        (uploadState as? UploadSummaryState.Uploading)?.let { u ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.uploading_progress, u.current, u.total),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { viewModel.cancelUpload() }) {
                    Text(stringResource(R.string.cancel))
                }
            }
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
                    columns = GridCells.Fixed(gridSize.columns),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    gridItems(state.contents, key = { it.path }) { file ->
                        RemoteThumbnail(
                            file = file,
                            selected = file.path in state.selection,
                            selectionMode = selectionMode,
                            viewModel = viewModel,
                            // In mod selectie, apasarea comuta selectia; altfel
                            // deschide previzualizarea. Apasarea lunga porneste
                            // selectia (comportament standard de galerie).
                            onClick = {
                                if (selectionMode) viewModel.toggleSelect(file.path)
                                else previewFile = file
                            },
                            onLongClick = { viewModel.toggleSelect(file.path) }
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

    previewFile?.let { file ->
        val startIndex = state.contents.indexOfFirst { it.path == file.path }.coerceAtLeast(0)
        PhotoPreviewDialog(
            photos = state.contents,
            initialIndex = startIndex,
            selection = state.selection,
            viewModel = viewModel,
            onToggleSelect = { path -> viewModel.toggleSelect(path) },
            onDismiss = { previewFile = null }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.screensaver_delete)) },
            text = {
                Text(
                    stringResource(
                        R.string.screensaver_delete_confirm,
                        state.selection.size,
                        formatFileSize(selectedBytes)
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    viewModel.deleteSelected(context)
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.cancel)) }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RemoteThumbnail(
    file: RemoteFile,
    selected: Boolean,
    selectionMode: Boolean,
    viewModel: ScreensaverViewModel,
    onClick: () -> Unit,
    onLongClick: () -> Unit
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
            // Apasare = onClick; apasare lunga = porneste/comuta selectia.
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
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
        // Bifa din colt doar in mod selectie. Cerc real: Box patrat (size)
        // cu colturi complet rotunjite, fara padding asimetric (care il facea
        // sa arate ca un oval vertical).
        if (selectionMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else Color.Black.copy(alpha = 0.35f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (selected) Text(text = "✓", color = Color.White)
            }
        }
    }
}

/** Marimile disponibile pentru grila de miniaturi (numar de coloane). */
private enum class GridSize(val columns: Int, val labelRes: Int) {
    SMALL(5, R.string.screensaver_grid_size_small),
    MEDIUM(3, R.string.screensaver_grid_size_medium),
    LARGE(2, R.string.screensaver_grid_size_large);

    fun next(): GridSize = entries[(ordinal + 1) % entries.size]
}

/**
 * Previzualizare la marime mare intr-un dialog pe tot ecranul. Un
 * [HorizontalPager] permite tragerea stanga/dreapta pentru poza urmatoare/
 * anterioara. Fiecare pagina isi decodeaza propria imagine din cache (deja
 * trasa integral de [ScreensaverViewModel]) la o rezolutie mai mare decat
 * miniatura, ca utilizatorul sa vada detaliile.
 */
@Composable
private fun PhotoPreviewDialog(
    photos: List<RemoteFile>,
    initialIndex: Int,
    selection: Set<String>,
    viewModel: ScreensaverViewModel,
    onToggleSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (photos.isEmpty()) return
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, photos.lastIndex),
        pageCount = { photos.size }
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                // Apasarea pe poza (nu tragerea) inchide previzualizarea.
                Box(
                    modifier = Modifier.fillMaxSize().clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    PhotoPreviewPage(file = photos[page], viewModel = viewModel)
                }
            }

            val currentFile = photos[pagerState.currentPage]
            // Indicator „3 / 12” + numele fisierului curent.
            Text(
                text = "${pagerState.currentPage + 1} / ${photos.size}  ·  ${currentFile.name}",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
            )

            val currentSelected = currentFile.path in selection
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = { onToggleSelect(currentFile.path) },
                    label = {
                        Text(
                            stringResource(
                                if (currentSelected) R.string.screensaver_preview_deselect
                                else R.string.screensaver_preview_select
                            ),
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                )
                AssistChip(
                    onClick = onDismiss,
                    label = { Text(stringResource(R.string.screensaver_preview_close), maxLines = 1, softWrap = false) }
                )
            }
        }
    }
}

/** O singura pagina din previzualizare: decodeaza si afiseaza o poza mare. */
@Composable
private fun PhotoPreviewPage(
    file: RemoteFile,
    viewModel: ScreensaverViewModel
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, file.path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val cached = viewModel.thumbnailFile(file) ?: return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(cached.absolutePath, bounds)
                // Tinta 1600px: mult mai clara decat miniatura de 256px, dar tot
                // subesantionata ca sa nu incarce o imagine uriasa in memorie.
                val sample = calculateInSampleSize(bounds, 1600, 1600)
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeFile(cached.absolutePath, opts)?.asImageBitmap()
            }.getOrNull()
        }
    }

    val current = bitmap
    if (current != null) {
        Image(
            bitmap = current,
            contentDescription = file.name,
            modifier = Modifier.fillMaxSize().padding(16.dp),
            contentScale = ContentScale.Fit
        )
    } else {
        CircularProgressIndicator()
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
