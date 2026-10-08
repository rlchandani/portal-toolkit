/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.controlcenter

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import com.portalinstaller.data.companion.CompanionPhotos
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
): ImageBitmap? = withContext(Dispatchers.IO) {
    try {
        loadThumbnail(path)?.let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

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
) {
    var page by remember(deviceId, folder) { mutableIntStateOf(0) }
    var previewTarget by remember(deviceId) { mutableStateOf<JSONObject?>(null) }
    var deleteTarget by remember(deviceId) { mutableStateOf<JSONObject?>(null) }
    var chooseFolder by rememberSaveable(deviceId) { mutableStateOf(false) }
    var folderDraft by rememberSaveable(deviceId, folder) { mutableStateOf(folder) }
    val photos = files.objects()
    val pages = ((photos.size + 23) / 24).coerceAtLeast(1)
    val currentPage = page.coerceIn(0, pages - 1)
    SectionTitle("Photos on Portal")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onImport, enabled = !working) { Text("Add photos") }
        OutlinedButton(onClick = onRefresh, enabled = !working) { Text("Refresh") }
    }
    if (!uploadDetail.isNullOrBlank()) {
        Text(uploadDetail, style = MaterialTheme.typography.bodyMedium)
    }
    Text("${photos.size} ${if (photos.size == 1) "photo" else "photos"}", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    TextButton(onClick = { folderDraft = folder; chooseFolder = true }, enabled = !working) {
        Text("Folder: $folder")
    }
    OutlinedButton(onClick = onActivateFolder, enabled = !working && photos.isNotEmpty()) {
        Text("Use for slideshow")
    }
    if (photos.isEmpty()) EmptyMessage("Your collection starts here",
        "Add photos, activate the folder, and preview them on your Portal.")
    photos.drop(currentPage * 24).take(24).chunked(3).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            row.forEach { photo ->
                val path = photo.optString("path")
                key(deviceId, path, photo.opt("modified")) {
                val bitmap by produceState<ImageBitmap?>(null, deviceId, path, photo.opt("modified")) {
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
    if (pages > 1) Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { page = currentPage - 1 }, enabled = currentPage > 0) { Text("Previous") }
        Text("${currentPage + 1} of $pages", modifier = Modifier.weight(1f))
        TextButton(onClick = { page = currentPage + 1 }, enabled = currentPage + 1 < pages) { Text("Next") }
    }
    previewTarget?.let { target ->
        val path = target.optString("path")
        key(deviceId, path, target.opt("modified")) {
            var requestGeneration by remember { mutableIntStateOf(0) }
            // Keep the observed state stable across retries; restart only its producer.
            val preview by produceState<PhotoPreviewState>(PhotoPreviewState.Loading, requestGeneration) {
                value = PhotoPreviewState.Loading
                val bitmap = loadPhotoThumbnail(path, loadThumbnail)
                value = bitmap?.let { PhotoPreviewState.Loaded(it) } ?: PhotoPreviewState.Failed
            }
            AlertDialog(
                onDismissRequest = { previewTarget = null },
                title = { Text(target.optString("name", "Photo")) },
                text = {
                    Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
                        when (val state = preview) {
                            PhotoPreviewState.Loading -> CircularProgressIndicator()
                            is PhotoPreviewState.Loaded -> Image(state.bitmap,
                                contentDescription = target.optString("name"),
                                contentScale = ContentScale.Fit, modifier = Modifier.matchParentSize())
                            PhotoPreviewState.Failed -> Column(
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
            title = { Text(target.optString("name", "Photo")) },
            text = { Text("Delete this photo from the Portal?") },
            confirmButton = { TextButton(onClick = {
                deleteTarget = null; onDelete(target.getString("path"))
            }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Keep photo") } })
    }
    if (chooseFolder) AlertDialog(
        onDismissRequest = { chooseFolder = false },
        title = { Text("Photos on Portal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Choose a public folder on the Portal. Browsing does not change your slideshow.")
                OutlinedTextField(folderDraft, onValueChange = { folderDraft = it },
                    label = { Text("Folder on Portal") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { chooseFolder = false; onChangeFolder(folderDraft.trim()) },
                enabled = !working && runCatching { CompanionPhotos.folder(folderDraft) }.isSuccess) { Text("Browse folder") }
        },
        dismissButton = { TextButton(onClick = { chooseFolder = false }) { Text("Cancel") } })
}
