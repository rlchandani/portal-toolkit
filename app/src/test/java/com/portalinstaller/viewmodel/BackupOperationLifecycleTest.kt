/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.viewmodel

import com.portalinstaller.data.ConnectionHolder
import com.portalinstaller.data.PortalRestoreSummary
import dadb.Dadb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
class BackupOperationLifecycleTest {
    private fun device(): Dadb =
        Proxy.newProxyInstance(Dadb::class.java.classLoader, arrayOf(Dadb::class.java)) { proxy, method, args ->
            when (method.name) {
                "close" -> null
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "SyntheticBackupPortal"
                else -> error("Lifecycle tests must not perform device operations")
            }
        } as Dadb

    @Test fun `automatic session loss clears busy restore and permits work after reconnect`() = runTest {
        val holder = ConnectionHolder().apply { set(device(), "origin") }
        val state = MutableStateFlow<BackupState>(BackupState.Idle)
        val lifecycle = BackupRestoreLifecycle(holder, backgroundScope, state)
        val old = lifecycle.launch(holder.requireSession()) { awaitCancellation() }
        testScheduler.runCurrent()
        assertTrue(state.value is BackupState.Restoring)

        holder.clear()
        testScheduler.runCurrent()

        assertTrue(old.isCancelled)
        assertEquals(BackupState.Idle, state.value)
        holder.set(device(), "reconnected")
        val next = lifecycle.launch(holder.requireSession()) { ensureCurrent ->
            ensureCurrent()
            state.value = BackupState.Success("New restore completed")
        }
        testScheduler.runCurrent()
        assertTrue(next.isCompleted)
        assertEquals(BackupState.Success("New restore completed"), state.value)
    }

    @Test fun `device replacement clears busy restore without a disconnect hook`() = runTest {
        val holder = ConnectionHolder().apply { set(device(), "origin") }
        val state = MutableStateFlow<BackupState>(BackupState.Idle)
        val lifecycle = BackupRestoreLifecycle(holder, backgroundScope, state)
        val old = lifecycle.launch(holder.requireSession()) { awaitCancellation() }
        testScheduler.runCurrent()

        holder.set(device(), "replacement")
        testScheduler.runCurrent()

        assertTrue(old.isCancelled)
        assertEquals(BackupState.Idle, state.value)
    }

    @Test fun `reconnecting the same transport still cancels its old restore generation`() = runTest {
        val transport = device()
        val holder = ConnectionHolder().apply { set(transport, "origin") }
        val origin = holder.requireSession()
        val state = MutableStateFlow<BackupState>(BackupState.Idle)
        val lifecycle = BackupRestoreLifecycle(holder, backgroundScope, state)
        val old = lifecycle.launch(origin) { awaitCancellation() }
        testScheduler.runCurrent()

        holder.clear()
        holder.set(transport, "reconnected")
        testScheduler.runCurrent()

        assertTrue(old.isCancelled)
        assertNotEquals(origin.generation, holder.requireSession().generation)
        assertEquals(BackupState.Idle, state.value)
    }

    @Test fun `explicit cancellation resets busy state immediately and permits another restore`() = runTest {
        val holder = ConnectionHolder().apply { set(device(), "origin") }
        val state = MutableStateFlow<BackupState>(BackupState.Idle)
        val lifecycle = BackupRestoreLifecycle(holder, backgroundScope, state)
        val old = lifecycle.launch(holder.requireSession()) { awaitCancellation() }
        testScheduler.runCurrent()

        lifecycle.cancel()

        assertEquals(BackupState.Idle, state.value)
        val next = lifecycle.launch(holder.requireSession()) { awaitCancellation() }
        testScheduler.runCurrent()
        assertTrue(old.isCancelled)
        assertTrue(next.isActive)
        assertTrue(state.value is BackupState.Restoring)
    }

