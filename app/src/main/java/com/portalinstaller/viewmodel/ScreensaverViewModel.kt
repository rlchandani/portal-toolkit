package com.portalinstaller.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.portalinstaller.R
import com.portalinstaller.data.AdbErrors
import com.portalinstaller.data.AppContainer
import com.portalinstaller.data.ContentUriHelper
import com.portalinstaller.data.PortalConnectionManager
import com.portalinstaller.data.files.RemoteFile
import com.portalinstaller.data.screensaver.DedupeKey
import com.portalinstaller.data.screensaver.ImageFolder
import com.portalinstaller.data.store.HistoryEntry
import com.portalinstaller.data.transfer.TransferProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Alege planul de incarcare: fisierele noi (dupa nume+dimensiune) plus
 * duplicatele explicit fortate prin [overrides]. Functie pura, testabila.
 */
internal fun planUpload(
    picked: List<Pair<String, Long>>,
    existing: Set<DedupeKey>,
    overrides: Set<String>
): List<Pair<String, Long>> =
    picked.filter { (name, size) ->
        DedupeKey(name, size) !in existing || name in overrides
    }

/** Starea managerului de screensaver: folderul ales, sugestii si continut. */
data class ScreensaverState(
    val folder: String? = null,
    val suggestions: List<ImageFolder> = emptyList(),
    val contents: List<RemoteFile> = emptyList(),
    val selection: Set<String> = emptySet(),
    val folderBytes: Long = 0L,
    val deviceFreeBytes: Long = 0L,
    val isLoading: Boolean = false
)

/** Rezumatul unei sesiuni de incarcare pe Portal. */
sealed class UploadSummaryState {
    data object Idle : UploadSummaryState()
    data class Uploading(val current: Int, val total: Int, val progress: TransferProgress) : UploadSummaryState()
    data class Done(val uploaded: Int, val skipped: Int, val failed: Int) : UploadSummaryState()
    data class Failed(val message: String) : UploadSummaryState()
}

/**
 * Gestioneaza folderul de screensaver al Portalului: descopera folderele cu
 * imagini, masoara stocarea, incarca poze cu deduplicare si opereaza pe
 * continut (sterge/muta/trage). Inlocuieste vechiul PhotosViewModel.
 */
class ScreensaverViewModel(app: Application) : AndroidViewModel(app) {

    private val container = AppContainer.from(app)
    private val holder = container.connectionHolder
    private val screensaverManager = container.screensaverManager
    private val fileManager = container.fileManager
    private val historyStore = container.historyStore

    private val _state = MutableStateFlow(ScreensaverState())
    val state: StateFlow<ScreensaverState> = _state.asStateFlow()

    private val _uploadState = MutableStateFlow<UploadSummaryState>(UploadSummaryState.Idle)
    val uploadState: StateFlow<UploadSummaryState> = _uploadState.asStateFlow()

    private val _notConnected = MutableStateFlow(false)
    val notConnected: StateFlow<Boolean> = _notConnected.asStateFlow()

    private var uploadJob: Job? = null

    private fun defaultFolder(): String = PortalConnectionManager.PORTAL_PHOTOS_DIR

    fun loadFolders() {
        val d = holder.dadb.value ?: run { _notConnected.value = true; return }
        _notConnected.value = false
        _state.value = _state.value.copy(isLoading = true)
        viewModelScope.launch {
            val suggestions = runCatching { screensaverManager.findImageFolders(d) }.getOrDefault(emptyList())
            val current = _state.value.folder ?: suggestions.firstOrNull()?.path ?: defaultFolder()
            _state.value = _state.value.copy(suggestions = suggestions, folder = current, isLoading = false)
            refreshContents()
        }
    }

    fun chooseFolder(context: Context, folder: String) {
        _state.value = _state.value.copy(folder = folder, selection = emptySet())
        refreshContents()
    }

    fun refreshContents() {
        val d = holder.dadb.value ?: run { _notConnected.value = true; return }
        val folder = _state.value.folder ?: defaultFolder()
        _state.value = _state.value.copy(isLoading = true)
        viewModelScope.launch {
            val contents = runCatching { fileManager.list(d, folder).filter { !it.isDirectory } }
                .getOrDefault(emptyList())
            val folderBytes = runCatching { screensaverManager.folderBytes(d, folder) }.getOrDefault(0L)
            val freeBytes = runCatching { screensaverManager.deviceFreeBytes(d) }.getOrDefault(0L)
            _state.value = _state.value.copy(
                contents = contents,
                folderBytes = folderBytes,
                deviceFreeBytes = freeBytes,
                isLoading = false
            )
        }
    }

    fun toggleSelect(path: String) {
        val selection = _state.value.selection
        _state.value = _state.value.copy(
            selection = if (path in selection) selection - path else selection + path
        )
    }

    fun clearSelection() {
        _state.value = _state.value.copy(selection = emptySet())
    }

    fun deleteSelected(context: Context) {
        val d = holder.dadb.value ?: run { _notConnected.value = true; return }
        val targets = _state.value.contents.filter { it.path in _state.value.selection }
        if (targets.isEmpty()) return
        viewModelScope.launch {
            val result = runCatching { targets.forEach { fileManager.delete(d, it, advancedMode = false) } }
            reportBatch(context, result)
            clearSelection()
            refreshContents()
        }
    }

    fun moveSelected(context: Context, destDir: String) {
        val d = holder.dadb.value ?: run { _notConnected.value = true; return }
        val targets = _state.value.contents.filter { it.path in _state.value.selection }
        if (targets.isEmpty()) return
        viewModelScope.launch {
            val result = runCatching { targets.forEach { fileManager.move(d, it, destDir, advancedMode = false) } }
            reportBatch(context, result)
            clearSelection()
            refreshContents()
        }
    }

