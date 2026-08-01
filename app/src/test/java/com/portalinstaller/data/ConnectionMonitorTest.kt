package com.portalinstaller.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionMonitorTest {

    private var clock = 0L
    private fun monitor() = ConnectionMonitor(
        backoff = ReconnectBackoff(baseMs = 2_000, maxMs = 30_000),
        heartbeatIntervalMs = 20_000,
        now = { clock }
    )

    @Test
    fun `stays quiet while pings succeed`() = runTest {
        val monitor = monitor()
        val signals = mutableListOf<MonitorSignal>()

        monitor.start(this, ping = { true }, reconnect = { true }) { signals += it }
        advanceTimeBy(65_000) // three heartbeats
        monitor.stop()

        assertTrue(signals.isEmpty())
        assertTrue(monitor.events.value.isEmpty())
    }

    @Test
    fun `emits Lost then reconnects after backoff`() = runTest {
        val monitor = monitor()
        val signals = mutableListOf<MonitorSignal>()
        var pingResult = true
        var reconnectAttempts = 0

        monitor.start(
            this,
            ping = { pingResult },
            reconnect = {
                reconnectAttempts++
                // Se vindecă la a doua reconectare.
                reconnectAttempts >= 2
            }
        ) { signals += it }

        // Primul heartbeat: sănătos. Al doilea: pică.
        pingResult = false
        advanceTimeBy(20_001)
        // Prima reconectare eșuează → planifică 2s; a doua reușește.
        advanceTimeBy(2_001)
        monitor.stop()

        assertTrue(signals.any { it is MonitorSignal.Lost })
        assertTrue(signals.any { it is MonitorSignal.Reconnecting && it.attempt == 1 && it.nextRetryMs == 2_000L })
        assertEquals(MonitorSignal.Reconnected, signals.last())
        assertEquals(ConnectionEventKind.RECONNECTED, monitor.events.value.first().kind)
    }

    @Test
    fun `caps the event log at MAX_EVENTS`() {
        val monitor = monitor()
        repeat(ConnectionMonitor.MAX_EVENTS + 10) { i ->
            clock = i.toLong()
            monitor.logEvent(ConnectionEventKind.HEALTHY)
        }
        assertEquals(ConnectionMonitor.MAX_EVENTS, monitor.events.value.size)
        // Cea mai nouă intrare este prima (cel mai mare timestamp).
        assertEquals(
            (ConnectionMonitor.MAX_EVENTS + 9).toLong(),
            monitor.events.value.first().timestamp
        )
    }
}
