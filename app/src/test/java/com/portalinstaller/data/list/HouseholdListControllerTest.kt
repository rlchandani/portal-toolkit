/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.list

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HouseholdListControllerTest {
    private class MemoryStore : HouseholdListStore {
        var queue = emptyList<PendingAction>()
        var snapshot: ListSnapshot? = null
        override fun loadQueue() = queue
        override fun saveQueue(actions: List<PendingAction>) { queue = actions }
        override fun loadSnapshot() = snapshot
        override fun saveSnapshot(snapshot: ListSnapshot) { this.snapshot = snapshot }
    }

    private val fa = FakeListPortal("Family Area")
    private val la = FakeListPortal("Living Area")
    private val store = MemoryStore()
    private var network = NetworkKind.WIFI
    private var seq = 0

    private fun TestScope.controller() = HouseholdListController(
        scope = this, portals = { listOf(fa, la) }, store = store, device = { "Rohit’s Pixel" },
        clock = { 1_000L + seq }, network = { network }, newAid = { "a${++seq}" },
        dispatcher = StandardTestDispatcher(testScheduler))

    @Test fun addSendsToBothPortalsAndShowsUpToDate() = runTest {
        val c = controller(); c.start(); advanceUntilIdle()
        c.add("Milk"); advanceUntilIdle()
        assertEquals(listOf("Milk"), c.state.value.view.open.map { it.text })
        assertTrue(c.state.value.view.waiting.isEmpty())
        assertTrue(store.queue.isEmpty())
        assertEquals("Up to date with both Portals", c.state.value.status.text)
        assertEquals(1, fa.posts.size); assertEquals(1, la.posts.size)
        assertEquals("Added Milk.", c.state.value.snack!!.text)
    }

    @Test fun awayFromHomeQueuesThenSendsWithASnackbar() = runTest {
        fa.reachable = false; la.reachable = false; network = NetworkKind.OTHER
        val c = controller(); c.start(); advanceUntilIdle()
        c.add("Lemons"); advanceUntilIdle()
        c.add("Yogurt"); advanceUntilIdle()
        assertEquals(2, store.queue.size)
        assertEquals("Away from home. 2 changes waiting to send.", c.state.value.status.text)
        assertEquals(setOf(HouseholdListIds.itemId("Lemons"), HouseholdListIds.itemId("Yogurt")), c.state.value.view.waiting)
        fa.reachable = true; la.reachable = true; network = NetworkKind.WIFI
        c.onNetworkAvailable(); advanceUntilIdle()
        assertTrue(store.queue.isEmpty())
        assertEquals("Sent 2 changes to both Portals.", c.state.value.snack!!.text)
        assertEquals("Up to date with both Portals", c.state.value.status.text)
    }

    @Test fun duplicateAddIsCaughtLocally() = runTest {
        val c = controller(); c.start(); advanceUntilIdle()
        c.add("Eggs"); advanceUntilIdle()
        val posts = fa.posts.size
        c.add(" eggs "); advanceUntilIdle()
        assertEquals("Eggs is already on the list.", c.state.value.snack!!.text)
        assertEquals(posts, fa.posts.size)
    }

    @Test fun blankAddAsksForText() = runTest {
        val c = controller(); c.start(); advanceUntilIdle()
        c.add("   ")
        assertEquals("Type something to add first.", c.state.value.snack!!.text)
    }

    @Test fun undoOfAQueuedActionCancelsItInsteadOfSendingTwo() = runTest {
        fa.reachable = false; la.reachable = false; network = NetworkKind.NONE
        val c = controller(); c.start(); advanceUntilIdle()
        c.add("Milk"); advanceUntilIdle()
        c.undo(); advanceUntilIdle()
        assertTrue(store.queue.isEmpty())
        assertTrue(c.state.value.view.open.isEmpty())
        assertEquals("No connection", c.state.value.status.text)
    }

    @Test fun clearDoneThenUndoRestoresEachItem() = runTest {
        val c = controller(); c.start(); advanceUntilIdle()
        c.add("Milk"); advanceUntilIdle()
        val id = HouseholdListIds.itemId("Milk")
        c.toggle(id); advanceUntilIdle()
        c.clearDone(); advanceUntilIdle()
        assertEquals("Cleared 1 done item.", c.state.value.snack!!.text)
        c.undo(); advanceUntilIdle()
        val ops = fa.posts.flatMap { p -> (0 until p.getJSONArray("actions").length()).map { p.getJSONArray("actions").getJSONObject(it).getString("op") } }
        assertEquals(listOf("add", "tick", "clear_done", "restore"), ops)
    }

    @Test fun moveToTopSendsAnOrderAboveTheFirstItem() = runTest {
        val c = controller(); c.start(); advanceUntilIdle()
        c.add("Milk"); advanceUntilIdle(); c.add("Bread"); advanceUntilIdle()
        c.moveToTop(HouseholdListIds.itemId("Milk")); advanceUntilIdle()
        val move = fa.posts.last().getJSONArray("actions").getJSONObject(0)
        assertEquals("move", move.getString("op"))
        assertTrue(move.getDouble("order") < 0.0)
    }

    @Test fun persistedQueueSurvivesARestartAndFlushesOnStart() = runTest {
        store.queue = listOf(PendingAction("old1", ListOp.ADD, 5, HouseholdListIds.itemId("Tea"), "Tea", null, emptyList()))
        val c = controller(); c.start(); advanceUntilIdle()
        assertTrue(store.queue.isEmpty())
        assertEquals("Tea", fa.posts.single().getJSONArray("actions").getJSONObject(0).getString("text"))
        assertEquals("Sent 1 change to both Portals.", c.state.value.snack!!.text)
        c.consumeSnack()
        assertNull(c.state.value.snack)
    }
}
