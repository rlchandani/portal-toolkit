package com.portalinstaller.viewmodel

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.portalinstaller.R
import com.portalinstaller.data.AdbErrors
import com.portalinstaller.data.AdbSession
import com.portalinstaller.data.AppContainer
import com.portalinstaller.data.files.PathSafety
import com.portalinstaller.data.files.RemoteFile
import com.portalinstaller.data.transfer.TransferProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Starea navigatorului de fisiere: calea curenta, continutul si selectia. */
data class FileBrowserState(
    val path: String = PathSafety.SDCARD_ROOT,
    val entries: List<RemoteFile> = emptyList(),
    val isLoading: Boolean = false,
    val selection: Set<String> = emptySet(),
    val advancedMode: Boolean = false,
    val error: String? = null
)

/** Byte progress counts actual I/O; completion also requires verification and publication. */
sealed class FileTransferState {
    data object Idle : FileTransferState()
    data class Working(val current: Int, val total: Int, val progress: TransferProgress) : FileTransferState()
    data class Done(val pulled: Int, val pushed: Int) : FileTransferState()
    data class Failed(val message: String, val cancelled: Boolean = false) : FileTransferState()
}

data class FileItemResult(
    val path: String,
    val operation: String,
    val succeeded: Boolean,
    val message: String? = null,
    val savedUri: Uri? = null,
    val cancelled: Boolean = false,
    val deviceLabel: String? = null,
    val sessionGeneration: Long? = null
)

/** A batch keeps completed items and marks unconfirmed/remaining items for retry. */
internal fun interruptedFileResults(
    paths: List<String>, operation: String, completed: List<FileItemResult>
): List<FileItemResult> = completed + paths.filterNot { path -> completed.any { it.path == path } }.map {
    FileItemResult(it, operation, false, "Cancelled before completion was confirmed", cancelled = true)
}

class FileManagerViewModel(app: Application) : AndroidViewModel(app) {
    private val container = AppContainer.from(app)
    private val holder = container.connectionHolder
    private val fileManager = container.fileManager
    private val _browser = MutableStateFlow(FileBrowserState())
    val browser: StateFlow<FileBrowserState> = _browser.asStateFlow()
    private val _transferState = MutableStateFlow<FileTransferState>(FileTransferState.Idle)
    val transferState: StateFlow<FileTransferState> = _transferState.asStateFlow()
    private val _itemResults = MutableStateFlow<List<FileItemResult>>(emptyList())
    val itemResults: StateFlow<List<FileItemResult>> = _itemResults.asStateFlow()
    private val _notConnected = MutableStateFlow(false)
    val notConnected: StateFlow<Boolean> = _notConnected.asStateFlow()
    private var transferJob: Job? = null
    private var browserJob: Job? = null
    private var listingGeneration = 0L
    @Volatile private var operationGeneration = 0L

    // Cancelled blocking I/O may still be unwinding. Keep ownership until completion.
    private fun operationInFlight() = transferJob?.isCompleted == false

    init {
        viewModelScope.launch {
            holder.session.collect { session ->
                cancelTransfer()
                browserJob?.cancel()
                listingGeneration++
                _browser.value = FileBrowserState(advancedMode = _browser.value.advancedMode)
                _notConnected.value = session == null
                if (session != null) open(PathSafety.SDCARD_ROOT)
            }
        }
    }