    fun pullSelected(context: Context) {
        val d = holder.dadb.value ?: run { _notConnected.value = true; return }
        val targets = _state.value.contents.filter { it.path in _state.value.selection }
        if (targets.isEmpty()) return
        viewModelScope.launch {
            val result = runCatching {
                targets.forEach { fileManager.pull(d, it, getApplication<Application>().cacheDir, advancedMode = false) }
            }
            reportBatch(context, result)
            clearSelection()
        }
    }

    /**
     * Incarca pozele selectate, sarind peste duplicate cu exceptia celor
     * fortate prin [overrideDuplicates] (dupa numele fisierului).
     */
    fun uploadPhotos(context: Context, uris: List<Uri>, overrideDuplicates: Set<String> = emptySet()) {
        val d = holder.dadb.value ?: run { _notConnected.value = true; return }
        if (uris.isEmpty()) return
        // Garda de reintrare: nu pornim o a doua incarcare peste una in curs,
        // altfel referinta uploadJob veche se pierde si nu mai poate fi anulata.
        if (uploadJob?.isActive == true) return
        val folder = _state.value.folder ?: defaultFolder()

        uploadJob = viewModelScope.launch {
            // Construieste harta nume->uri si perechile (nume, dimensiune) pentru dedupe.
            val picked = uris.map { uri ->
                val name = ContentUriHelper.getDisplayName(context, uri) ?: "photo_${uri.hashCode()}.jpg"
                val size = ContentUriHelper.getSize(context, uri)
                Triple(name, size, uri)
            }
            val existing = _state.value.contents
                .map { DedupeKey(it.name, it.sizeBytes) }
                .toSet()
            val plan = planUpload(
                picked = picked.map { it.first to it.second },
                existing = existing,
                overrides = overrideDuplicates
            )
            // Filtram dupa cheia completa (nume + dimensiune), nu doar nume:
            // altfel doua poze cu acelasi nume dar dimensiuni diferite s-ar
            // amesteca, iar un duplicat deja incarcat ar reintra in plan.
            val planKeys = plan.map { DedupeKey(it.first, it.second) }.toSet()
            val toUpload = picked.filter { DedupeKey(it.first, it.second) in planKeys }
            val skipped = picked.size - toUpload.size

            var uploaded = 0
            var failed = 0
            var lastError: String? = null

            toUpload.forEachIndexed { index, (name, size, uri) ->
                _uploadState.value = UploadSummaryState.Uploading(
                    current = index + 1,
                    total = toUpload.size,
                    progress = TransferProgress(0L, size)
                )
                val local = withContext(Dispatchers.IO) {
                    runCatching {
                        val input = context.contentResolver.openInputStream(uri) ?: return@runCatching null
                        val tmp = File(context.cacheDir, name)
                        tmp.outputStream().use { out -> input.copyTo(out) }
                        input.close()
                        tmp
                    }.getOrNull()
                }
                if (local == null) {
                    failed++
                    lastError = context.getString(R.string.error_read_photo_failed)
                    return@forEachIndexed
                }
                val pushResult = runCatching { fileManager.push(d, local, folder, advancedMode = false) }
                pushResult
                    .onSuccess {
                        uploaded++
                        _uploadState.value = UploadSummaryState.Uploading(
                            current = index + 1,
                            total = toUpload.size,
                            progress = TransferProgress(size, size)
                        )
                    }
                    .onFailure {
                        failed++
                        lastError = reason(context, it)
                    }
                local.delete()
            }

            _uploadState.value = UploadSummaryState.Done(uploaded = uploaded, skipped = skipped, failed = failed)
            historyStore.add(
                HistoryEntry(
                    timestamp = System.currentTimeMillis(),
                    description = context.getString(R.string.history_photo_upload, uploaded, toUpload.size),
                    success = failed == 0
                )
            )
            clearSelection()
            refreshContents()
        }
    }

    /**
     * Aduce local (in cache-ul de miniaturi) un fisier de pe Portal si intoarce
     * fisierul cache. Reutilizeaza fisierul deja descarcat daca exista deja o
     * miniatura valida pentru aceeasi (cale, data, dimensiune).
     */
    suspend fun thumbnailFile(file: RemoteFile): File? {
        val d = holder.dadb.value ?: return null
        return withContext(Dispatchers.IO) {
            val cacheDir = getApplication<Application>().cacheDir
            val key = com.portalinstaller.data.screensaver.ThumbnailCache.keyFor(
                file.path, file.modifiedAt, file.sizeBytes
            )
            val cached = com.portalinstaller.data.screensaver.ThumbnailCache.fileFor(cacheDir, key)
            if (cached.exists() && cached.length() > 0L) return@withContext cached
            val result = runCatching {
                d.pull(cached, file.path)
                com.portalinstaller.data.screensaver.ThumbnailCache.trim(cacheDir)
                cached
            }
            result.getOrNull()
        }
    }

    fun cancelUpload() {
        uploadJob?.cancel()
        uploadJob = null
        _uploadState.value = UploadSummaryState.Idle
    }

    fun clearUploadState() {
        _uploadState.value = UploadSummaryState.Idle
    }

    fun clearNotConnected() {
        _notConnected.value = false
    }

    fun onDisconnected() {
        cancelUpload()
        _notConnected.value = false
        _state.value = ScreensaverState()
    }

    private fun reportBatch(context: Context, result: Result<Unit>) {
        _uploadState.value = result.fold(
            onSuccess = { UploadSummaryState.Idle },
            onFailure = { UploadSummaryState.Failed(reason(context, it)) }
        )
    }

    private fun reason(context: Context, t: Throwable): String =
        AdbErrors.formatReason(context, t.message, context.getString(R.string.error_unknown))
}
