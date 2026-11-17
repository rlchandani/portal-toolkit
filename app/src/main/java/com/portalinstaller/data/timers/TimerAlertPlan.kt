/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.timers

/**
 * When the phone says "<name> is done". The phone can't hear the household bus, so for every
 * running timer it schedules one check at the time left (a duration, so the phone's clock never
 * has to agree with the Portals'). The check asks the Portals again: still running (+1 min) →
 * check later; paused/cancelled/stopped → stay quiet; ringing → notify. Away from home Wi-Fi it
 * notifies anyway ("should be done"), since that's when the phone is the only reminder. Pure.
 */
object TimerAlertPlan {
    /** A timer the Portal hasn't flipped to ringing yet is re-checked after this long… */
    const val RECHECK_MS = 5_000L
    /** …at most this many times, then the phone notifies anyway. */
    const val MAX_RECHECKS = 3
    /** Remembered "already notified" keys. */
    const val MAX_REMEMBERED = 64

    data class Alarm(val id: String, val seq: Long, val name: String, val room: String, val delayMs: Long)

    sealed interface Check {
        data class Notify(val expected: Boolean) : Check
        data class Later(val delayMs: Long) : Check
        object Quiet : Check
    }

    /** One alarm per running timer, due when it should end. */
    fun alarms(timers: List<PhoneTimer>, nowMs: Long): List<Alarm> = timers.filter { it.state == TimerState.RUNNING }
        .map { Alarm(it.id, it.seq, it.name, it.room, it.remaining(nowMs)) }

    /** The version an alarm was planned for; a new seq (paused, +1 min…) re-plans it. */
    fun key(id: String, seq: Long) = "$id:$seq"

    /** What the scheduled check does with what the Portals said ([found] null: no Portal knows it). */
    fun check(found: PhoneTimer?, reachable: Boolean, rechecks: Int, nowMs: Long): Check = when {
        !reachable -> Check.Notify(expected = true)
        found == null -> Check.Quiet
        found.state == TimerState.RINGING -> Check.Notify(expected = false)
        found.state == TimerState.RUNNING -> {
            val left = found.remaining(nowMs)
            when {
                left > RECHECK_MS -> Check.Later(left)
                rechecks < MAX_RECHECKS -> Check.Later(RECHECK_MS)
                else -> Check.Notify(expected = true)
            }
        }
        else -> Check.Quiet
    }

    /** Timers ringing right now that the phone hasn't announced yet. */
    fun newlyRinging(timers: List<PhoneTimer>, notified: Set<String>): List<PhoneTimer> =
        timers.filter { it.state == TimerState.RINGING && key(it.id, it.seq) !in notified }

    /** Keeps the newest [MAX_REMEMBERED] keys (insertion order). */
    fun remember(notified: List<String>, key: String): List<String> = (notified.filterNot { it == key } + key).takeLast(MAX_REMEMBERED)
}
