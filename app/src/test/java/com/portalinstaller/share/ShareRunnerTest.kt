/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.share

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareRunnerTest {
    private val family = ShareRoom("f", "Family Area")
    private val living = ShareRoom("l", "Living Area")
    private val items = listOf(ShareItem("1", "a.jpg", 10), ShareItem("2", "b.jpg", 10), ShareItem("3", "c.jpg", 10))

    private class FakeTransport : ShareTransport {
        val reach = mutableMapOf<String, Reach>()
        val outcomes = mutableMapOf<String, ArrayDeque<ItemState>>()
        val sent = mutableListOf<String>()
        override suspend fun reach(roomId: String, kind: ShareKind) = reach[roomId] ?: Reach.Ready
        override suspend fun photo(roomId: String, job: ShareJob, item: ShareItem, progress: (Long) -> Unit): ItemState {
            sent += "$roomId:${item.id}"
            progress(item.bytes)
            return outcomes["$roomId:${item.id}"]?.removeFirstOrNull() ?: ItemState(ItemStatus.CONFIRMED, item.bytes, portalPath = "/p/${item.name}")
        }
        override suspend fun message(roomId: String, job: ShareJob) = ItemState(ItemStatus.CONFIRMED, remoteState = "shown")
        override suspend fun check(roomId: String, job: ShareJob, item: ShareItem, state: ItemState) =
            ItemState(ItemStatus.CONFIRMED, state.sentBytes, portalPath = "/p/${item.name}")
    }

    private class Events : ShareEvents {
        val held = mutableListOf<String>(); val finished = mutableListOf<String>()
        override fun held(job: ShareJob) { held += job.id }
        override fun finished(job: ShareJob) { finished += job.id }
    }

    private fun TestScope.runner(t: ShareTransport, e: ShareEvents, saved: MutableList<List<ShareJob>> = mutableListOf()) =
        ShareRunner(t, this, persist = { saved += it }, events = e)

    private fun job(rooms: List<ShareRoom>) = ShareJob("j", ShareKind.PHOTOS, "Rohit's Pixel", 0, rooms, items)

    @Test fun everyFileIsConfirmedPerRoomAndFinishedFiresOnce() = runTest {
        val t = FakeTransport(); val e = Events(); val saved = mutableListOf<List<ShareJob>>()
        val r = runner(t, e, saved)
        r.start(job(listOf(family))); advanceUntilIdle()
        assertTrue(r.jobs.value.single().summary().allConfirmed)
        assertEquals(listOf("j"), e.finished)
        assertTrue(saved.isNotEmpty())
    }

    @Test fun anOfflineRoomWaitsAndCatchesUpByItself() = runTest {
        val t = FakeTransport().apply { reach["l"] = Reach.Held("Living Area isn’t responding. It may be off or offline.") }
        val e = Events(); val r = runner(t, e)
        r.start(job(listOf(family, living))); advanceUntilIdle()
        val waiting = r.jobs.value.single()
        assertEquals(3 to 3, waiting.roomSummary("f"))
        assertTrue(items.all { waiting.state("l", it.id).status == ItemStatus.HELD })
        assertEquals(listOf("j"), e.held); assertTrue(e.finished.isEmpty())
        t.reach.remove("l")
        r.resumeHeld(); advanceUntilIdle()
        assertTrue(r.jobs.value.single().summary().allConfirmed)
        assertEquals(listOf("j"), e.finished)
    }

    @Test fun retrySendsOnlyTheFileThatDidNotArrive() = runTest {
        val t = FakeTransport().apply { outcomes["f:2"] = ArrayDeque(listOf(ItemState(ItemStatus.FAILED, 5, "dropped"))) }
        val r = runner(t, Events())
        r.start(job(listOf(family))); advanceUntilIdle()
        assertEquals(1, r.jobs.value.single().retryCount())
        t.sent.clear()
        r.retryFailed("j"); advanceUntilIdle()
        assertEquals(listOf("f:2"), t.sent)
        assertTrue(r.jobs.value.single().summary().allConfirmed)
    }

    @Test fun checkAgainAsksThePortalInsteadOfResending() = runTest {
        val t = FakeTransport().apply { outcomes["f:1"] = ArrayDeque(listOf(ItemState(ItemStatus.UNCONFIRMED, 10))) }
        val r = runner(t, Events())
        r.start(job(listOf(family))); advanceUntilIdle()
        t.sent.clear()
        r.checkAgain("j"); advanceUntilIdle()
        assertTrue(t.sent.isEmpty())
        assertTrue(r.jobs.value.single().summary().allConfirmed)
    }

    @Test fun refusedRoomsFailWithTheirReasonAndAreRetryable() = runTest {
        val t = FakeTransport().apply { reach["f"] = Reach.Refused("Sharing is turned off on Family Area.") }
        val r = runner(t, Events())
        r.start(job(listOf(family))); advanceUntilIdle()
        val j = r.jobs.value.single()
        assertEquals("Sharing is turned off on Family Area.", j.state("f", "1").reason)
        assertEquals(3, j.retryCount())
    }

    @Test fun cancelWaitingStopsHeldFilesAndNothingIsSentLater() = runTest {
        val t = FakeTransport().apply { reach["f"] = Reach.Held("Your phone isn’t on home Wi-Fi.") }
        val r = runner(t, Events())
        r.start(job(listOf(family))); advanceUntilIdle()
        r.cancelWaiting("j")
        t.reach.clear()
        r.resumeHeld(); advanceUntilIdle()
        assertTrue(t.sent.isEmpty())
        assertTrue(r.jobs.value.single().cancelled)
    }

    @Test fun restoredJobsTreatInterruptedTransfersAsUnconfirmed() = runTest {
        val interrupted = job(listOf(family)).with("f", "1", ItemState(ItemStatus.SENDING, 4))
        val r = ShareRunner(FakeTransport(), this, persist = {}, events = Events(), initial = listOf(interrupted))
        assertEquals(ItemStatus.UNCONFIRMED, r.jobs.value.single().state("f", "1").status)
    }
}
