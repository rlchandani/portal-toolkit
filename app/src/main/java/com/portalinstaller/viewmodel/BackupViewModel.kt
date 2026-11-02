package com.portalinstaller.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.portalinstaller.R
import com.portalinstaller.data.AppContainer
import com.portalinstaller.data.AdbSession
import com.portalinstaller.data.ConnectionHolder
import com.portalinstaller.data.PortalBackupPreview
import com.portalinstaller.data.PortalRestoreSummary
import com.portalinstaller.data.store.HistoryEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class BackupState {
    data object Idle : BackupState()
    data object Previewing : BackupState()
    data class ReadyToRestore(
        val source: Uri,
        val preview: PortalBackupPreview
    ) : BackupState()
    data class Creating(val current: Int, val total: Int, val packageName: String) : BackupState()
    data class Restoring(val current: Int, val total: Int, val packageName: String) : BackupState()
    data class Success(val message: String) : BackupState()
    data class Failed(val message: String) : BackupState()
}

internal fun backupRestoreOutcome(summary: PortalRestoreSummary, message: String): BackupState =
    if (summary.failedPackages.isEmpty() && summary.stoppedEarlyReason == null && summary.failedSettings.isEmpty()) {
        BackupState.Success(message)
    } else {
        BackupState.Failed(message)
    }

internal fun shouldRefreshAfterRestore(summary: PortalRestoreSummary): Boolean =
    summary.installedCount > 0 || summary.restoredSettingCount > 0

internal suspend fun completeBackupRestore(
    summary: PortalRestoreSummary,
    message: String,
    holder: ConnectionHolder,
    session: AdbSession,
    publishOutcome: (BackupState) -> Unit,
    recordHistory: suspend (String, Boolean) -> Unit,
    onRestored: () -> Unit
) {
    currentCoroutineContext().ensureActive()
    holder.ensureCurrent(session)
    val outcome = backupRestoreOutcome(summary, message)
    publishOutcome(outcome)
    recordHistory(message, outcome is BackupState.Success)
    currentCoroutineContext().ensureActive()
    // ConnectionHolder.set uses the same monitor: callback consumers capture this session.
    synchronized(holder) {
        holder.ensureCurrent(session)
        if (shouldRefreshAfterRestore(summary)) onRestored()
    }
}

/** Preview ownership is independent of ADB; a cancelled file read may return late. */
internal class BackupPreviewLifecycle(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<BackupState>,
) {
    private class Operation { var job: Job? = null }
    @Volatile private var active: Operation? = null

    fun launch(block: suspend (() -> Unit) -> Unit): Job {
        val operation = Operation()
        active = operation
        state.value = BackupState.Previewing
        val job = scope.launch {
            block {
                coroutineContext.ensureActive()
                if (active !== operation) throw CancellationException("Backup preview changed")
            }
        }
        operation.job = job
        job.invokeOnCompletion { error ->
            if (active === operation) {
                active = null
                if (error is CancellationException && state.value is BackupState.Previewing)
                    state.value = BackupState.Idle
            }
        }
        return job
    }

    fun cancel() {
        val operation = active ?: return
        active = null
        operation.job?.cancel()
        if (state.value is BackupState.Previewing) state.value = BackupState.Idle
    }
}

/** Main-thread restore ownership; cancelled IO may finish after a newer operation starts. */
internal class BackupRestoreLifecycle(
    private val holder: ConnectionHolder,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<BackupState>,
) {
    private class Operation {
        var job: Job? = null
        var sessionWatcher: Job? = null
    }

    @Volatile private var active: Operation? = null

    fun launch(session: AdbSession, block: suspend (() -> Unit) -> Unit): Job {
        val operation = Operation()
        active = operation
        state.value = BackupState.Restoring(0, 0, "")
        val job = holder.launchForSession(scope, session) {
            block {
                coroutineContext.ensureActive()
                if (active !== operation) throw CancellationException("Backup restore changed")
                holder.ensureCurrent(session)
            }
        }
        operation.job = job
        // This watcher is outside the restore job so blocked/cancelled IO cannot delay
        // releasing its UI state when the captured connection disappears.
        operation.sessionWatcher = scope.launch {
            holder.session.first { !holder.isCurrent(session) }
            cancel(operation)
        }
        job.invokeOnCompletion { error ->
            operation.sessionWatcher?.cancel()
            if (active === operation) {
                active = null
                if (error is CancellationException && state.value is BackupState.Restoring) {
                    state.value = BackupState.Idle
                }
            }
        }
        return job
    }

    fun cancel() {
        active?.let(::cancel)
    }

    private fun cancel(operation: Operation) {
        if (active !== operation) return
        active = null
        operation.sessionWatcher?.cancel()
        operation.job?.cancel()
        if (state.value is BackupState.Restoring) state.value = BackupState.Idle
    }
}

/**
 * Creează, inspectează și restaurează arhive de backup. Restaurarea reușită
 * cere reîmprospătarea listei de aplicații și a stării dispozitivului, dar
 * acele ViewModel-uri sunt independente; MainScreen leagă acele acțiuni.
 */
class BackupViewModel(app: Application) : AndroidViewModel(app) {

    private val container = AppContainer.from(app)
    private val backupManager = container.backupManager
    private val holder = container.connectionHolder
    private val historyStore = container.historyStore

    private val _backupState = MutableStateFlow<BackupState>(BackupState.Idle)
    val backupState: StateFlow<BackupState> = _backupState.asStateFlow()
    private val restoreLifecycle = BackupRestoreLifecycle(holder, viewModelScope, _backupState)
    private val previewLifecycle = BackupPreviewLifecycle(viewModelScope, _backupState)

