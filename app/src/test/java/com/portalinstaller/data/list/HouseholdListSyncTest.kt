/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.list

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HouseholdListSyncTest {
    private val milk = PendingAction("a1", ListOp.ADD, 5, HouseholdListIds.itemId("Milk"), "Milk", null, emptyList())

    @Test fun postsTheQueueToEveryReachablePortalAndAcksOnAnySuccess() = runTest {
        val fa = FakeListPortal("Family Area"); val la = FakeListPortal("Living Area", reachable = false)
        val out = HouseholdListSync.run(listOf(fa, la), listOf(milk), "Rohit’s Pixel", "000000000abc")
        assertEquals(setOf("a1"), out.acked)
        assertEquals(listOf("Family Area"), out.reached)
        assertEquals(listOf("Living Area"), out.unreached)
        assertEquals("Rohit’s Pixel", fa.posts.single().getString("device"))
        assertEquals("000000000abc", fa.posts.single().getString("expectedRevision"))
        assertEquals("Milk", out.snapshot!!.items.single().text)
    }

    @Test fun keepsTheQueueWhenNoPortalAnswers() = runTest {
        val out = HouseholdListSync.run(listOf(FakeListPortal("Family Area", reachable = false)), listOf(milk), "P", null)
        assertTrue(out.acked.isEmpty())
        assertNull(out.snapshot)
        assertEquals(listOf("Family Area"), out.unreached)
    }

    @Test fun anEmptyQueueOnlyReads() = runTest {
        val fa = FakeListPortal("Family Area")
        val out = HouseholdListSync.run(listOf(fa), emptyList(), "P", null)
        assertTrue(fa.posts.isEmpty())
        assertNotNull(out.snapshot)
        assertEquals(listOf("Family Area"), out.reached)
    }

    @Test fun aRejectedBatchIsDroppedSoItCannotWedgeTheQueue() = runTest {
        val fa = FakeListPortal("Family Area").apply { rejectWith = 400 }
        val out = HouseholdListSync.run(listOf(fa), listOf(milk), "P", null)
        assertEquals(setOf("a1"), out.dropped)
        assertEquals(listOf("Family Area"), out.reached)
    }

    @Test fun sendsLongQueuesInChunks() = runTest {
        val fa = FakeListPortal("Family Area")
        val many = (1..60).map { PendingAction("a$it", ListOp.TICK, it.toLong(), "h1", null, null, emptyList()) }
        val out = HouseholdListSync.run(listOf(fa), many, "P", null)
        assertEquals(listOf(50, 10), fa.posts.map { it.getJSONArray("actions").length() })
        assertEquals(60, out.acked.size)
    }

    @Test fun noPairedPortalsMeansNothingReached() = runTest {
        val out = HouseholdListSync.run(emptyList(), listOf(milk), "P", null)
        assertTrue(out.reached.isEmpty() && out.unreached.isEmpty() && out.acked.isEmpty())
    }
}
