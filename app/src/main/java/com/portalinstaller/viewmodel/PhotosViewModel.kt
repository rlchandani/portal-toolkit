package com.portalinstaller.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.portalinstaller.R
import com.portalinstaller.data.AppContainer
import com.portalinstaller.data.ContentUriHelper
import com.portalinstaller.data.store.HistoryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed class PhotoUploadState {
    data object Idle : PhotoUploadState()
    data class Uploading(val current: Int, val total: Int) : PhotoUploadState()
    data class Success(val count: Int) : PhotoUploadState()
    data class Failed(val message: String) : PhotoUploadState()
}

/**
 * Încarcă fotografii pe Portal (folderul de screensaver). Push-ul efectiv este
 * delegat lui PortalConnectionManager; conexiunea vine din holder-ul partajat.
 */
class PhotosViewModel(app: Application) : AndroidViewModel(app) {

    private val container = AppContainer.from(app)
    private val connectionManager = container.connectionManager
    private val holder = container.connectionHolder
    private val historyStore = container.historyStore

    private val _photoUploadState = MutableStateFlow<PhotoUploadState>(PhotoUploadState.Idle)
    val photoUploadState: StateFlow<PhotoUploadState> = _photoUploadState.asStateFlow()

    private val _selectedPhotoUris = MutableStateFlow<List<Uri>>(emptyList())
    val selectedPhotoUris: StateFlow<List<Uri>> = _selectedPhotoUris.asStateFlow()

    fun setSelectedPhotoUris(uris: List<Uri>) {
        _selectedPhotoUris.value = uris
        if (uris.isNotEmpty()) {
            _photoUploadState.value = PhotoUploadState.Idle
        }
    }

    fun uploadPhotos(context: Context) {
        val d = holder.dadb.value ?: return
        val uris = _selectedPhotoUris.value
        if (uris.isEmpty()) return

        viewModelScope.launch {
            var successCount = 0
            var lastError: String? = null

            uris.forEachIndexed { index, uri ->
                _photoUploadState.value = PhotoUploadState.Uploading(current = index + 1, total = uris.size)

                val photoFile = withContext(Dispatchers.IO) {
                    val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext null
                    val fileName = ContentUriHelper.getDisplayName(context, uri)
                        ?: "photo_${System.nanoTime()}.jpg"
                    val tempFile = File(context.cacheDir, fileName)
                    tempFile.outputStream().use { output -> inputStream.copyTo(output) }
                    inputStream.close()
                    tempFile
                }

                if (photoFile == null) {
                    lastError = context.getString(R.string.error_read_photo_failed)
                    return@forEachIndexed
                }

                connectionManager.pushPhoto(context, d, photoFile)
                    .onSuccess { successCount++ }
                    .onFailure { e -> lastError = e.message ?: context.getString(R.string.error_unknown) }

                photoFile.delete()
            }

            _photoUploadState.value = if (lastError == null) {
                PhotoUploadState.Success(successCount)
            } else {
                PhotoUploadState.Failed(
                    context.getString(
                        R.string.error_photo_upload_partial,
                        successCount,
                        uris.size,
                        lastError
                    )
                )
            }

            historyStore.add(
                HistoryEntry(
                    timestamp = System.currentTimeMillis(),
                    description = context.getString(R.string.history_photo_upload, successCount, uris.size),
                    success = lastError == null
                )
            )

            _selectedPhotoUris.value = emptyList()
        }
    }

    fun clearPhotoUploadState() {
        _photoUploadState.value = PhotoUploadState.Idle
    }

    fun onDisconnected() {
        _photoUploadState.value = PhotoUploadState.Idle
    }
}