    fun open(path: String) {
        val session = holder.capture() ?: run { _notConnected.value = true; return }
        val advanced = _browser.value.advancedMode
        val normalized = try {
            PathSafety.requireAllowed(path, advanced)
            PathSafety.normalize(path)
        } catch (e: Exception) {
            _browser.value = _browser.value.copy(isLoading = false, error = e.message)
            return
        }
        browserJob?.cancel()
        val generation = ++listingGeneration
        _notConnected.value = false
        _browser.value = _browser.value.copy(path = normalized, entries = emptyList(), isLoading = true,
            selection = emptySet(), error = null)
        browserJob = holder.launchForSession(viewModelScope, session) {
            try {
                val entries = fileManager.list(session.dadb, normalized, advanced)
                holder.ensureCurrent(session)
                if (generation == listingGeneration) {
                    _browser.value = _browser.value.copy(entries = entries, isLoading = false)
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (holder.isCurrent(session) && generation == listingGeneration) {
                    _browser.value = _browser.value.copy(entries = emptyList(), isLoading = false,
                        error = reason(getApplication(), e))
                }
            }
        }
    }

    fun navigateUp() {
        val current = _browser.value.path
        if (current == PathSafety.SDCARD_ROOT && !_browser.value.advancedMode) return
        open(FilePaths.parentOf(current))
    }
    fun refresh() = open(_browser.value.path)
    fun toggleSelect(path: String) {
        val selection = _browser.value.selection
        _browser.value = _browser.value.copy(selection = if (path in selection) selection - path else selection + path)
    }
    fun clearSelection() { _browser.value = _browser.value.copy(selection = emptySet()) }
    fun setAdvancedMode(on: Boolean) {
        _browser.value = _browser.value.copy(advancedMode = on)
        if (!on && !PathSafety.isWithinSdcard(_browser.value.path)) open(PathSafety.SDCARD_ROOT)
    }

    private fun selected() = _browser.value.let { state -> state.entries.filter { it.path in state.selection } }

    fun deleteSelected(context: Context) {
        val state = _browser.value
        runBatch(context, "delete", selected().map { it.path }) { session, path, _ ->
            fileManager.delete(session.dadb, state.entries.first { it.path == path }, state.advancedMode)
            null
        }
    }
    fun rename(context: Context, file: RemoteFile, newName: String) {
        val advanced = _browser.value.advancedMode
        runBatch(context, "rename", listOf(file.path)) { session, _, _ ->
            fileManager.rename(session.dadb, file, newName, advanced)
            null
        }
    }
    fun moveSelected(context: Context, destDir: String) {
        val state = _browser.value
        runBatch(context, "move", selected().map { it.path }) { session, path, _ ->
            fileManager.move(session.dadb, state.entries.first { it.path == path }, destDir, state.advancedMode)
            null
        }
    }
    fun mkdir(context: Context, name: String) {
        val state = _browser.value
        runBatch(context, "mkdir", listOf("${state.path}/$name")) { session, _, _ ->
            fileManager.mkdir(session.dadb, state.path, name, state.advancedMode)
            null
        }
    }

    /** API 29+ publishes Downloads; older phones need a user-selected SAF tree. */
    fun pullSelected(context: Context, destinationTree: Uri? = null) {
        val state = _browser.value
        val targets = selected().filterNot { it.isDirectory }
        runBatch(context, "pull", targets.map { it.path }) { session, path, progress ->
            val file = targets.first { it.path == path }
            withContext(Dispatchers.IO) {
                val tempDir = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "portal-pull-").toFile()
                try {
                    val local = fileManager.pull(session.dadb, file, tempDir, state.advancedMode, progress)
                    holder.ensureCurrent(session)
                    publishDownload(context, local, destinationTree)
                } finally { tempDir.deleteRecursively() }
            }
        }
    }

    fun pushLocal(context: Context, localFile: File) {
        val state = _browser.value
        runBatch(context, "push", listOf(localFile.absolutePath)) { session, _, progress ->
            fileManager.push(session.dadb, localFile, state.path, state.advancedMode, progress)
            null
        }
    }

