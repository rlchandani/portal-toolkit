package com.portalinstaller.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.portalinstaller.data.AppContainer
import com.portalinstaller.data.tools.LogLine
import com.portalinstaller.data.tools.LogRingBuffer
import com.portalinstaller.data.tools.LogcatParser
import dadb.AdbStream
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Streameaza logcat de pe Portal prin canalul ADB brut (`dadb.open`). Liniile
 * sunt parsate cu [LogcatParser] si acumulate intr-un [LogRingBuffer] marginit.
 * Filtrarea dupa nivel/tag se face client-side peste buffer; modul „doar erori”
 * reporneste stream-ul ca `logcat *:E` pentru a reduce traficul de la sursa.
 */
class LogcatViewModel(app: Application) : AndroidViewModel(app) {

    private val holder = AppContainer.from(app).connectionHolder

    private val _buffer = MutableStateFlow(LogRingBuffer(CAPACITY))
    val buffer: StateFlow<LogRingBuffer> = _buffer.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _levelFilter = MutableStateFlow<Char?>(null)
    val levelFilter: StateFlow<Char?> = _levelFilter.asStateFlow()

    private val _tagFilter = MutableStateFlow("")
    val tagFilter: StateFlow<String> = _tagFilter.asStateFlow()

    private val _errorsOnly = MutableStateFlow(false)
    val errorsOnly: StateFlow<Boolean> = _errorsOnly.asStateFlow()

    private val _crashLineIndex = MutableStateFlow<Int?>(null)
    val crashLineIndex: StateFlow<Int?> = _crashLineIndex.asStateFlow()

    private val _notConnected = MutableStateFlow(false)
    val notConnected: StateFlow<Boolean> = _notConnected.asStateFlow()

    private var streamJob: Job? = null
    // Each run owns its stream, including cleanup after a stop/restart.
    private var stream: AtomicReference<AdbStream?>? = null
    // Closing can block on a write. One-shot cleanup must also survive
    // viewModelScope cancellation so it can unblock the reader in onCleared.
    private val streamCloseScope = CoroutineScope(Dispatchers.IO)

    fun start() {
        if (_isStreaming.value) return
        val d = holder.dadb.value
        if (d == null) {
            _notConnected.value = true
            return
        }
        _notConnected.value = false
        val destination = if (_errorsOnly.value) "shell:logcat *:E" else "shell:logcat -v brief"
        val ownedStream = AtomicReference<AdbStream?>()
        stream = ownedStream
        _isStreaming.value = true

        streamJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val opened = d.open(destination)
                        ownedStream.set(opened)
                        try {
                            // open/read are blocking: cancellation may have
                            // happened while either operation was pending.
                            coroutineContext.ensureActive()
                            val source = opened.source
                            while (coroutineContext.isActive) {
                                val raw = source.readUtf8Line() ?: break
                                coroutineContext.ensureActive()
                                val line = LogcatParser.parseLine(raw)
                                val updated = _buffer.value.append(line)
                                _buffer.value = updated
                                if (raw.contains(CRASH_MARKER)) {
                                    _crashLineIndex.value = updated.lines.size - 1
                                }
                            }
                        } finally {
                            runCatching { ownedStream.getAndSet(null)?.close() }
                        }
                    }
                }
            } finally {
                if (stream === ownedStream) {
                    stream = null
                    streamJob = null
                    _isStreaming.value = false
                }
            }
        }
    }

    fun stop() {
        val stoppedJob = streamJob
        streamJob = null
        val stoppedStream = stream
        stream = null
        _isStreaming.value = false
        stoppedJob?.cancel()
        val opened = stoppedStream?.getAndSet(null)
        if (opened != null) {
            streamCloseScope.launch { runCatching { opened.close() } }
        }
    }

    fun clear() {
        _buffer.value = LogRingBuffer(CAPACITY)
        _crashLineIndex.value = null
    }

    fun setLevelFilter(level: Char?) {
        _levelFilter.value = level
    }

    fun setTagFilter(filter: String) {
        _tagFilter.value = filter
    }

    /** Comutarea „doar erori” reporneste stream-ul cu noul argument. */
    fun setErrorsOnly(enabled: Boolean) {
        if (_errorsOnly.value == enabled) return
        _errorsOnly.value = enabled
        if (_isStreaming.value) {
            stop()
            start()
        }
    }

    fun consumeCrashSignal() {
        _crashLineIndex.value = null
    }

    fun clearNotConnected() {
        _notConnected.value = false
    }

    /** Liniile de afisat dupa filtrul de nivel si de tag/mesaj (client-side). */
    fun visibleLines(): List<LogLine> {
        val level = _levelFilter.value
        val tag = _tagFilter.value.trim().lowercase()
        return _buffer.value.lines.filter { line ->
            (level == null || line.level == level) &&
                (tag.isEmpty() ||
                    line.tag.lowercase().contains(tag) ||
                    line.message.lowercase().contains(tag))
        }
    }

    fun onDisconnected() {
        stop()
        _notConnected.value = false
    }

    override fun onCleared() {
        super.onCleared()
        stop()
    }

    companion object {
        const val CAPACITY = 2000
        private const val CRASH_MARKER = "FATAL EXCEPTION"
    }
}
