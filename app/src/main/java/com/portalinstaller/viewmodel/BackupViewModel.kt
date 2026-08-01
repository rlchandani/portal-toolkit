package com.portalinstaller.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.portalinstaller.R
import com.portalinstaller.data.AppContainer
import com.portalinstaller.data.PortalBackupPreview
import com.portalinstaller.data.store.HistoryEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    // Job-ul backup/restore in curs, ca sa poata fi anulat din UI.
    private var backupJob: Job? = null

    private suspend fun addHistory(description: String, success: Boolean) {
        historyStore.add(HistoryEntry(System.currentTimeMillis(), description, success))
    }

    fun createBackup(context: Context, destination: Uri, selectedPackages: Set<String>? = null) {
        val connection = holder.dadb.value
        if (connection == null) {
            _backupState.value = BackupState.Failed(context.getString(R.string.backup_error_connect_first))
            return
        }
        if (_backupState.value is BackupState.Creating || _backupState.value is BackupState.Restoring) return

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

    fun inspectBackup(context: Context, source: Uri) {
        if (_backupState.value is BackupState.Creating ||
            _backupState.value is BackupState.Restoring ||
            _backupState.value is BackupState.Previewing
        ) {
            return
        }

        viewModelScope.launch {
            _backupState.value = BackupState.Previewing
            runCatching {
                withContext(Dispatchers.IO) { backupManager.inspectBackup(context, source) }
            }.onSuccess { preview ->
                _backupState.value = BackupState.ReadyToRestore(source, preview)
            }.onFailure { error ->
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
     * Restaurează dintr-o sursă. La succes complet cheamă [onRestored] pentru ca
     * MainScreen să reîmprospăteze aplicațiile/starea (concerns din alte VM-uri).
     */
    fun restoreBackup(context: Context, source: Uri, onRestored: () -> Unit = {}) {
        val connection = holder.dadb.value
        if (connection == null) {
            _backupState.value = BackupState.Failed(context.getString(R.string.backup_error_connect_first))
            return
        }
        if (_backupState.value is BackupState.Creating ||
            _backupState.value is BackupState.Restoring ||
            _backupState.value is BackupState.Previewing
        ) {
            return
        }

        backupJob = viewModelScope.launch {
            _backupState.value = BackupState.Restoring(0, 0, "")
            runCatching {
                withContext(Dispatchers.IO) {
                    backupManager.restoreBackup(context, connection, source) {
                            current, total, packageName ->
                        _backupState.value = BackupState.Restoring(current, total, packageName)
                    }
                }
            }.onSuccess { summary ->
                val message = if (summary.stoppedEarlyReason != null) {
                    context.getString(
                        R.string.backup_restored_stopped,
                        summary.installedCount,
                        summary.failedPackages.size,
                        summary.stoppedEarlyReason
                    )
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
                _backupState.value = BackupState.Success(message)
                addHistory(
                    message,
                    success = summary.failedPackages.isEmpty() && summary.stoppedEarlyReason == null
                )
                if (summary.stoppedEarlyReason == null) {
                    onRestored()
                }
            }.onFailure { error ->
                if (error is CancellationException) return@onFailure
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
        backupJob?.cancel()
        backupJob = null
        _backupState.value = BackupState.Idle
    }

    fun clearBackupState() {
        _backupState.value = BackupState.Idle
    }

    /** Resetează la deconectare. */
    fun onDisconnected() {
        _backupState.value = BackupState.Idle
    }
}
