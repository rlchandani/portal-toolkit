package com.portalinstaller.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.portalinstaller.R
import com.portalinstaller.data.AdbErrors
import com.portalinstaller.data.AppContainer
import com.portalinstaller.data.files.PathSafety
import com.portalinstaller.data.files.RemoteFile
import com.portalinstaller.data.transfer.TransferProgress
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Starea navigatorului de fisiere: calea curenta, continutul si selectia. */
data class FileBrowserState(
    val path: String = PathSafety.SDCARD_ROOT,
    val entries: List<RemoteFile> = emptyList(),
    val isLoading: Boolean = false,
    val selection: Set<String> = emptySet(),
    val advancedMode: Boolean = false,
    /** Mesaj de eroare la listare (ex. permisiune refuzata), null cand e ok. */
    val error: String? = null
)

/** Starea unui transfer de fisiere (pull/push) cu progres per-element. */
sealed class FileTransferState {
    data object Idle : FileTransferState()
    data class Working(
        val current: Int,
        val total: Int,
        val progress: TransferProgress
    ) : FileTransferState()
    data class Done(val pulled: Int, val pushed: Int) : FileTransferState()
    data class Failed(val message: String) : FileTransferState()
}

/**
 * Gestioneaza navigarea si operatiile pe fisierele Portal. Toate operatiile
 * trec prin [com.portalinstaller.data.files.PortalFileManager] si raporteaza
 * erorile prin [AdbErrors]. Transferurile pot fi anulate via [cancelTransfer].
 */
class FileManagerViewModel(app: Application) : AndroidViewModel(app) {

    private val container = AppContainer.from(app)
    private val holder = container.connectionHolder
    private val fileManager = container.fileManager

    private val _browser = MutableStateFlow(FileBrowserState())
    val browser: StateFlow<FileBrowserState> = _browser.asStateFlow()

    private val _transferState = MutableStateFlow<FileTransferState>(FileTransferState.Idle)
    val transferState: StateFlow<FileTransferState> = _transferState.asStateFlow()

    private val _notConnected = MutableStateFlow(false)
    val notConnected: StateFlow<Boolean> = _notConnected.asStateFlow()

    private var transferJob: Job? = null

    // Toate operatiile (delete/rename/move/mkdir/pull/push) impart transferJob.
    // Daca am porni una noua peste alta in curs, referinta veche s-ar pierde si
    // n-am mai putea-o anula. Refuzam suprapunerea.
    private fun operationInFlight(): Boolean = transferJob?.isActive == true

    init {
        // Incarcam radacina automat cand apare o conexiune. Inainte, nimic nu
        // apela open() la deschiderea tabului, deci lista ramanea mereu goala pe
        // /sdcard. Observand holder-ul (care traieste dincolo de compozitie),
        // listarea ruleaza exact o data per conexiune, fara LaunchedEffect fragil.
        viewModelScope.launch {
            holder.dadb.collect { d ->
                if (d != null && _browser.value.entries.isEmpty()) {
                    open(_browser.value.path)
                }
            }
        }
    }

    fun open(path: String) {
        val d = holder.dadb.value
        if (d == null) {
            _notConnected.value = true
            return
        }
        _notConnected.value = false
        _browser.value = _browser.value.copy(
            path = path, isLoading = true, selection = emptySet(), error = null
        )
        viewModelScope.launch {
            val result = runCatching { fileManager.list(d, path) }
            _browser.value = result.fold(
                onSuccess = { entries ->
                    _browser.value.copy(entries = entries, isLoading = false, error = null)
                },
                onFailure = { t ->
                    // Nu inghitim eroarea in lista goala: o aratam utilizatorului.
                    _browser.value.copy(
                        entries = emptyList(),
                        isLoading = false,
                        error = AdbErrors.formatReason(
                            getApplication(),
                            t.message,
                            getApplication<Application>().getString(R.string.error_unknown)
                        )
                    )
                }
            )
        }
    }

    fun navigateUp() {
        val current = _browser.value.path
        // Nu urca peste /sdcard decat in mod avansat.
        if (current == PathSafety.SDCARD_ROOT && !_browser.value.advancedMode) return
        open(FilePaths.parentOf(current))
    }

    fun refresh() = open(_browser.value.path)

    fun toggleSelect(path: String) {
        val selection = _browser.value.selection
        _browser.value = _browser.value.copy(
            selection = if (path in selection) selection - path else selection + path
        )
    }

    fun clearSelection() {
        _browser.value = _browser.value.copy(selection = emptySet())
    }

