/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.chores

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** The phone's copy of the Portal's chores model: same wire, same views, same merge. */
class ChoreModelTest {
    private val monday = 20773 // Monday 16 Nov 2026

    /** Exactly what a Portal's GET /remote/chores carries in `state` (contract fixture). */
    private val portalJson = """
        {"v":1,
         "kids":[{"i":"kidmia","n":"Mia","a":"🦊","c":0,"g":6,"r":"Movie night","q":2,"b":"living-room","x":false},
                 {"i":"kidleo","n":"Leo","a":"🐻","c":3,"g":0,"r":"","q":1,"b":"living-room","x":false}],
         "chores":[{"i":"chbed","t":"Make bed","p":"bed","k":"kidmia","s":2,"ok":false,"q":1,"b":"living-room","x":false,"w":"d"},
                   {"i":"chtoys","t":"Tidy toys","p":"toys","k":"","s":3,"ok":true,"q":1,"b":"kitchen","x":false,"w":"w","wd":[1,3,5]},
                   {"i":"chgone","t":"Old","p":"cup","k":"","s":1,"ok":false,"q":4,"b":"kitchen","x":true,"w":"o","od":20700}],
         "ticks":[{"i":"tick0001","c":"chbed","k":"kidmia","d":20773,"s":2,"st":"ok","q":1,"b":"kitchen"},
                  {"i":"tick0002","c":"chtoys","k":"kidmia","d":20773,"s":3,"st":"wait","q":1,"b":"kitchen"}]}
    """.trimIndent()

    private val state = ChoreCodec.decodeState(JSONObject(portalJson))!!

    @Test fun readsThePortalsReplica() {
        assertEquals(listOf("Leo", "Mia"), state.liveKids.map { it.name })
        assertEquals(listOf("chbed", "chtoys"), state.liveChores.map { it.id })
        assertTrue(state.chores.getValue("chgone").deleted)
        assertEquals(ChoreSchedule.Weekdays(setOf(1, 3, 5)), state.chores.getValue("chtoys").schedule)
        assertNull(state.chores.getValue("chtoys").kid)
        assertEquals(state, ChoreCodec.decodeState(ChoreCodec.encodeState(state)))
    }

    @Test fun weekTotalsPendingAndGoalLineMatchThePortal() {
        val mia = ChoreBoard.week(state, monday).first { it.kid == "kidmia" }
        assertEquals(2, mia.stars)
        assertEquals(3, mia.pending)
        assertEquals("2 / 6 ★ — Movie night", ChoreBoard.goalLine(mia))
        assertEquals(listOf("tick0002"), ChoreBoard.pending(state).map { it.id })
        assertEquals(0, ChoreBoard.week(state, monday + 7).first { it.kid == "kidmia" }.stars)
        assertEquals(2, ChoreBoard.history(state, monday + 7).first().first { it.kid == "kidmia" }.stars)
    }

    @Test fun todaysBoardShowsOwnAndAnyoneChores() {
        val board = ChoreBoard.today(state, monday).associate { it.kid.name to it.items.map { i -> i.chore.id to i.done } }
        assertEquals(listOf("chbed" to true, "chtoys" to true), board["Mia"])
        assertEquals(emptyList<Pair<String, Boolean>>(), board["Leo"]) // Mia already has the anyone chore
        assertEquals(1, ChoreDays.weekday(monday))
        assertEquals(monday, ChoreDays.day(monday * ChoreDays.DAY_MS + 3_600_000L, TimeZone.getTimeZone("UTC")))
    }

    @Test fun mergingTwoPortalsKeepsTheNewestEditAndEveryTick() {
        val kitchen = state.copy(
            kids = state.kids + ("kidmia" to state.kids.getValue("kidmia").copy(goal = 10, seq = 3, by = "kitchen")),
            ticks = state.ticks + ("tick0002" to state.ticks.getValue("tick0002").copy(status = TickStatus.APPROVED, seq = 2)))
        val living = state.copy(ticks = state.ticks + ("tick0003" to ChoreTick("tick0003", "chbed", "kidmia", monday + 1, 2, TickStatus.AWARDED, 1, "living-room")))
        val merged = ChoreMerge.merge(living, kitchen)
        assertEquals(merged, ChoreMerge.merge(kitchen, living))
        assertEquals(10, merged.kids.getValue("kidmia").goal)
        assertEquals(7, ChoreBoard.week(merged, monday + 1).first { it.kid == "kidmia" }.stars)
        assertTrue(ChoreBoard.pending(merged).isEmpty())
    }

    @Test fun junkFromAPortalIsSkipped() {
        val bad = JSONObject(portalJson)
        bad.getJSONArray("ticks").put(JSONObject().put("i", "x"))
        bad.getJSONArray("kids").put(JSONObject(bad.getJSONArray("kids").getJSONObject(0).toString()).put("i", "kidbad").put("c", 99))
        assertEquals(state, ChoreCodec.decodeState(bad))
        assertNull(ChoreCodec.decodeState(JSONObject().put("v", 9)))
    }
}
