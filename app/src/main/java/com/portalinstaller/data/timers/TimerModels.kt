/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.timers

import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** The Portal's timer states (`/remote/timers` `state`); [rank] matches the Portal's tie-break. */
enum class TimerState(val wire: String, val rank: Int, val live: Boolean) {
    RUNNING("run", 0, true),
    PAUSED("pause", 0, true),
    RINGING("ring", 1, true),
    DONE("done", 2, false),
    CANCELLED("cancel", 2, false);

    companion object {
        fun fromWire(code: String?): TimerState? = values().firstOrNull { it.wire == code }
    }
}

/**
 * One household timer as the phone sees it, the same model as the Portal's SharedTimer. The
 * Portal sends time LEFT ([remainingMs]), never its clock; [readAtMs] is this phone's monotonic
 * clock when that was read, so the countdown continues locally between polls.
 */
data class PhoneTimer(
    val id: String,
    val name: String,
    val room: String,
    val durationMs: Long,
    val remainingMs: Long,
    val state: TimerState,
    val seq: Long,
    val readAtMs: Long,
) {
    val paused: Boolean get() = state == TimerState.PAUSED

    fun remaining(nowMs: Long): Long = when (state) {
        TimerState.RUNNING -> (remainingMs - (nowMs - readAtMs).coerceAtLeast(0L)).coerceAtLeast(0L)
        TimerState.PAUSED -> remainingMs
        else -> 0L
    }

    companion object {
        const val MAX_NAME = 40
        const val MAX_DURATION_MS = 24 * 3_600_000L + 60 * 60_000L
        private val ID = Regex("^[a-z0-9][a-z0-9-]{3,39}$")

        fun isId(text: String?): Boolean = text != null && ID.matches(text)

        /** Portal data is untrusted: anything malformed is dropped. */
        fun parse(json: JSONObject?, readAtMs: Long): PhoneTimer? {
            if (json == null) return null
            val id = json.optString("id").takeIf(::isId) ?: return null
            val state = TimerState.fromWire(json.opt("state") as? String) ?: return null
            val duration = (json.opt("durationMs") as? Number)?.toLong()?.takeIf { it in 1L..MAX_DURATION_MS } ?: return null
            val remaining = (json.opt("remainingMs") as? Number)?.toLong()?.takeIf { it in 0L..MAX_DURATION_MS } ?: return null
            val seq = (json.opt("seq") as? Number)?.toLong()?.takeIf { it >= 1L } ?: return null
            val name = TimerText.clean(json.optString("name")) ?: "Timer"
            val room = TimerText.clean(json.optString("room")) ?: "Portal"
            return PhoneTimer(id, name, room, duration, remaining, state, seq, readAtMs)
        }

        fun parseList(array: JSONArray?, readAtMs: Long): List<PhoneTimer> =
            if (array == null) emptyList() else (0 until array.length()).mapNotNull { parse(array.optJSONObject(it), readAtMs) }
    }
}

/** The phone's household view: one entry per timer id, whichever Portal reported the newest version. */
object TimerMerge {
    private val order = compareBy<PhoneTimer>({ it.seq }, { it.state.rank })

    fun newer(a: PhoneTimer, b: PhoneTimer): PhoneTimer = if (order.compare(a, b) >= 0) a else b

    fun merge(lists: List<List<PhoneTimer>>): List<PhoneTimer> =
        lists.flatten().groupBy { it.id }.values.map { it.reduce(::newer) }

    /** Folds one Portal's answer for a single timer (after a command) into [timers]. */
    fun apply(timers: List<PhoneTimer>, timer: PhoneTimer): List<PhoneTimer> =
        if (timers.none { it.id == timer.id }) timers + timer
        else timers.map { if (it.id == timer.id) newer(timer, it) else it }

    /** Ringing first, then soonest to end, paused last; ended timers are hidden. */
    fun display(timers: List<PhoneTimer>, nowMs: Long): List<PhoneTimer> = timers.filter { it.state.live }
        .sortedWith(compareBy<PhoneTimer>({ group(it) }, { it.remaining(nowMs) }, { it.id }))

    private fun group(t: PhoneTimer) = when (t.state) {
        TimerState.RINGING -> 0
        TimerState.RUNNING -> 1
        else -> 2
    }
}

/** Phone → Portal commands (`POST /remote/timers`). */
sealed class TimerCommand {
    data class Start(val name: String, val durationMs: Long) : TimerCommand()
    data class Pause(val id: String) : TimerCommand()
    data class Resume(val id: String) : TimerCommand()
    data class AddMinute(val id: String) : TimerCommand()
    data class Cancel(val id: String) : TimerCommand()
    data class Stop(val id: String) : TimerCommand()

    fun body(sender: String): JSONObject = when (this) {
        is Start -> JSONObject().put("action", "start").put("durationMs", durationMs).put("name", name)
            .put("from", sender.take(PhoneTimer.MAX_NAME))
        is Pause -> act("pause", id)
        is Resume -> act("resume", id)
        is AddMinute -> act("add", id)
        is Cancel -> act("cancel", id)
        is Stop -> act("stop", id)
    }

    private fun act(action: String, id: String) = JSONObject().put("action", action).put("id", id)
}

/** Names and durations typed on the phone; the Portal re-validates anyway. */
object TimerText {
    val PRESET_MINUTES = listOf(1, 5, 10, 15, 30)
    private const val MAX_HOURS = 23
    private const val MAX_MINUTES = 59

    /** One printable line, ≤ [PhoneTimer.MAX_NAME] code points; null when empty. */
    fun clean(raw: String?): String? {
        if (raw == null) return null
        val out = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            i += Character.charCount(cp)
            val type = Character.getType(cp)
            when {
                Character.isWhitespace(cp) || Character.isSpaceChar(cp) -> out.append(' ')
                Character.isISOControl(cp) || type == Character.FORMAT.toInt() || type == Character.SURROGATE.toInt() -> Unit
                else -> out.appendCodePoint(cp)
            }
        }
        val text = out.toString().replace(Regex(" {2,}"), " ").trim()
        if (text.isEmpty()) return null
        return if (text.codePointCount(0, text.length) <= PhoneTimer.MAX_NAME) text
            else text.substring(0, text.offsetByCodePoints(0, PhoneTimer.MAX_NAME)).trimEnd()
    }

    /** Custom duration from hours/minutes fields; null when out of range or zero. */
    fun duration(hours: String, minutes: String): Long? {
        val h = hours.ifBlank { "0" }.toIntOrNull()?.takeIf { it in 0..MAX_HOURS } ?: return null
        val m = minutes.ifBlank { "0" }.toIntOrNull()?.takeIf { it in 0..MAX_MINUTES } ?: return null
        return ((h * 60L + m) * 60_000L).takeIf { it > 0L }
    }

    fun presetName(minutes: Int): String = "$minutes min"

    /** 1:05:09, 09:59. */
    fun clock(ms: Long): String {
        val seconds = (ms.coerceAtLeast(0L) + 999L) / 1000L
        return if (seconds >= 3600L) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
            else String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
    }

    fun subtitle(t: PhoneTimer): String = when (t.state) {
        TimerState.PAUSED -> "${t.room} · Paused"
        TimerState.RINGING -> "${t.room} · Time’s up"
        else -> t.room
    }
}
