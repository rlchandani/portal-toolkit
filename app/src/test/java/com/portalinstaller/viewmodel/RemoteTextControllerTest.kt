/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.viewmodel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteTextControllerTest {
    @Test fun pendingSendSurvivesConsumerReplacementAndCannotBeSentTwice() = runTest {
        val acknowledgement = CompletableDeferred<Boolean>()
        var calls = 0
        val controller = RemoteTextController(backgroundScope) { key, text ->
            assertEquals("portal-a:1", key)
            assertEquals("Synthetic draft", text)
            calls++
            acknowledgement.await()
        }
        controller.updateSession("portal-a:1", true)
        controller.open("portal-a:1")
        controller.edit("portal-a:1", "Synthetic draft")
        val request = controller.send("portal-a:1")!!
        // A recreated UI consumes the same controller, with no new send coroutine.
        val replacementConsumer = controller.state
        assertTrue(replacementConsumer.value.sending)
        assertNull(controller.send("portal-a:1"))
        controller.dismiss("portal-a:1")
        controller.open("portal-a:1")
        assertNull(controller.send("portal-a:1"))
        assertEquals(1, calls)
        acknowledgement.complete(true)
        request.join()
        assertFalse(replacementConsumer.value.sending)
        assertFalse(replacementConsumer.value.keyboardOpen)
        assertEquals("", replacementConsumer.value.draft)
        assertFalse(replacementConsumer.value.error)
    }

    @Test fun failedAcknowledgementKeepsDraftAndAllowsOneRetry() = runTest {
        val first = CompletableDeferred<Boolean>()
        var calls = 0
        val controller = RemoteTextController(backgroundScope) { _, _ ->
            if (++calls == 1) first.await() else true
        }
        controller.updateSession("portal-a:1", true)
        controller.open("portal-a:1")
        controller.edit("portal-a:1", "Synthetic retry")
        val request = controller.send("portal-a:1")!!
        first.complete(false)
        request.join()
        assertEquals("Synthetic retry", controller.state.value.draft)
        assertTrue(controller.state.value.keyboardOpen)
        assertTrue(controller.state.value.error)
        assertFalse(controller.state.value.sending)
        controller.send("portal-a:1")!!.join()
        assertEquals(2, calls)
        assertEquals("", controller.state.value.draft)
        assertFalse(controller.state.value.keyboardOpen)
    }

    @Test fun acknowledgementNeverClearsANewerDraftEvenIfItsTextMatches() = runTest {
        for (newDraft in listOf("New synthetic draft", "Original synthetic draft")) {
            val acknowledgement = CompletableDeferred<Boolean>()
            val controller = RemoteTextController(backgroundScope) { _, _ -> acknowledgement.await() }
            controller.updateSession("portal-a:1", true)
            controller.open("portal-a:1")
            controller.edit("portal-a:1", "Original synthetic draft")
            val request = controller.send("portal-a:1")!!
            controller.edit("portal-a:1", newDraft)
            acknowledgement.complete(true)
            request.join()
            assertEquals(newDraft, controller.state.value.draft)
            assertTrue(controller.state.value.keyboardOpen)
            assertFalse(controller.state.value.sending)
        }
    }

    @Test fun lateSuccessOrFailureCannotChangeAnotherGenerationOrItsPendingSend() = runTest {
        for (oldResult in listOf(true, false)) {
            val oldAck = CompletableDeferred<Boolean>()
            val newAck = CompletableDeferred<Boolean>()
            val controller = RemoteTextController(backgroundScope) { key, _ ->
                if (key == "portal-a:1") oldAck.await() else newAck.await()
            }
            controller.updateSession("portal-a:1", true)
            controller.open("portal-a:1")
            controller.edit("portal-a:1", "Old synthetic draft")
            val oldRequest = controller.send("portal-a:1")!!
            controller.updateSession("portal-b:2", true)
            controller.updateSession("portal-a:3", true)
            controller.open("portal-a:3")
            controller.edit("portal-a:3", "Current synthetic draft")
            val currentRequest = controller.send("portal-a:3")!!
            val expected = controller.state.value
            oldAck.complete(oldResult)
            oldRequest.join()
            assertEquals(expected, controller.state.value)
            assertTrue(controller.state.value.sending)
            assertNull(controller.send("portal-a:3"))
            newAck.complete(true)
            currentRequest.join()
            assertEquals("", controller.state.value.draft)
            assertFalse(controller.state.value.sending)
        }
    }

    @Test fun staleUiCallbacksAndUnconnectedOrBlankDraftsCannotSend() = runTest {
        var calls = 0
        val controller = RemoteTextController(backgroundScope) { _, _ -> calls++; true }
        controller.updateSession("portal-b:2", true)
        controller.open("portal-b:2")
        controller.edit("portal-b:2", "Current synthetic draft")
        val expected = controller.state.value
        controller.edit("portal-a:1", "Stale synthetic draft")
        controller.dismiss("portal-a:1")
        controller.open("portal-a:1")
        assertNull(controller.send("portal-a:1"))
        assertEquals(expected, controller.state.value)
        controller.updateSession("portal-b:2", false)
        assertEquals("Current synthetic draft", controller.state.value.draft)
        assertFalse(controller.state.value.keyboardOpen)
        assertNull(controller.send("portal-b:2"))
        controller.updateSession("portal-b:2", true)
        controller.open("portal-b:2")
        controller.edit("portal-b:2", "  ")
        assertNull(controller.send("portal-b:2"))
        assertEquals(0, calls)
    }

    @Test fun disconnectKeepsPendingOwnershipUntilAcknowledgement() = runTest {
        val acknowledgement = CompletableDeferred<Boolean>()
        val controller = RemoteTextController(backgroundScope) { _, _ -> acknowledgement.await() }
        controller.updateSession("portal-a:1", true)
        controller.open("portal-a:1")
        controller.edit("portal-a:1", "Synthetic draft")
        val request = controller.send("portal-a:1")!!
        controller.updateSession("portal-a:1", false)
        assertFalse(controller.state.value.keyboardOpen)
        assertTrue(controller.state.value.sending)
        controller.updateSession("portal-a:1", true)
        controller.open("portal-a:1")
        assertNull(controller.send("portal-a:1"))
        acknowledgement.complete(false)
        request.join()
        assertEquals("Synthetic draft", controller.state.value.draft)
        assertTrue(controller.state.value.error)
    }

    @Test fun exceptionAndCancellationReleasePendingOwnershipWithoutClearingDraft() = runTest {
        val failure = RemoteTextController(backgroundScope) { _, _ -> throw IllegalStateException() }
        failure.updateSession("portal-a:1", true)
        failure.open("portal-a:1")
        failure.edit("portal-a:1", "Synthetic failure")
        failure.send("portal-a:1")!!.join()
        assertEquals("Synthetic failure", failure.state.value.draft)
        assertTrue(failure.state.value.error)
        assertFalse(failure.state.value.sending)

        val acknowledgement = CompletableDeferred<Boolean>()
        val cancelled = RemoteTextController(backgroundScope) { _, _ -> acknowledgement.await() }
        cancelled.updateSession("portal-a:1", true)
        cancelled.open("portal-a:1")
        cancelled.edit("portal-a:1", "Synthetic cancellation")
        cancelled.send("portal-a:1")!!.cancelAndJoin()
        assertEquals("Synthetic cancellation", cancelled.state.value.draft)
        assertFalse(cancelled.state.value.sending)
    }
}
