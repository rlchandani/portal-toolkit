package com.portalinstaller.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.portalinstaller.R
import com.portalinstaller.data.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** O linie din transcriptul shell-ului: comanda rulata plus rezultatul ei. */
data class ShellEntry(
    val command: String,
    val output: String,
    val isError: Boolean
)

/**
 * Ruleaza comenzi shell pe Portal prin conexiunea ADB partajata. Fiecare
 * comanda se executa pe [Dispatchers.IO] (dadb.shell este blocant), iar
 * transcriptul se acumuleaza imutabil. Comenzile reusite se salveaza in
 * [AppContainer.shellHistoryStore] pentru reapel (sageti sus/jos in UI).
 */
class ShellViewModel(app: Application) : AndroidViewModel(app) {

    private val container = AppContainer.from(app)
    private val holder = container.connectionHolder
    private val shellHistoryStore = container.shellHistoryStore

    /** Comenzi rapide implicite oferite ca „chips” in UI. */
    val quickCommands: List<String> = listOf(
        "pm list packages -3",
        "getprop",
        "wm size",
        "df -h"
    )

    private val _transcript = MutableStateFlow<List<ShellEntry>>(emptyList())
    val transcript: StateFlow<List<ShellEntry>> = _transcript.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    val commandHistory: StateFlow<List<String>> = shellHistoryStore.commands
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var runningJob: Job? = null

    fun run(context: Context, command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty() || _isRunning.value) return

        val d = holder.dadb.value
        if (d == null) {
            appendEntry(
                ShellEntry(
                    command = trimmed,
                    output = context.getString(R.string.shell_not_connected),
                    isError = true
                )
            )
            return
        }

        runningJob = viewModelScope.launch {
            _isRunning.value = true
            val result = withContext(Dispatchers.IO) {
                runCatching { d.shell(trimmed).allOutput }
            }
            val entry = result.fold(
                onSuccess = { output -> ShellEntry(trimmed, output.trimEnd(), isError = false) },
                onFailure = { e ->
                    ShellEntry(
                        command = trimmed,
                        output = e.message ?: context.getString(R.string.error_unknown),
                        isError = true
                    )
                }
            )
            appendEntry(entry)
            if (!entry.isError) shellHistoryStore.add(trimmed)
            _isRunning.value = false
        }
    }

    fun cancel() {
        runningJob?.cancel()
        runningJob = null
        _isRunning.value = false
    }

    fun clearTranscript() {
        _transcript.value = emptyList()
    }

    fun onDisconnected() {
        cancel()
    }

    private fun appendEntry(entry: ShellEntry) {
        _transcript.value = _transcript.value + entry
    }
}
