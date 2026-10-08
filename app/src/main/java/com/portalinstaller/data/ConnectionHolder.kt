package com.portalinstaller.data

import dadb.Dadb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job

/** Aruncată de [ConnectionHolder.require] când nu există o conexiune activă. */
class NotConnectedException : IllegalStateException("Nu există o conexiune ADB activă.")

/** Immutable operation target. A reused transport still gets a new generation after reconnect. */
data class AdbSession(val dadb: Dadb, val generation: Long, val label: String?)

/**
 * Sursa unică de adevăr pentru conexiunea ADB curentă.
 *
 * Înainte, fiecare copie a `dadb` trăia în PortalViewModel; acum toate
 * ViewModel-urile de funcționalitate împart acest holder (via AppContainer),
 * astfel încât Apps/Backup/Photos văd aceeași conexiune stabilită de Connection.
 * Închiderea conexiunii vechi la înlocuire se face pe IO, în afara firului UI.
 */
class ConnectionHolder {

    // Un singur scope la nivel de aplicatie pentru inchiderile in fundal, in loc
    // sa cream un CoroutineScope orfan (fara owner) la fiecare set(). Holder-ul e
    // singleton in AppContainer, deci scope-ul traieste cat procesul.
    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _dadb = MutableStateFlow<Dadb?>(null)
    val dadb: StateFlow<Dadb?> = _dadb.asStateFlow()

    private val _deviceLabel = MutableStateFlow<String?>(null)
    val deviceLabel: StateFlow<String?> = _deviceLabel.asStateFlow()
    private var generation = 0L
    private val _session = MutableStateFlow<AdbSession?>(null)
    val session: StateFlow<AdbSession?> = _session.asStateFlow()

    /** Instalează o nouă conexiune, închizând în fundal pe cea anterioară (dacă diferă). */
    @Synchronized
    fun set(dadb: Dadb?, label: String?) {
        val previous = _dadb.value
        if (previous !== dadb || _session.value == null) generation++
        _session.value = dadb?.let { AdbSession(it, generation, label) }
        _dadb.value = dadb
        _deviceLabel.value = label
        if (previous != null && previous !== dadb) {
            closeScope.launch {
                runCatching { previous.close() }
            }
        }
    }

    /** Închide și golește conexiunea curentă. */
    fun clear() = set(null, null)
    fun capture(): AdbSession? = _session.value
    fun requireSession(): AdbSession = capture() ?: throw NotConnectedException()
    fun isCurrent(session: AdbSession): Boolean =
        _session.value?.let { it.generation == session.generation && it.dadb === session.dadb } == true

    @Synchronized
    fun clearIfCurrent(session: AdbSession): Boolean {
        if (!isCurrent(session)) return false
        clear()
        return true
    }

    fun ensureCurrent(session: AdbSession) {
        if (!isCurrent(session)) throw CancellationException("ADB session changed")
    }

    /** Owned work is cancelled on loss/replacement even when a screen has no UI disconnect hook. */
    fun launchForSession(
        scope: CoroutineScope, origin: AdbSession,
        block: suspend CoroutineScope.() -> Unit
    ): Job = scope.launch {
        ensureCurrent(origin)
        coroutineScope {
            val owner = coroutineContext.job
            val watcher = launch {
                session.collect { if (!isCurrent(origin)) owner.cancel(CancellationException("ADB session changed")) }
            }
            try { block() } finally { watcher.cancel() }
        }
    }

    /** Conexiunea curentă sau [NotConnectedException] dacă lipsește. */
    fun require(): Dadb = _dadb.value ?: throw NotConnectedException()
}