    fun setAdvancedMode(on: Boolean) {
        _browser.value = _browser.value.copy(advancedMode = on)
    }

    fun deleteSelected(context: Context) {
        val d = holder.dadb.value ?: run { _notConnected.value = true; return }
        if (operationInFlight()) return
        val state = _browser.value
        val targets = state.entries.filter { it.path in state.selection }
        if (targets.isEmpty()) return
        transferJob = viewModelScope.launch {
            val result = runCatching {
                targets.forEach { fileManager.delete(d, it, state.advancedMode) }
            }
            reportBatch(context, result)
            clearSelection()
            refresh()
        }
    }

    fun rename(context: Context, file: RemoteFile, newName: String) {
        val d = holder.dadb.value ?: run { _notConnected.value = true; return }
        if (operationInFlight()) return
        val advanced = _browser.value.advancedMode
        transferJob = viewModelScope.launch {
            val result = runCatching { fileManager.rename(d, file, newName, advanced) }
            reportBatch(context, result)
            refresh()
        }
    }

    fun moveSelected(context: Context, destDir: String) {
        val d = holder.dadb.value ?: run { _notConnected.value = true; return }
        val state = _browser.value
        if (operationInFlight()) return
        val targets = state.entries.filter { it.path in state.selection }
        if (targets.isEmpty()) return
        transferJob = viewModelScope.launch {
            val result = runCatching {
                targets.forEach { fileManager.move(d, it, destDir, state.advancedMode) }
            }
            reportBatch(context, result)
            clearSelection()
            refresh()
        }
    }

    fun mkdir(context: Context, name: String) {
        val d = holder.dadb.value ?: run { _notConnected.value = true; return }
        if (operationInFlight()) return
        val state = _browser.value
        transferJob = viewModelScope.launch {
            val result = runCatching { fileManager.mkdir(d, state.path, name, state.advancedMode) }
            reportBatch(context, result)
            refresh()
        }
    }

    fun pullSelected(context: Context) {
        val d = holder.dadb.value ?: run { _notConnected.value = true; return }
        val state = _browser.value
        if (operationInFlight()) return
        val targets = state.entries.filter { it.path in state.selection && !it.isDirectory }
        if (targets.isEmpty()) return
        transferJob = viewModelScope.launch {
            val result = runCatching {
                targets.forEachIndexed { index, file ->
                    _transferState.value = FileTransferState.Working(
                        current = index + 1,
                        total = targets.size,
                        progress = TransferProgress(0L, file.sizeBytes)
                    )
                    fileManager.pull(d, file, getApplication<Application>().cacheDir, state.advancedMode)
                    _transferState.value = FileTransferState.Working(
                        current = index + 1,
                        total = targets.size,
                        progress = TransferProgress(file.sizeBytes, file.sizeBytes)
                    )
                }
            }
            _transferState.value = result.fold(
                onSuccess = { FileTransferState.Done(pulled = targets.size, pushed = 0) },
                onFailure = { FileTransferState.Failed(reason(context, it)) }
            )
            clearSelection()
        }
    }

    fun pushLocal(context: Context, localFile: java.io.File) {
        val d = holder.dadb.value ?: run { _notConnected.value = true; return }
        if (operationInFlight()) return
        val state = _browser.value
        transferJob = viewModelScope.launch {
            _transferState.value = FileTransferState.Working(
                current = 1, total = 1, progress = TransferProgress(0L, localFile.length())
            )
            val result = runCatching { fileManager.push(d, localFile, state.path, state.advancedMode) }
            _transferState.value = result.fold(
                onSuccess = { FileTransferState.Done(pulled = 0, pushed = 1) },
                onFailure = { FileTransferState.Failed(reason(context, it)) }
            )
            refresh()
        }
    }

    fun cancelTransfer() {
        transferJob?.cancel()
        transferJob = null
        _transferState.value = FileTransferState.Idle
    }

    fun clearTransferState() {
        _transferState.value = FileTransferState.Idle
    }

    fun clearNotConnected() {
        _notConnected.value = false
    }

    fun onDisconnected() {
        cancelTransfer()
        _browser.value = FileBrowserState()
    }

    private fun reportBatch(context: Context, result: Result<Unit>) {
        _transferState.value = result.fold(
            onSuccess = { FileTransferState.Idle },
            onFailure = { FileTransferState.Failed(reason(context, it)) }
        )
    }

    private fun reason(context: Context, t: Throwable): String =
        AdbErrors.formatReason(context, t.message, context.getString(R.string.error_unknown))
}
