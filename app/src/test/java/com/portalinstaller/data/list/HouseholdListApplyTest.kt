/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.list

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HouseholdListApplyTest {
    private val me = "Rohit’s Pixel"
    private fun item(text: String, order: Double, done: Boolean = false, doneAt: Long? = null) = ListItem(
        id = HouseholdListIds.itemId(text), text = text, done = done, order = order, addedBy = "Family Area",
        addedAt = 10, updatedAt = 10, doneBy = if (done) "Living Area" else null, doneAt = doneAt)

    private val base = listOf(item("Eggs", 1.0), item("Bread", 2.0), item("Rice", 0.0, done = true, doneAt = 50))
    private fun act(op: ListOp, at: Long, id: String? = null, text: String? = null, order: Double? = null,
                    ids: List<String> = emptyList()) =
        PendingAction("a$at", op, at, id, text, order, ids)

    @Test fun addPutsANewItemAtTheTopWithThisPhoneAsAuthor() {
        val view = HouseholdListApply.view(base, listOf(act(ListOp.ADD, 100, text = "Milk")), emptyList(), me)
        assertEquals(listOf("Milk", "Eggs", "Bread"), view.open.map { it.text })
        val milk = view.open.first()
        assertEquals(HouseholdListIds.itemId("Milk"), milk.id)
        assertEquals(me, milk.addedBy)
        assertTrue(milk.id in view.waiting)
        assertFalse(HouseholdListIds.itemId("Eggs") in view.waiting)
    }

    @Test fun addingAnOpenDuplicateChangesNothingAndAddingADoneItemMovesItBackToTheTop() {
        val view = HouseholdListApply.view(base, listOf(act(ListOp.ADD, 100, text = " eggs "), act(ListOp.ADD, 101, text = "RICE")),
            emptyList(), me)
        assertEquals(listOf("Rice", "Eggs", "Bread"), view.open.map { it.text }) // back on the list, at the top
        assertTrue(view.done.isEmpty())
    }

    @Test fun tickUntickRemoveRestoreRenameMove() {
        val eggs = HouseholdListIds.itemId("Eggs"); val bread = HouseholdListIds.itemId("Bread")
        val ticked = HouseholdListApply.view(base, listOf(act(ListOp.TICK, 100, id = eggs)), emptyList(), me)
        assertEquals(listOf("Eggs", "Rice"), ticked.done.map { it.text }) // newest tick first
        assertEquals(me, ticked.done.first().doneBy)
        val removed = HouseholdListApply.view(base, listOf(act(ListOp.REMOVE, 100, id = eggs)), emptyList(), me)
        assertEquals(listOf("Bread"), removed.open.map { it.text })
        val restored = HouseholdListApply.view(base, listOf(act(ListOp.REMOVE, 100, id = eggs), act(ListOp.RESTORE, 101, id = eggs)), emptyList(), me)
        assertEquals(listOf("Eggs", "Bread"), restored.open.map { it.text })
        val renamed = HouseholdListApply.view(base, listOf(act(ListOp.RENAME, 100, id = eggs, text = "Free-range eggs")), emptyList(), me)
        assertEquals("Free-range eggs", renamed.open.first().text)
        assertEquals(me, renamed.open.first().renamedBy)
        val moved = HouseholdListApply.view(base, listOf(act(ListOp.MOVE, 100, id = bread, order = 0.5)), emptyList(), me)
        assertEquals(listOf("Bread", "Eggs"), moved.open.map { it.text })
    }

    @Test fun restoreBringsBackAGhostTheSnapshotNoLongerHas() {
        val rice = base.last()
        val view = HouseholdListApply.view(base.dropLast(1), listOf(act(ListOp.RESTORE, 100, id = rice.id)), listOf(rice), me)
        assertEquals(listOf("Rice"), view.done.map { it.text })
    }

    @Test fun clearDoneRemovesTheGivenIdsOnly() {
        val rice = HouseholdListIds.itemId("Rice")
        val view = HouseholdListApply.view(base, listOf(act(ListOp.CLEAR_DONE, 100, ids = listOf(rice))), emptyList(), me)
        assertTrue(view.done.isEmpty())
        assertEquals(2, view.open.size)
    }

    @Test fun actionsOnUnknownItemsAreIgnored() {
        val view = HouseholdListApply.view(base, listOf(act(ListOp.TICK, 100, id = "hnope")), emptyList(), me)
        assertEquals(2, view.open.size)
        assertNull(view.find("hnope"))
    }

    @Test fun moveOrderBetweenNeighbours() {
        assertEquals(0.0, HouseholdListApply.orderBetween(null, null), 0.0)
        assertEquals(0.0, HouseholdListApply.orderBetween(null, 1.0), 0.0)
        assertEquals(3.0, HouseholdListApply.orderBetween(2.0, null), 0.0)
        assertEquals(1.5, HouseholdListApply.orderBetween(1.0, 2.0), 0.0)
    }
}