    // Job-ul backup/restore in curs, ca sa poata fi anulat din UI.
    private var backupJob: Job? = null

    private suspend fun addHistory(description: String, success: Boolean) {
        historyStore.add(HistoryEntry(System.currentTimeMillis(), description, success))
    }

    fun createBackup(context: Context, destination: Uri, selectedPackages: Set<String>? = null) {
        if (_backupState.value is BackupState.Creating ||
            _backupState.value is BackupState.Restoring ||
            _backupState.value is BackupState.Previewing
        ) return
        val connection = holder.dadb.value
        if (connection == null) {
            _backupState.value = BackupState.Failed(context.getString(R.string.backup_error_connect_first))
            return
        }
        backupJob = viewModelScope.launch {
            _backupState.value = BackupState.Creating(0, 0, "")
            runCatching {
                withContext(Dispatchers.IO) {
                    backupManager.createBackup(context, connection, destination, selectedPackages) {
                            current, total, packageName ->
                        _backupState.value = BackupState.Creating(current, total, packageName)
                    }
                }
            }.onSuccess { summary ->
                val message = context.getString(
                    R.string.backup_created_success,
                    summary.appCount,
                    summary.settingCount
                )
                _backupState.value = BackupState.Success(message)
                addHistory(message, success = true)
            }.onFailure { error ->
                if (error is CancellationException) return@onFailure
                val message = context.getString(
                    R.string.backup_failed,
                    error.message ?: context.getString(R.string.error_unknown)
                )
                _backupState.value = BackupState.Failed(message)
                addHistory(message, success = false)
            }
        }
    }

    /** The UI may ignore this job; inspection owners can await cancellation settlement. */
    fun inspectBackup(context: Context, source: Uri): Job? {
        if (_backupState.value is BackupState.Creating ||
            _backupState.value is BackupState.Restoring ||
            _backupState.value is BackupState.Previewing
        ) {
            return null
        }

        return previewLifecycle.launch { ensureCurrent ->
            runCatching {
                withContext(Dispatchers.IO) {
                    ensureCurrent()
                    backupManager.inspectBackup(context, source, checkCancelled = ensureCurrent)
                }
            }.onSuccess { preview ->
                ensureCurrent()
                _backupState.value = BackupState.ReadyToRestore(source, preview)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                ensureCurrent()
                _backupState.value = BackupState.Failed(
                    context.getString(
                        R.string.backup_preview_failed,
                        error.message ?: context.getString(R.string.error_unknown)
                    )
                )
            }
        }
    }

    /**
     * Restaurează dintr-o sursă. După orice modificare confirmată cheamă [onRestored]
     * pentru ca MainScreen să reîmprospăteze aplicațiile/starea pe aceeași sesiune.
     */
    fun restoreBackup(context: Context, source: Uri, onRestored: () -> Unit = {}) {
        val session = holder.capture()
        if (session == null) {
            _backupState.value = BackupState.Failed(context.getString(R.string.backup_error_connect_first))
            return
        }
        if (_backupState.value is BackupState.Creating ||
            _backupState.value is BackupState.Restoring ||
            _backupState.value is BackupState.Previewing
        ) {
            return
        }

        backupJob = restoreLifecycle.launch(session) { ensureCurrent ->
            runCatching {
                withContext(Dispatchers.IO) {
                    ensureCurrent()
                    backupManager.restoreBackup(context, session.dadb, source) {
                            current, total, packageName ->
                        ensureCurrent()
                        _backupState.value = BackupState.Restoring(current, total, packageName)
                    }
                }
            }.onSuccess { summary ->
                ensureCurrent()
                val message = if (summary.stoppedEarlyReason != null) {
                    context.getString(
                        R.string.backup_restored_stopped,
                        summary.installedCount,
                        summary.failedPackages.size,
                        summary.stoppedEarlyReason
                    )
                } else if (summary.failedSettings.isNotEmpty()) {
                    context.getString(R.string.backup_restore_failed, summary.failedSettings.joinToString())
                } else if (summary.failedPackages.isEmpty()) {
                    context.getString(
                        R.string.backup_restored_success,
                        summary.installedCount,
                        summary.restoredSettingCount
                    )
                } else {
                    context.getString(
                        R.string.backup_restored_partial,
                        summary.installedCount,
                        summary.failedPackages.size,
                        summary.failedPackages.joinToString()
                    )
                }
                completeBackupRestore(
                    summary, message, holder, session,
                    publishOutcome = { _backupState.value = it },
                    recordHistory = ::addHistory,
                    onRestored = onRestored
                )
            }.onFailure { error ->
                if (error is CancellationException) throw error
                ensureCurrent()
                val message = context.getString(
                    R.string.backup_restore_failed,
                    error.message ?: context.getString(R.string.error_unknown)
                )
                _backupState.value = BackupState.Failed(message)
                addHistory(message, success = false)
            }
        }
    }

    /** Anuleaza backup-ul/restaurarea in curs (daca exista). */
    fun cancelBackup() {
        previewLifecycle.cancel()
        restoreLifecycle.cancel()
        backupJob?.cancel()
        backupJob = null
        _backupState.value = BackupState.Idle
    }

    fun clearBackupState() {
        previewLifecycle.cancel()
        _backupState.value = BackupState.Idle
    }

    /** Resetează la deconectare, anulând orice backup/restore în curs. */
    fun onDisconnected() {
        previewLifecycle.cancel()
        restoreLifecycle.cancel()
        backupJob?.cancel()
        backupJob = null
        _backupState.value = BackupState.Idle
    }
}