    private fun runBatch(
        context: Context, operation: String, paths: List<String>,
        action: suspend (AdbSession, String, (TransferProgress) -> Unit) -> Uri?
    ) {
        val session = holder.capture() ?: run { _notConnected.value = true; return }
        if (operationInFlight() || paths.isEmpty()) return
        val generation = ++operationGeneration
        _itemResults.value = emptyList()
        _transferState.value = FileTransferState.Working(1, paths.size, TransferProgress(0, 0))
        val completed = mutableListOf<FileItemResult>()
        transferJob = holder.launchForSession(viewModelScope, session) {
            try {
                paths.forEachIndexed { index, path ->
                    holder.ensureCurrent(session)
                    _transferState.value = FileTransferState.Working(index + 1, paths.size, TransferProgress(0, 0))
                    try {
                        val saved = action(session, path) { progress ->
                            if (holder.isCurrent(session) && generation == operationGeneration) {
                                _transferState.value = FileTransferState.Working(index + 1, paths.size, progress)
                            }
                        }
                        holder.ensureCurrent(session)
                        completed += FileItemResult(path, operation, true, savedUri = saved,
                            deviceLabel = session.label, sessionGeneration = session.generation)
                    } catch (e: CancellationException) { throw e
                    } catch (e: Exception) {
                        completed += FileItemResult(path, operation, false, reason(context, e),
                            deviceLabel = session.label, sessionGeneration = session.generation)
                    }
                    if (generation == operationGeneration) _itemResults.value = completed.toList()
                }
                val successes = completed.count { it.succeeded }
                if (completed.any { !it.succeeded }) {
                    _transferState.value = FileTransferState.Failed(completed.filterNot { it.succeeded }
                        .joinToString("\n") { "${it.path}: ${it.message}" })
                } else if (operation == "pull" || operation == "push") {
                    _transferState.value = FileTransferState.Done(
                        if (operation == "pull") successes else 0, if (operation == "push") successes else 0)
                } else _transferState.value = FileTransferState.Idle
                clearSelection()
                if (operation != "pull") refresh()
            } catch (e: CancellationException) {
                throw e
            }
        }
        transferJob?.invokeOnCompletion { failure ->
            if (failure is CancellationException && generation == operationGeneration) {
                _itemResults.value = interruptedFileResults(paths, operation, completed).map {
                    it.copy(deviceLabel = session.label, sessionGeneration = session.generation)
                }
                _transferState.value = FileTransferState.Failed(
                    "Cancelled; completed items were retained. An in-flight result may be unconfirmed.", cancelled = true)
            }
        }
    }

    private suspend fun publishDownload(context: Context, local: File, tree: Uri?): Uri {
        val resolver = context.contentResolver
        val mime = java.net.URLConnection.guessContentTypeFromName(local.name) ?: "application/octet-stream"
        val uri = if (tree != null) {
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            DocumentsContract.createDocument(resolver, parent, mime, local.name)
        } else {
            require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { "Choose a document folder to save this download" }
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, local.name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/PortalToolkit")
                put(MediaStore.Downloads.IS_PENDING, 1)
            })
        } ?: throw IllegalStateException("Cannot create download document")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                local.inputStream().use { input ->
                    val bytes = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(bytes)
                        if (count < 0) break
                        output.write(bytes, 0, count)
                    }
                }
            } ?: throw IllegalStateException("Cannot open download document")
            currentCoroutineContext().ensureActive()
            if (tree == null) check(resolver.update(uri, ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }, null, null) > 0) { "Cannot publish download" }
            return uri
        } catch (e: Exception) {
            runCatching {
                if (tree != null) DocumentsContract.deleteDocument(resolver, uri) else resolver.delete(uri, null, null)
            }
            throw e
        }
    }

    fun cancelTransfer() {
        if (transferJob?.isCompleted == false) {
            transferJob?.cancel()
            _transferState.value = FileTransferState.Failed("Cancellation requested; in-flight completion is unconfirmed", true)
        }
    }
    fun clearTransferState() { if (!operationInFlight()) _transferState.value = FileTransferState.Idle }
    fun clearNotConnected() { _notConnected.value = false }
    fun onDisconnected() {
        cancelTransfer()
        browserJob?.cancel()
        listingGeneration++
        _browser.value = FileBrowserState()
    }
    private fun reason(context: Context, t: Throwable) =
        AdbErrors.formatReason(context, t.message, context.getString(R.string.error_unknown))
}
