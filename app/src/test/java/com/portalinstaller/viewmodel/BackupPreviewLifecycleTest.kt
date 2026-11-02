/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.viewmodel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class BackupPreviewLifecycleTest {
    @Test fun `preview enters busy state and preserves completed outcome`() = runTest {
        val state = MutableStateFlow<BackupState>(BackupState.Idle)
        val owner = BackupPreviewLifecycle(backgroundScope, state)
        val job = owner.launch { current -> current(); state.value = BackupState.Success("generated") }
        assertEquals(BackupState.Previewing, state.value)
        testScheduler.runCurrent()
        assertTrue(job.isCompleted)
        assertEquals(BackupState.Success("generated"), state.value)
    }

    @Test fun `explicit cancellation releases busy state immediately`() = runTest {
        val state = MutableStateFlow<BackupState>(BackupState.Idle)
        val owner = BackupPreviewLifecycle(backgroundScope, state)
        val job = owner.launch { awaitCancellation() }
        testScheduler.runCurrent()
        owner.cancel()
        assertEquals(BackupState.Idle, state.value)
        assertTrue(job.isCancelled)
    }

    @Test fun `blocked cancelled work cannot publish after it is released`() = runTest {
        val state = MutableStateFlow<BackupState>(BackupState.Idle)
        val owner = BackupPreviewLifecycle(backgroundScope, state)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var published = false
        val job = owner.launch { current ->
            started.complete(Unit)
            withContext(NonCancellable) { release.await() }
            current()
            published = true
            state.value = BackupState.Success("obsolete")
        }
        started.await()
        owner.cancel()
        assertFalse(job.isCompleted)
        assertEquals(BackupState.Idle, state.value)
        release.complete(Unit)
        testScheduler.runCurrent()
        assertTrue(job.isCompleted)
        assertFalse(published)
        assertEquals(BackupState.Idle, state.value)
    }

    @Test fun `old completion cannot clear a newer busy preview`() = runTest {
        val state = MutableStateFlow<BackupState>(BackupState.Idle)
        val owner = BackupPreviewLifecycle(backgroundScope, state)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val old = owner.launch { current ->
            started.complete(Unit)
            withContext(NonCancellable) { release.await() }
            current()
            state.value = BackupState.Success("obsolete")
        }
        started.await()
        owner.cancel()
        val next = owner.launch { awaitCancellation() }
        testScheduler.runCurrent()
        release.complete(Unit)
        testScheduler.runCurrent()
        assertTrue(old.isCompleted)
        assertTrue(next.isActive)
        assertEquals(BackupState.Previewing, state.value)
        owner.cancel()
    }

    @Test fun `old result cannot replace a newer completed preview`() = runTest {
        val state = MutableStateFlow<BackupState>(BackupState.Idle)
        val owner = BackupPreviewLifecycle(backgroundScope, state)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val old = owner.launch { current ->
            started.complete(Unit)
            withContext(NonCancellable) { release.await() }
            current()
            state.value = BackupState.Success("old")
        }
        started.await()
        owner.cancel()
        owner.launch { current -> current(); state.value = BackupState.Success("new") }
        testScheduler.runCurrent()
        release.complete(Unit)
        testScheduler.runCurrent()
        assertTrue(old.isCompleted)
        assertEquals(BackupState.Success("new"), state.value)
    }

    @Test fun `cancellation before the coroutine starts clears only its own preview`() = runTest {
        val state = MutableStateFlow<BackupState>(BackupState.Idle)
        val owner = BackupPreviewLifecycle(backgroundScope, state)
        var ran = false
        val job = owner.launch { ran = true }
        job.cancel()
        testScheduler.runCurrent()
        assertFalse(ran)
        assertEquals(BackupState.Idle, state.value)
    }
}
