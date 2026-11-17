/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.timers

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone's copy of the Portal's timer model, merge rules, commands and text. */
class TimerModelsTest {
    private fun json(id: String = "a1b2c3d4e5f6", state: String = "run", remaining: Long = 300_000L, seq: Long = 1L, name: String = "Pasta") =
        JSONObject().put("id", id).put("name", name).put("room", "Kitchen").put("durationMs", 600_000L)
            .put("remainingMs", remaining).put("state", state).put("paused", state == "pause").put("seq", seq)

    @Test fun parsesAPortalTimerAndCountsDownOnThePhonesOwnClock() {
        val t = PhoneTimer.parse(json(), readAtMs = 1_000L)!!
        assertEquals(TimerState.RUNNING, t.state)
        assertEquals(300_000L, t.remaining(1_000L))
        assertEquals(240_000L, t.remaining(61_000L))
        assertEquals(0L, t.remaining(10_000_000L))
        val paused = PhoneTimer.parse(json(state = "pause"), readAtMs = 1_000L)!!
        assertEquals(300_000L, paused.remaining(99_000_000L))
        assertTrue(paused.paused)
    }

    @Test fun junkFromAPortalIsDropped() {
        assertNull(PhoneTimer.parse(json(id = "../x"), 0L))
        assertNull(PhoneTimer.parse(json(state = "boom"), 0L))
        assertNull(PhoneTimer.parse(json(remaining = -5L), 0L))
        assertNull(PhoneTimer.parse(json(seq = 0L), 0L))
        assertNull(PhoneTimer.parse(json().put("durationMs", "600000"), 0L))
        assertEquals("Timer", PhoneTimer.parse(json(name = "\u0000"), 0L)!!.name)
        val list = PhoneTimer.parseList(JSONArray().put(json()).put("nope").put(json(id = "!!")), 0L)
        assertEquals(1, list.size)
    }

    @Test fun mergeKeepsTheNewestVersionOfEachTimerAcrossPortals() {
        val kitchen = listOf(PhoneTimer.parse(json(seq = 2L, state = "pause"), 0L)!!)
        val den = listOf(PhoneTimer.parse(json(seq = 1L), 0L)!!, PhoneTimer.parse(json(id = "bbbbbbbb"), 0L)!!)
        val merged = TimerMerge.merge(listOf(den, kitchen))
        assertEquals(2, merged.size)
        assertEquals(TimerState.PAUSED, merged.first { it.id == "a1b2c3d4e5f6" }.state)
        // Same seq: an ending beats a ring (the Portal's tie-break).
        val ring = PhoneTimer.parse(json(seq = 3L, state = "ring"), 0L)!!
        val cancel = PhoneTimer.parse(json(seq = 3L, state = "cancel"), 0L)!!
        assertEquals(TimerState.CANCELLED, TimerMerge.merge(listOf(listOf(ring), listOf(cancel))).single().state)
    }

    @Test fun applyFoldsACommandAnswerWithoutGoingBackwards() {
        val current = listOf(PhoneTimer.parse(json(seq = 5L, state = "pause"), 0L)!!)
        val older = PhoneTimer.parse(json(seq = 4L), 0L)!!
        assertEquals(TimerState.PAUSED, TimerMerge.apply(current, older).single().state)
        val newer = PhoneTimer.parse(json(seq = 6L), 0L)!!
        assertEquals(TimerState.RUNNING, TimerMerge.apply(current, newer).single().state)
        assertEquals(2, TimerMerge.apply(current, PhoneTimer.parse(json(id = "cccccccc"), 0L)!!).size)
    }

    @Test fun displayShowsRingingFirstThenSoonestAndHidesEnded() {
        val timers = listOf(
            PhoneTimer.parse(json(id = "aaaaaaaa", remaining = 500_000L, name = "Late"), 0L)!!,
            PhoneTimer.parse(json(id = "bbbbbbbb", remaining = 10_000L, name = "Soon"), 0L)!!,
            PhoneTimer.parse(json(id = "cccccccc", state = "pause", remaining = 1_000L, name = "Paused"), 0L)!!,
            PhoneTimer.parse(json(id = "dddddddd", state = "ring", remaining = 0L, name = "Ring"), 0L)!!,
            PhoneTimer.parse(json(id = "eeeeeeee", state = "done", remaining = 0L, name = "Done"), 0L)!!,
        )
        assertEquals(listOf("Ring", "Soon", "Late", "Paused"), TimerMerge.display(timers, 0L).map { it.name })
    }

    @Test fun commandBodiesMatchThePortalRoute() {
        val start = TimerCommand.Start("Pasta", 600_000L).body("Rohit’s Pixel")
        assertEquals("start", start.getString("action"))
        assertEquals(600_000L, start.getLong("durationMs"))
        assertEquals("Pasta", start.getString("name"))
        assertEquals("Rohit’s Pixel", start.getString("from"))
        mapOf(TimerCommand.Pause("x1y2z3w4") to "pause", TimerCommand.Resume("x1y2z3w4") to "resume",
            TimerCommand.AddMinute("x1y2z3w4") to "add", TimerCommand.Cancel("x1y2z3w4") to "cancel",
            TimerCommand.Stop("x1y2z3w4") to "stop").forEach { (command, action) ->
            val body = command.body("Phone")
            assertEquals(action, body.getString("action"))
            assertEquals("x1y2z3w4", body.getString("id"))
        }
    }

    @Test fun namesDurationsAndClocks() {
        assertEquals("Pasta water", TimerText.clean("  Pasta \n water "))
        assertNull(TimerText.clean("   "))
        assertEquals(40, TimerText.clean("x".repeat(60))!!.length)
        assertEquals(90 * 60_000L, TimerText.duration("1", "30"))
        assertEquals(5 * 60_000L, TimerText.duration("", "5"))
        assertNull(TimerText.duration("0", "0"))
        assertNull(TimerText.duration("24", "0"))
        assertNull(TimerText.duration("0", "60"))
        assertEquals("09:59", TimerText.clock(599_000L))
        assertEquals("00:01", TimerText.clock(1L))
        assertEquals("1:00:00", TimerText.clock(3_600_000L))
        assertEquals(listOf(1, 5, 10, 15, 30), TimerText.PRESET_MINUTES)
    }
}
