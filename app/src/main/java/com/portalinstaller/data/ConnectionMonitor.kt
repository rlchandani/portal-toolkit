package com.portalinstaller.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Tipul unei intrări din jurnalul de conexiune, pentru pictograma/culoarea din UI. */
enum class ConnectionEventKind {
    CONNECTED_WIFI, CONNECTED_USB, HEALTHY, LOST, RECONNECTING, RECONNECTED, DISCONNECTED
}

/**
 * O intrare din jurnalul de conexiune afișat în DevicesTab. Nu ține text
 * localizat — UI-ul redă eticheta din [kind] (plus [attempt] pentru
 * reconectări), ca să funcționeze în ambele limbi fără a da Context motorului.
 */
data class ConnectionEvent(
    val timestamp: Long,
    val kind: ConnectionEventKind,
    val attempt: Int? = null
)

/** Semnal emis către ViewModel când starea heartbeat-ului se schimbă. */
sealed class MonitorSignal {
    data object Healthy : MonitorSignal()
    data object Lost : MonitorSignal()
    data class Reconnecting(val attempt: Int, val nextRetryMs: Long) : MonitorSignal()
    data object Reconnected : MonitorSignal()
}

/**
 * Motorul de sănătate a conexiunii: trimite periodic un heartbeat (`echo ok`)
 * și, la eșec, reconectează cu backoff exponențial până reușește sau până la
 * oprire manuală. Este independent de dadb/ViewModel — primește [ping] și
 * [reconnect] ca funcții suspendabile, deci se poate testa determinist cu
 * timp virtual.
 */
class ConnectionMonitor(
    private val backoff: ReconnectBackoff = ReconnectBackoff(),
    private val heartbeatIntervalMs: Long = 20_000,
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    private val _events = MutableStateFlow<List<ConnectionEvent>>(emptyList())
    val events: StateFlow<List<ConnectionEvent>> = _events.asStateFlow()

    private var job: Job? = null

    /**
     * Pornește bucla de heartbeat pe [scope]. [ping] întoarce true dacă
     * dispozitivul răspunde; [reconnect] încearcă o singură reconectare și
     * întoarce true la succes. [onSignal] primește tranzițiile de stare.
     */
    fun start(
        scope: CoroutineScope,
        ping: suspend () -> Boolean,
        reconnect: suspend () -> Boolean,
        onSignal: (MonitorSignal) -> Unit
    ) {
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                delay(heartbeatIntervalMs)
                if (!isActive) break

                val alive = runCatching { ping() }.getOrDefault(false)
                if (alive) continue

                append(ConnectionEventKind.LOST)
                onSignal(MonitorSignal.Lost)
                runReconnectLoop(reconnect, onSignal)
            }
        }
    }

    private suspend fun runReconnectLoop(
        reconnect: suspend () -> Boolean,
        onSignal: (MonitorSignal) -> Unit
    ) {
        var attempt = 1
        while (currentCoroutineContext().isActive) {
            val recovered = runCatching { reconnect() }.getOrDefault(false)
            if (recovered) {
                append(ConnectionEventKind.RECONNECTED)
                onSignal(MonitorSignal.Reconnected)
                return
            }
            val nextRetryMs = backoff.delayFor(attempt)
            append(ConnectionEventKind.RECONNECTING, attempt)
            onSignal(MonitorSignal.Reconnecting(attempt, nextRetryMs))
            delay(nextRetryMs)
            attempt++
        }
    }

    /** Oprește heartbeat-ul (deconectare manuală). */
    fun stop() {
        job?.cancel()
        job = null
    }

    /** Adaugă manual o intrare în jurnal (ex. conectat/deconectat din ViewModel). */
    fun logEvent(kind: ConnectionEventKind) = append(kind)

    fun clearLog() {
        _events.value = emptyList()
    }

    private fun append(kind: ConnectionEventKind, attempt: Int? = null) {
        val entry = ConnectionEvent(now(), kind, attempt)
        // Cele mai noi primele, plafonat la MAX_EVENTS.
        _events.value = (listOf(entry) + _events.value).take(MAX_EVENTS)
    }

    companion object {
        const val MAX_EVENTS = 50
    }
}
