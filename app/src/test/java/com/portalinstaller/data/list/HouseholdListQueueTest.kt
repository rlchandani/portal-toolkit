/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.list

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HouseholdListQueueTest {
    private val actions = listOf(
        PendingAction("a1", ListOp.ADD, 5, HouseholdListIds.itemId("Milk"), "Milk", null, emptyList()),
        PendingAction("a2", ListOp.MOVE, 6, "h1", null, 1.5, emptyList()),
        PendingAction("a3", ListOp.CLEAR_DONE, 7, null, null, null, listOf("h2", "h3")),
    )

    @Test fun roundTripsThroughJson() {
        assertEquals(actions, HouseholdListQueue.decode(HouseholdListQueue.encode(actions)))
    }

    @Test fun wireFormMatchesTheContract() {
        val wire = HouseholdListQueue.wire(actions[0])
        assertEquals("add", wire.getString("op"))
        assertEquals("Milk", wire.getString("text"))
        assertEquals("a1", wire.getString("aid"))
        assertEquals(5L, wire.getLong("at"))
        assertTrue(!wire.has("id")) // the contract's add carries text only; the Portal derives the id
        assertEquals("clear_done", HouseholdListQueue.wire(actions[2]).getString("op"))
        assertEquals(2, HouseholdListQueue.wire(actions[2]).getJSONArray("ids").length())
    }

    @Test fun corruptStorageDecodesToEmptyAndBadRowsAreSkipped() {
        assertTrue(HouseholdListQueue.decode("{not json").isEmpty())
        val mixed = "[{\"aid\":\"a1\",\"op\":\"tick\",\"at\":1,\"id\":\"h1\"},{\"aid\":\"bad aid\",\"op\":\"tick\",\"at\":1},{\"aid\":\"a3\",\"op\":\"explode\",\"at\":1}]"
        assertEquals(listOf("a1"), HouseholdListQueue.decode(mixed).map { it.aid })
    }

    @Test fun chunksAtFifty() {
        val many = (1..120).map { PendingAction("a$it", ListOp.TICK, it.toLong(), "h1", null, null, emptyList()) }
        assertEquals(listOf(50, 50, 20), HouseholdListQueue.chunks(many).map { it.size })
    }
}
