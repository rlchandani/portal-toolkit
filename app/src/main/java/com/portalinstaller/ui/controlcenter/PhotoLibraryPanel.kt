/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.controlcenter

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.portalinstaller.data.companion.CompanionPhotos
import com.portalinstaller.data.companion.CompanionPhotoFolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private sealed interface PhotoPreviewState {
    data object Loading : PhotoPreviewState
    data class Loaded(val bitmap: ImageBitmap) : PhotoPreviewState
    data object Failed : PhotoPreviewState
}

private suspend fun loadPhotoThumbnail(
    path: String,
    loadThumbnail: suspend (String) -> ByteArray?,
    maxEdge: Int = PHOTO_GRID_IMAGE_EDGE,
): ImageBitmap? = withContext(Dispatchers.IO) {
    try {
        loadThumbnail(path)?.let { bytes ->
            decodeCompanionDisplayBitmap(bytes, maxEdge)?.asImageBitmap()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PhotoLibraryPanel(
    deviceId: String,
    files: JSONArray,
    folder: String,
    working: Boolean,
    uploadDetail: String?,
    onImport: () -> Unit,
    onRefresh: () -> Unit,
    onChangeFolder: (String) -> Unit,
    onActivateFolder: () -> Unit,
    onDelete: (String) -> Unit,
    loadThumbnail: suspend (String) -> ByteArray?,
    allPhotos: Boolean = false,
    canBrowseAll: Boolean = false,
    folders: List<CompanionPhotoFolder> = emptyList(),
    listingLoaded: Boolean = true,
    truncated: Boolean = false,
    onAllPhotos: () -> Unit = {},
    listingError: String? = null,
    showImport: Boolean = true,
) {
    var page by remember(deviceId, folder, allPhotos) { mutableIntStateOf(0) }
    var previewTarget by remember(deviceId) { mutableStateOf<JSONObject?>(null) }
    var deleteTarget by remember(deviceId) { mutableStateOf<JSONObject?>(null) }
    var chooseFolder by rememberSaveable(deviceId) { mutableStateOf(false) }
    var folderDraft by rememberSaveable(deviceId, folder) { mutableStateOf(folder) }
    var enterPath by rememberSaveable(deviceId) { mutableStateOf(false) }
    val photos = files.objects()
    val pages = ((photos.size + 23) / 24).coerceAtLeast(1)
    val currentPage = page.coerceIn(0, pages - 1)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionTitle("Photos on Portal")
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (showImport) Button(onClick = onImport, enabled = !working) { Text("Add photos") }
            OutlinedButton(onClick = onRefresh, enabled = !working) { Text("Refresh") }
        }
        if (!uploadDetail.isNullOrBlank()) {
            Text(uploadDetail, style = MaterialTheme.typography.bodyMedium)
        }
        if (listingLoaded) Text("${photos.size} ${if (photos.size == 1) "photo" else "photos"}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { folderDraft = folder; enterPath = false; chooseFolder = true },
            enabled = !working, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (allPhotos) "All photos on Portal" else "Folder: $folder",
                    modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                ControlGlyph("chevron", modifier = Modifier.size(20.dp))
            }
        }
        if (truncated) Text("This is a partial collection. Choose a folder to browse it directly.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onActivateFolder,
            enabled = !working && listingLoaded && listingError == null && photos.isNotEmpty() && !truncated) {
            Text(if (allPhotos) "Use all photos for slideshow" else "Use for slideshow")
        }
        if (listingError != null) Text(listingError, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error)
        if (!listingLoaded && listingError == null) Row(horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            Text("Finding photos…")
        } else if (listingError == null && photos.isEmpty()) EmptyMessage(if (truncated) "Collection scan is incomplete" else "No photos found",
            if (allPhotos) "Add photos or choose a folder to browse."
            else "Add photos here or choose another folder.")
        photos.drop(currentPage * 24).take(24).chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { photo ->
                    val path = photo.optString("path")
                    key(deviceId, path, photo.opt("modified"), photo.opt("size")) {
                    val bitmap by produceState<ImageBitmap?>(null, deviceId, path, photo.opt("modified"), photo.opt("size")) {
                        value = loadPhotoThumbnail(path, loadThumbnail)
                    }
                    Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(enabled = !working) { previewTarget = photo },
                        contentAlignment = Alignment.Center) {
                        if (bitmap != null) Image(bitmap!!, contentDescription = photo.optString("name"),
                            modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                        else ControlGlyph("photos", modifier = Modifier.size(30.dp))
                    }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (pages > 1) FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = { page = currentPage - 1 }, enabled = currentPage > 0) { Text("Previous") }
            Text("${currentPage + 1} of $pages", modifier = Modifier.align(Alignment.CenterVertically))
            TextButton(onClick = { page = currentPage + 1 }, enabled = currentPage + 1 < pages) { Text("Next") }
        }
    }
    previewTarget?.let { target ->
        val path = target.optString("path")
        key(deviceId, path, target.opt("modified"), target.opt("size")) {
            var requestGeneration by remember { mutableIntStateOf(0) }
            // Keep the observed state stable across retries; restart only its producer.
            val preview by produceState<PhotoPreviewState>(PhotoPreviewState.Loading, requestGeneration) {
                value = PhotoPreviewState.Loading
                val bitmap = loadPhotoThumbnail(path, loadThumbnail, PHOTO_PREVIEW_IMAGE_EDGE)
                value = bitmap?.let { PhotoPreviewState.Loaded(it) } ?: PhotoPreviewState.Failed
            }
            // Observe in the owning composition before crossing the dialog's composition boundary.
            val previewState = preview
            AlertDialog(
                onDismissRequest = { previewTarget = null },
                title = { Text(target.optString("name", "Photo"),
                    maxLines = 2, overflow = TextOverflow.Ellipsis) },
                text = {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                        when (val state = previewState) {
                            PhotoPreviewState.Loading -> Box(Modifier.fillMaxWidth().height(260.dp),
                                contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                            is PhotoPreviewState.Loaded -> Image(state.bitmap,
                                contentDescription = target.optString("name"),
                                contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().height(260.dp))
                            PhotoPreviewState.Failed -> Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text("Couldn't load this photo preview. Try again.")
                                OutlinedButton(onClick = { requestGeneration++ }) { Text("Retry") }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { previewTarget = null }) { Text("Done") } },
                dismissButton = { TextButton(onClick = {
                    previewTarget = null; deleteTarget = target
                }, enabled = !working) { Text("Delete photo") } })
        }
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(target.optString("name", "Photo"),
                maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = { Text("Delete this photo from the Portal?",
                modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = {
                deleteTarget = null; onDelete(target.getString("path"))
            }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Keep photo") } })
    }
    if (chooseFolder) AlertDialog(
        onDismissRequest = { chooseFolder = false },
        modifier = Modifier.imePadding(),
        title = { Text("Photos on Portal") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Choose a collection. Browsing does not change your slideshow.")
                if (canBrowseAll) PhotoFolderChoice("All photos", "Every discovered photo folder",
                    allPhotos, onClick = { chooseFolder = false; onAllPhotos() })
                folders.forEach { discovered ->
                    PhotoFolderChoice(
                        if (discovered.path == "/sdcard") "Shared storage" else discovered.path.substringAfterLast('/'),
                        "${discovered.count} photos · ${discovered.path}",
                        !allPhotos && folder == discovered.path,
                        onClick = { chooseFolder = false; onChangeFolder(discovered.path) })
                }
                if (!enterPath && (canBrowseAll || folders.isNotEmpty())) TextButton(onClick = { enterPath = true }) {
                    Text("Enter a folder path")
                }
                if (enterPath || !canBrowseAll && folders.isEmpty()) OutlinedTextField(
                    folderDraft, onValueChange = { folderDraft = it },
                    label = { Text("Folder on Portal") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            if (enterPath || !canBrowseAll && folders.isEmpty()) TextButton(
                onClick = { chooseFolder = false; onChangeFolder(folderDraft.trim()) },
                enabled = !working && runCatching { CompanionPhotos.folder(folderDraft) }.isSuccess) { Text("Browse folder") }
        },
        dismissButton = { TextButton(onClick = { chooseFolder = false }) { Text("Cancel") } })
}

@Composable
private fun PhotoFolderChoice(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
