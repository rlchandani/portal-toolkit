/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.timers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the phone notifies that a timer ended. */
class TimerAlertPlanTest {
    private fun timer(state: TimerState, remaining: Long = 0L, seq: Long = 1L, id: String = "a1b2c3d4") =
        PhoneTimer(id, "Pasta", "Kitchen", 600_000L, remaining, state, seq, readAtMs = 0L)

    @Test fun eachRunningTimerGetsOneAlarmAtItsTimeLeft() {
        val alarms = TimerAlertPlan.alarms(listOf(timer(TimerState.RUNNING, 300_000L),
            timer(TimerState.PAUSED, 100_000L, id = "bbbbbbbb"), timer(TimerState.RINGING, id = "cccccccc")), nowMs = 60_000L)
        assertEquals(listOf(TimerAlertPlan.Alarm("a1b2c3d4", 1L, "Pasta", "Kitchen", 240_000L)), alarms)
    }

    @Test fun ringingTimerNotifies() {
        assertEquals(TimerAlertPlan.Check.Notify(expected = false), TimerAlertPlan.check(timer(TimerState.RINGING), true, 0, 0L))
    }

    @Test fun awayFromHomeTheTimerShouldBeDoneAnyway() {
        assertEquals(TimerAlertPlan.Check.Notify(expected = true), TimerAlertPlan.check(null, reachable = false, rechecks = 0, nowMs = 0L))
    }

    @Test fun stoppedPausedOrGoneTimersStayQuiet() {
        listOf(TimerState.PAUSED, TimerState.DONE, TimerState.CANCELLED).forEach {
            assertEquals(it.name, TimerAlertPlan.Check.Quiet, TimerAlertPlan.check(timer(it, 1_000L), true, 0, 0L))
        }
        assertEquals(TimerAlertPlan.Check.Quiet, TimerAlertPlan.check(null, true, 0, 0L))
    }

    @Test fun aTimerGivenMoreTimeIsCheckedAgainLater() {
        assertEquals(TimerAlertPlan.Check.Later(60_000L), TimerAlertPlan.check(timer(TimerState.RUNNING, 60_000L), true, 0, 0L))
    }

    @Test fun aTimerAboutToRingIsRecheckedAFewTimesThenNotified() {
        val almost = timer(TimerState.RUNNING, 1_000L)
        assertEquals(TimerAlertPlan.Check.Later(TimerAlertPlan.RECHECK_MS), TimerAlertPlan.check(almost, true, 0, 0L))
        assertEquals(TimerAlertPlan.Check.Notify(expected = true),
            TimerAlertPlan.check(almost, true, TimerAlertPlan.MAX_RECHECKS, 0L))
    }

    @Test fun eachRingIsAnnouncedOnceAndMemoryStaysBounded() {
        val ringing = timer(TimerState.RINGING, seq = 4L)
        assertEquals(listOf(ringing), TimerAlertPlan.newlyRinging(listOf(ringing, timer(TimerState.RUNNING, id = "zzzzzzzz")), emptySet()))
        assertTrue(TimerAlertPlan.newlyRinging(listOf(ringing), setOf(TimerAlertPlan.key("a1b2c3d4", 4L))).isEmpty())
        val many = (1..100).fold(emptyList<String>()) { acc, i -> TimerAlertPlan.remember(acc, "k$i") }
        assertEquals(TimerAlertPlan.MAX_REMEMBERED, many.size)
        assertEquals("k100", many.last())
        assertEquals(listOf("a", "b"), TimerAlertPlan.remember(listOf("b", "a"), "b"))
    }
}
