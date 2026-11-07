/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.routines

import org.junit.Assert.*
import org.junit.Test

class RoutineDraftTest {
    private var n = 0
    private val ids = { "id${n++}" }
    private val base = RoutineDraft.new(RoutineTemplates.create("morning", ids))

    @Test fun newDraftIsDirtyOnlyAfterAChange() {
        assertFalse(base.dirty)
        assertTrue(base.withName("Mornings").dirty)
        assertFalse(RoutineDraft.edit(base.routine).dirty)
    }

    @Test fun reorderMovesOneCard_withoutMutatingTheOriginal() {
        val moved = base.moveCard(4, 0)
        assertEquals("backpack", moved.routine.cards.first().pic)
        assertEquals("shirt", base.routine.cards.first().pic)
        assertSame(base, base.moveCard(9, 0))
    }

    @Test fun addEditRemoveCards_capAtEight() {
        var d = base
        repeat(5) { d = d.addCard(RoutineDraft.firstUnusedPicture(d.routine), "Card", ids) }
        assertEquals(8, d.routine.cards.size)
        assertSame(d, d.addCard("moon", "Moon", ids))
        val edited = d.updateCard(0, "teddy", "  Teddy  ")
        assertEquals(RoutineCard(d.routine.cards[0].id, "teddy", "Teddy"), edited.routine.cards[0])
        assertEquals(7, edited.removeCard(0).routine.cards.size)
        assertEquals("coat", RoutineDraft.firstUnusedPicture(base.routine))
    }

    @Test fun labelsAndNamesAreCapped() {
        assertEquals(24, base.withName("x".repeat(40)).routine.name.length)
        assertEquals(14, base.updateCard(0, "shirt", "y".repeat(30)).routine.cards[0].label.length)
    }

    @Test fun daysKidsSkipsAndTimes() {
        assertTrue(base.toggleDay(5).routine.days[5])
        assertEquals(listOf("maya"), base.toggleKid("maya").routine.kids)
        assertEquals(listOf("maya", "leo"), base.toggleKid("maya").toggleKid("leo").routine.kids)
        assertTrue(base.toggleKid("maya").toggleKid(RoutineDraft.EVERYONE).routine.kids.isEmpty())
        assertEquals(listOf("2026-11-11"), base.toggleSkip("2026-11-11").routine.skips)
        assertTrue(base.toggleSkip("2026-11-11").toggleSkip("2026-11-11").routine.skips.isEmpty())
        assertEquals(450, base.withStart(450).routine.start)
        assertEquals(500, base.withEnd(500).routine.end)
    }

    @Test fun timeSheetStepsWrapAndAmPmFlips() {
        assertEquals(1435, RoutineDraft.stepTime(5, -10))
        assertEquals(15, RoutineDraft.stepTime(1435, 20))
        assertEquals(435 + 720, RoutineDraft.setPm(435, true))
        assertEquals(435, RoutineDraft.setPm(435 + 720, false))
        assertEquals(435, RoutineDraft.setPm(435, false))
    }
}
