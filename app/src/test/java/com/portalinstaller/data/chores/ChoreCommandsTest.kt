/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.chores

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** POST bodies match the Portal's ChoreRequests contract; drafts and copy behave. */
class ChoreCommandsTest {
    private val monday = 20773

    @Test fun kidSaveCarriesEveryFieldAndTheSeqSeen() {
        val body = ChoreCommand.SaveKid(KidDraft("kidmia", " Mia ", "🦊", 2, 20, "Movie night", seq = 4)).body()
        assertEquals("kid_save", body.getString("action"))
        val kid = body.getJSONObject("kid")
        assertEquals(listOf("kidmia", "Mia", "🦊", 2, 20, "Movie night", 4L),
            listOf(kid.getString("id"), kid.getString("name"), kid.getString("avatar"), kid.getInt("color"), kid.getInt("goal"),
                kid.getString("reward"), kid.getLong("seq")))
        assertEquals("", ChoreCommand.SaveKid(KidDraft(name = "Zoe")).body().getJSONObject("kid").getString("id"))
    }

    @Test fun choreSaveUsesRepeatDaysAndAnyone() {
        val chore = ChoreCommand.SaveChore(ChoreDraft(title = "Feed cat", pic = "bowl", kid = null, stars = 2,
            repeat = ChoreRepeat.DAYS, days = setOf(7, 6), needsOk = true)).body().getJSONObject("chore")
        assertEquals("", chore.getString("kid"))
        assertEquals("days", chore.getString("repeat"))
        assertEquals("[6,7]", chore.getJSONArray("days").toString())
        assertTrue(chore.getBoolean("needsOk"))
        assertEquals("once", ChoreCommand.SaveChore(ChoreDraft(title = "Bath", repeat = ChoreRepeat.ONCE, day = monday)).body()
            .getJSONObject("chore").getString("repeat"))
    }

    @Test fun tickCommands() {
        assertEquals("approve", ChoreCommand.Approve("tick0001").body().getString("action"))
        assertEquals("decline", ChoreCommand.Decline("tick0001").body().getString("action"))
        assertEquals("take_back", ChoreCommand.TakeBack("tick0001").body().getString("action"))
        assertEquals("tick0001", ChoreCommand.TakeBack("tick0001").body().getString("id"))
        assertEquals("chore_delete", ChoreCommand.DeleteChore("chbed").body().getString("action"))
        assertEquals("kid_delete", ChoreCommand.DeleteKid("kidmia").body().getString("action"))
    }

    @Test fun draftsRoundTripFromTheModelAndValidate() {
        val chore = Chore("chtoys", "Tidy toys", "toys", null, 3, ChoreSchedule.Weekdays(setOf(1, 3)), true, 5, "kitchen")
        val draft = ChoreDraft.of(chore)
        assertEquals(ChoreRepeat.DAYS, draft.repeat)
        assertEquals(setOf(1, 3), draft.days)
        assertEquals(5L, draft.seq)
        assertFalse(draft.copy(title = "  ").valid)
        assertFalse(draft.copy(days = emptySet()).valid)
        assertTrue(draft.copy(repeat = ChoreRepeat.DAILY, days = emptySet()).valid)
        assertFalse(KidDraft(name = "Mia", goal = 501).valid)
        assertEquals(1, KidDraft.next(1).color)
        assertEquals(ChoreAvatars.all[1], KidDraft.next(1).avatar)
    }

    @Test fun copyIsShortAndFriendly() {
        assertEquals("Weekdays", ChoreCopy.schedule(ChoreSchedule.Weekdays(setOf(1, 2, 3, 4, 5)), monday))
        assertEquals("Weekends", ChoreCopy.schedule(ChoreSchedule.Weekdays(setOf(6, 7)), monday))
        assertEquals("Mon, Wed", ChoreCopy.schedule(ChoreSchedule.Weekdays(setOf(3, 1)), monday))
        assertEquals("Tomorrow only", ChoreCopy.schedule(ChoreSchedule.Once(monday + 1), monday))
        assertEquals("Mia · Make bed · Today", ChoreCopy.pendingLine("Mia", "Make bed", monday, monday))
        assertEquals("Mia · Make bed · Mon", ChoreCopy.pendingLine("Mia", "Make bed", monday, monday + 2))
        assertEquals("That was already sorted on another device.", ChoreCopy.refused("invalid_state"))
    }
}
