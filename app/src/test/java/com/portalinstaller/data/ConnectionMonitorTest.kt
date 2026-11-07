package com.portalinstaller.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionMonitorTest {

    private var clock = 0L
    private fun monitor() = ConnectionMonitor(
        backoff = ReconnectBackoff(baseMs = 2_000, maxMs = 30_000),
        heartbeatIntervalMs = 20_000,
        failureThreshold = 3,
        failureRetryMs = 2_000,
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
    fun `tolerates isolated ping blips below the failure threshold`() = runTest {
        val monitor = monitor()
        val signals = mutableListOf<MonitorSignal>()
        // Alterneaza esec/succes: nu se atinge niciodata 3 esecuri consecutive.
        var tick = 0
        monitor.start(
            this,
            ping = { tick++ % 2 == 0 }, // esec, succes, esec, succes...
            reconnect = { true }
        ) { signals += it }
        advanceTimeBy(200_001) // multe heartbeat-uri
        monitor.stop()

        // Un singur blip nu trebuie sa declanseze niciodata pierderea conexiunii.
        assertTrue(signals.none { it is MonitorSignal.Lost })
        assertTrue(monitor.events.value.none { it.kind == ConnectionEventKind.LOST })
    }

    @Test
    fun `declares Lost only after threshold consecutive failures`() = runTest {
        val monitor = monitor()
        val signals = mutableListOf<MonitorSignal>()
        var pingResult = true
        monitor.start(
            this,
            ping = { pingResult },
            reconnect = { true }
        ) { signals += it }

        // Primul heartbeat sanatos.
        advanceTimeBy(20_001)
        assertTrue(signals.none { it is MonitorSignal.Lost })

        // Acum picam. Primele doua esecuri (la interval failureRetryMs) NU declara pierdut.
        pingResult = false
        advanceTimeBy(20_001) // esec #1
        assertTrue(signals.none { it is MonitorSignal.Lost })
        advanceTimeBy(2_001)  // esec #2 (dupa failureRetryMs)
        assertTrue(signals.none { it is MonitorSignal.Lost })
        // Al treilea esec consecutiv → pierdut.
        advanceTimeBy(2_001)  // esec #3
        monitor.stop()

        assertTrue(signals.any { it is MonitorSignal.Lost })
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

        // Picam: sunt necesare 3 esecuri consecutive (20s + 2s + 2s) ca sa declaram pierdut.
        pingResult = false
        advanceTimeBy(20_001) // esec #1
        advanceTimeBy(2_001)  // esec #2
        advanceTimeBy(2_001)  // esec #3 → Lost, incepe reconectarea
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

    @Test
    fun `stop during third suspended ping preserves disconnected event`() = runTest {
        val monitor = monitor()
        val signals = mutableListOf<MonitorSignal>()
        var pingCalls = 0
        try {
            monitor.start(
                this,
                ping = {
                    pingCalls++
                    if (pingCalls < 3) false else awaitCancellation()
                },
                reconnect = { true }
            ) { signals += it }
            advanceTimeBy(24_001) // 20s heartbeat, then two 2s confirmations.
            assertEquals(3, pingCalls)

            monitor.stop()
            monitor.logEvent(ConnectionEventKind.DISCONNECTED)
            runCurrent()

            assertTrue(signals.isEmpty())
            assertEquals(
                listOf(ConnectionEventKind.DISCONNECTED),
                monitor.events.value.map { it.kind }
            )
        } finally {
            monitor.stop()
            runCurrent()
        }
    }

    @Test
    fun `stop during suspended reconnect emits no later transition`() = runTest {
        val monitor = monitor()
        val signals = mutableListOf<MonitorSignal>()
        var reconnectCalls = 0
        try {
            monitor.start(
                this,
                ping = { false },
                reconnect = { reconnectCalls++; awaitCancellation() }
            ) { signals += it }
            advanceTimeBy(24_001)
            assertEquals(1, reconnectCalls)
            assertEquals(listOf(MonitorSignal.Lost), signals)
            val eventsBeforeStop = monitor.events.value

            monitor.stop()
            runCurrent()

            assertEquals(listOf(MonitorSignal.Lost), signals)
            assertEquals(eventsBeforeStop, monitor.events.value)
        } finally {
            monitor.stop()
            runCurrent()
        }
    }

    @Test
    fun `stopped ping returning false after catching cancellation stays silent`() = runTest {
        val monitor = monitor()
        val signals = mutableListOf<MonitorSignal>()
        var pingCalls = 0
        try {
            monitor.start(
                this,
                ping = {
                    pingCalls++
                    if (pingCalls < 3) false else {
                        try {
                            awaitCancellation()
                        } catch (_: CancellationException) {
                            false
                        }
                    }
                },
                reconnect = { true }
            ) { signals += it }
            advanceTimeBy(24_001)
            assertEquals(3, pingCalls)

            monitor.stop()
            runCurrent()

            assertTrue(signals.isEmpty())
            assertTrue(monitor.events.value.isEmpty())
        } finally {
            monitor.stop()
            runCurrent()
        }
    }

    @Test
    fun `stopped ping returning true after catching cancellation stays silent`() = runTest {
        val monitor = monitor()
        val signals = mutableListOf<MonitorSignal>()
        var pingCalls = 0
        try {
            monitor.start(
                this,
                ping = {
                    pingCalls++
                    if (pingCalls < 3) false else {
                        try {
                            awaitCancellation()
                        } catch (_: CancellationException) {
                            true
                        }
                    }
                },
                reconnect = { true }
            ) { signals += it }
            advanceTimeBy(24_001)
            assertEquals(3, pingCalls)

            monitor.stop()
            runCurrent()
            advanceTimeBy(20_001)

            assertEquals(3, pingCalls)
            assertTrue(signals.isEmpty())
            assertTrue(monitor.events.value.isEmpty())
        } finally {
            monitor.stop()
            runCurrent()
        }
    }

    @Test
    fun `stopped reconnect returning false after catching cancellation stays silent`() = runTest {
        val monitor = monitor()
        val signals = mutableListOf<MonitorSignal>()
        var reconnectCalls = 0
        try {
            monitor.start(
                this,
                ping = { false },
                reconnect = {
                    reconnectCalls++
                    try {
                        awaitCancellation()
                    } catch (_: CancellationException) {
                        false
                    }
                }
            ) { signals += it }
            advanceTimeBy(24_001)
            assertEquals(1, reconnectCalls)
            val signalsBeforeStop = signals.toList()
            val eventsBeforeStop = monitor.events.value

            monitor.stop()
            runCurrent()

            assertEquals(signalsBeforeStop, signals)
            assertEquals(eventsBeforeStop, monitor.events.value)
        } finally {
            monitor.stop()
            runCurrent()
        }
    }

    @Test
    fun `stopped reconnect returning true after catching cancellation stays silent`() = runTest {
        val monitor = monitor()
        val signals = mutableListOf<MonitorSignal>()
        var reconnectCalls = 0
        try {
            monitor.start(
                this,
                ping = { false },
                reconnect = {
                    reconnectCalls++
                    try {
                        awaitCancellation()
                    } catch (_: CancellationException) {
                        true
                    }
                }
            ) { signals += it }
            advanceTimeBy(24_001)
            assertEquals(1, reconnectCalls)
            val signalsBeforeStop = signals.toList()
            val eventsBeforeStop = monitor.events.value

            monitor.stop()
            runCurrent()

            assertEquals(signalsBeforeStop, signals)
            assertEquals(eventsBeforeStop, monitor.events.value)
        } finally {
            monitor.stop()
            runCurrent()
        }
    }

    @Test
    fun `new run excludes canceled old third ping transitions`() = runTest {
        val monitor = monitor()
        val oldSignals = mutableListOf<MonitorSignal>()
        val newSignals = mutableListOf<MonitorSignal>()
        var oldPingCalls = 0
        var newPingCalls = 0
        try {
            monitor.start(
                this,
                ping = {
                    oldPingCalls++
                    if (oldPingCalls < 3) false else awaitCancellation()
                },
                reconnect = { true }
            ) { oldSignals += it }
            advanceTimeBy(24_001)
            assertEquals(3, oldPingCalls)

            monitor.start(
                this,
                ping = { newPingCalls++; true },
                reconnect = { true }
            ) { newSignals += it }
            runCurrent()
            advanceTimeBy(20_001)

            assertEquals(1, newPingCalls)
            assertTrue(oldSignals.isEmpty())
            assertTrue(newSignals.isEmpty())
            assertTrue(monitor.events.value.isEmpty())
        } finally {
            monitor.stop()
            runCurrent()
        }
    }

    @Test
    fun `new run excludes canceled old reconnect success`() = runTest {
        val monitor = monitor()
        val oldSignals = mutableListOf<MonitorSignal>()
        val newSignals = mutableListOf<MonitorSignal>()
        var reconnectCalls = 0
        var newPingCalls = 0
        try {
            monitor.start(
                this,
                ping = { false },
                reconnect = {
                    reconnectCalls++
                    try {
                        awaitCancellation()
                    } catch (_: CancellationException) {
                        true
                    }
                }
            ) { oldSignals += it }
            advanceTimeBy(24_001)
            assertEquals(1, reconnectCalls)
            assertEquals(listOf(MonitorSignal.Lost), oldSignals)
            val eventsBeforeReplacement = monitor.events.value

            monitor.start(
                this,
                ping = { newPingCalls++; true },
                reconnect = { true }
            ) { newSignals += it }
            runCurrent()
            advanceTimeBy(20_001)

            assertEquals(1, newPingCalls)
            assertEquals(listOf(MonitorSignal.Lost), oldSignals)
            assertTrue(newSignals.isEmpty())
            assertEquals(eventsBeforeReplacement, monitor.events.value)
        } finally {
            monitor.stop()
            runCurrent()
        }
    }

    @Test
    fun `explicit ping cancellation ends active run without Lost`() = runTest {
        val monitor = monitor()
        val signals = mutableListOf<MonitorSignal>()
        var pingCalls = 0
        var cancelledWhileActive = false
        try {
            monitor.start(
                this,
                ping = {
                    pingCalls++
                    if (pingCalls < 3) false else {
                        cancelledWhileActive = currentCoroutineContext().isActive
                        throw CancellationException("ping cancelled")
                    }
                },
                reconnect = { true }
            ) { signals += it }
            advanceTimeBy(24_001)

            assertTrue(cancelledWhileActive)
            assertTrue(signals.isEmpty())
            assertTrue(monitor.events.value.isEmpty())
        } finally {
            monitor.stop()
            runCurrent()
        }
    }

    @Test
    fun `explicit reconnect cancellation ends active run without retry`() = runTest {
        val monitor = monitor()
        val signals = mutableListOf<MonitorSignal>()
        var cancelledWhileActive = false
        try {
            monitor.start(
                this,
                ping = { false },
                reconnect = {
                    cancelledWhileActive = currentCoroutineContext().isActive
                    throw CancellationException("reconnect cancelled")
                }
            ) { signals += it }
            advanceTimeBy(24_001)

            assertTrue(cancelledWhileActive)
            assertEquals(listOf(MonitorSignal.Lost), signals)
            assertEquals(
                listOf(ConnectionEventKind.LOST),
                monitor.events.value.map { it.kind }
            )
        } finally {
            monitor.stop()
            runCurrent()
        }
    }

    @Test
    fun `ordinary ping exception still reaches Lost and recovers`() = runTest {
        val monitor = monitor()
        val signals = mutableListOf<MonitorSignal>()
        var pingCalls = 0
        try {
            monitor.start(
                this,
                ping = { pingCalls++; throw IOException("ping failed") },
                reconnect = { true }
            ) { signals += it }
            advanceTimeBy(24_001)

            assertEquals(3, pingCalls)
            assertEquals(listOf(MonitorSignal.Lost, MonitorSignal.Reconnected), signals)
            assertEquals(
                listOf(ConnectionEventKind.RECONNECTED, ConnectionEventKind.LOST),
                monitor.events.value.map { it.kind }
            )
        } finally {
            monitor.stop()
            runCurrent()
        }
    }

    @Test
    fun `ordinary reconnect exception still retries and recovers`() = runTest {
        val monitor = monitor()
        val signals = mutableListOf<MonitorSignal>()
        var reconnectCalls = 0
        try {
            monitor.start(
                this,
                ping = { false },
                reconnect = {
                    reconnectCalls++
                    if (reconnectCalls == 1) throw IOException("reconnect failed") else true
                }
            ) { signals += it }
            advanceTimeBy(26_001)

            assertEquals(2, reconnectCalls)
            assertEquals(
                listOf(MonitorSignal.Lost, MonitorSignal.Reconnecting(1, 2_000), MonitorSignal.Reconnected),
                signals
            )
            assertEquals(
                listOf(
                    ConnectionEventKind.RECONNECTED,
                    ConnectionEventKind.RECONNECTING,
                    ConnectionEventKind.LOST
                ),
                monitor.events.value.map { it.kind }
            )
        } finally {
            monitor.stop()
            runCurrent()
        }
    }
}