    @Test fun `replacement releases busy state before blocked old work finishes and its late completion cannot clear newer work`() = runTest {
        val holder = ConnectionHolder().apply { set(device(), "origin") }
        val state = MutableStateFlow<BackupState>(BackupState.Idle)
        val lifecycle = BackupRestoreLifecycle(holder, backgroundScope, state)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var oldPublished = false
        val old = lifecycle.launch(holder.requireSession()) { ensureCurrent ->
            started.complete(Unit)
            withContext(NonCancellable) { release.await() }
            ensureCurrent()
            oldPublished = true
            state.value = BackupState.Success("Stale result")
        }
        started.await()

        holder.set(device(), "replacement")
        testScheduler.runCurrent()
        val oldCompletedBeforeRelease = old.isCompleted
        val stateAfterReplacement = state.value
        val newerState = BackupState.Restoring(1, 2, "com.example.new")
        val next = lifecycle.launch(holder.requireSession()) {
            state.value = newerState
            awaitCancellation()
        }
        testScheduler.runCurrent()

        release.complete(Unit)
        testScheduler.runCurrent()

        assertFalse("The blocked old operation must still be in flight", oldCompletedBeforeRelease)
        assertEquals(BackupState.Idle, stateAfterReplacement)
        assertTrue(old.isCancelled)
        assertTrue(old.isCompleted)
        assertFalse(oldPublished)
        assertTrue(next.isActive)
        assertEquals(newerState, state.value)
    }

    @Test fun `late completion after explicit cancellation cannot clear a newer restore on the same session`() = runTest {
        val holder = ConnectionHolder().apply { set(device(), "origin") }
        val state = MutableStateFlow<BackupState>(BackupState.Idle)
        val lifecycle = BackupRestoreLifecycle(holder, backgroundScope, state)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val old = lifecycle.launch(holder.requireSession()) { ensureCurrent ->
            started.complete(Unit)
            withContext(NonCancellable) { release.await() }
            ensureCurrent()
            state.value = BackupState.Success("Stale result")
        }
        started.await()
        lifecycle.cancel()
        assertEquals(BackupState.Idle, state.value)
        val newerState = BackupState.Restoring(2, 3, "com.example.current")
        val next = lifecycle.launch(holder.requireSession()) {
            state.value = newerState
            awaitCancellation()
        }
        testScheduler.runCurrent()

        release.complete(Unit)
        testScheduler.runCurrent()

        assertTrue(old.isCancelled)
        assertTrue(old.isCompleted)
        assertTrue(next.isActive)
        assertEquals(newerState, state.value)
    }

    @Test fun `cancellation before the restore body starts does not leave busy state`() = runTest {
        val holder = ConnectionHolder().apply { set(device(), "origin") }
        val state = MutableStateFlow<BackupState>(BackupState.Idle)
        val lifecycle = BackupRestoreLifecycle(holder, backgroundScope, state)
        var executed = false
        val job = lifecycle.launch(holder.requireSession()) { executed = true }

        holder.clear()
        testScheduler.runCurrent()

        assertTrue(job.isCancelled)
        assertFalse(executed)
        assertEquals(BackupState.Idle, state.value)
    }

    @Test fun `operation cancellation while session stays current clears busy state`() = runTest {
        val holder = ConnectionHolder().apply { set(device(), "origin") }
        val state = MutableStateFlow<BackupState>(BackupState.Idle)
        val lifecycle = BackupRestoreLifecycle(holder, backgroundScope, state)
        val job = lifecycle.launch(holder.requireSession()) { throw CancellationException("Restore cancelled") }
        testScheduler.runCurrent()

        assertTrue(job.isCancelled)
        assertEquals(BackupState.Idle, state.value)
    }

    @Test fun `partial restore completion retains failure and refreshes confirmed mutations once`() = runTest {
        val holder = ConnectionHolder().apply { set(device(), "origin") }
        val state = MutableStateFlow<BackupState>(BackupState.Idle)
        val lifecycle = BackupRestoreLifecycle(holder, backgroundScope, state)
        val origin = holder.requireSession()
        val history = mutableListOf<Pair<String, Boolean>>()
        var refreshes = 0
        val job = lifecycle.launch(origin) { ensureCurrent ->
            ensureCurrent()
            completeBackupRestore(
                PortalRestoreSummary(1, listOf("com.example.rejected"), 0),
                "One installed; one failed", holder, origin,
                publishOutcome = { state.value = it },
                recordHistory = { message, success -> history += message to success },
                onRestored = { refreshes++ },
            )
        }
        testScheduler.runCurrent()

        assertTrue(job.isCompleted)
        assertFalse(job.isCancelled)
        assertEquals(BackupState.Failed("One installed; one failed"), state.value)
        assertEquals(listOf("One installed; one failed" to false), history)
        assertEquals(1, refreshes)
    }
}
