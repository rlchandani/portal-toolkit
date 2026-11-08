/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.dinner

import org.json.JSONArray
import org.json.JSONObject

/** Call everyone presets; ids and copy match the Portal's DinnerPresets (the wire contract). */
data class DinnerPreset(val id: String, val text: String)

object DinnerPresets {
    val all = listOf(
        DinnerPreset("dinner", "Dinner’s ready"),
        DinnerPreset("down", "Come downstairs"),
        DinnerPreset("leave", "Time to leave"),
        DinnerPreset("bed", "Bedtime"),
        DinnerPreset("lights", "Lights out in 5 minutes"),
        DinnerPreset("home10", "Home in 10 minutes"),
    )
}

sealed class PhoneMessage {
    data class Preset(val id: String) : PhoneMessage()
    data class Custom(val text: String) : PhoneMessage()
    object Ping : PhoneMessage()

    companion object {
        const val MAX_CUSTOM = 40

        /** Same cleaning as the Portal (which re-validates anyway): one printable line, ≤ 40 code points. */
        fun custom(raw: String): Custom? {
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
            return text.takeIf { it.isNotEmpty() && it.codePointCount(0, it.length) <= MAX_CUSTOM }?.let(::Custom)
        }

        /** Typing stops at the limit. */
        fun limit(text: String): String = if (text.codePointCount(0, text.length) <= MAX_CUSTOM) text
            else text.substring(0, text.offsetByCodePoints(0, MAX_CUSTOM))
    }
}

/** Bodies for `POST /remote/dinner` (see the Portal's DinnerRequests). */
object DinnerRequest {
    fun body(message: PhoneMessage, rooms: List<String>, sender: String): JSONObject = JSONObject().apply {
        when (message) {
            is PhoneMessage.Preset -> put("kind", "preset").put("preset", message.id)
            is PhoneMessage.Custom -> put("kind", "custom").put("text", message.text)
            PhoneMessage.Ping -> put("kind", "ping")
        }
        put("rooms", JSONArray(rooms.distinct()))
        put("sender", sender.take(40))
    }

    fun retry(sendId: String, roomId: String): JSONObject = JSONObject().put("retry", JSONObject().put("id", sendId).put("room", roomId))
}

enum class DinnerRowStatus(val wire: String?, val text: String, val good: Boolean = false, val failed: Boolean = false) {
    SENDING("sending", "Sending…"),
    SPOKEN("spoken", "Chimed and said it", good = true),
    CHIMED("chimed", "Chimed", good = true),
    CHIMED_ONLY("chimedonly", "Chimed, shown without voice", good = true),
    QUIET("quiet", "Shown quietly, quiet hours", good = true),
    BLOCKED("blocked", "Calls are turned off there", failed = true),
    NO_ANSWER("noanswer", "No answer, not delivered", failed = true),
    NOT_SET_UP("notsetup", "Not sent. Open Portal Toolkit near the Portals", failed = true),
    /** Phone-side: that Portal didn't answer and the phone can't reach it either. */
    OFFLINE(null, "Offline, not delivered", failed = true),
    /** Phone-side: no paired Portal reachable from here (away from home Wi-Fi). */
    NOT_SENT(null, "Not sent, not on home Wi-Fi", failed = true),
    UNKNOWN(null, "Status unknown", failed = true);

    companion object {
        fun fromWire(code: String?): DinnerRowStatus = values().firstOrNull { it.wire != null && it.wire == code } ?: UNKNOWN
    }
}

data class DinnerResultRow(val roomId: String, val name: String, val status: DinnerRowStatus, val retry: Boolean, val reply: String?)

data class DinnerResult(val id: String, val text: String, val pending: Boolean, val rows: List<DinnerResultRow>) {
    companion object {
        private val SEND_ID = Regex("^[a-f0-9]{8,16}$")
        private val REPLIES = setOf("Coming", "On my way")
        private const val MAX_ROWS = 8

        /** Portal responses are validated like any external data; junk rows/replies are dropped. */
        fun parse(json: JSONObject?): DinnerResult? {
            if (json == null) return null
            val id = json.optString("id").takeIf { SEND_ID.matches(it) } ?: return null
            val rowsJson = json.optJSONArray("rows") ?: return null
            val rows = (0 until minOf(rowsJson.length(), MAX_ROWS)).mapNotNull { i ->
                val r = rowsJson.optJSONObject(i) ?: return@mapNotNull null
                val room = r.optString("room").takeIf { it.isNotBlank() && it.length <= 64 } ?: return@mapNotNull null
                val status = DinnerRowStatus.fromWire(r.optString("status"))
                val reply = (r.opt("reply") as? String)?.takeIf { it in REPLIES }
                DinnerResultRow(room, r.optString("name").take(40).ifBlank { "Portal" }, status,
                    r.optBoolean("retry") && status.failed, reply)
            }
            return DinnerResult(id, json.optString("text").take(64), json.optBoolean("pending") || rows.any { it.status == DinnerRowStatus.SENDING }, rows)
        }
    }
}

data class DinnerReplyEvent(val sendId: String, val roomName: String, val reply: String)

object DinnerCopy {
    fun title(result: DinnerResult): String {
        val good = result.rows.count { it.status.good }
        return when {
            result.pending -> "Sending “${result.text}”"
            result.rows.isNotEmpty() && good == result.rows.size -> "Sent “${result.text}”"
            good == 0 -> "Not delivered"
            else -> "Sent to $good of ${result.rows.size} Portals"
        }
    }

    fun subtitle(targets: List<String>, quiet: List<String>, blocked: Boolean): String = when {
        blocked -> "Waiting for a connection."
        quiet.isNotEmpty() -> "${quiet.joinToString(" and ")} ${if (quiet.size > 1) "are" else "is"} in quiet hours, so no sound there."
        targets.size == 2 -> "Both Portals chime and say it once."
        targets.size > 2 -> "All ${targets.size} Portals chime and say it once."
        else -> "${targets.firstOrNull() ?: "The Portal"} chimes and says it once."
    }

    /** Replies present in [after] that [before] didn't have. */
    fun newReplies(before: DinnerResult?, after: DinnerResult): List<DinnerReplyEvent> = after.rows.mapNotNull { row ->
        val old = before?.rows?.firstOrNull { it.roomId == row.roomId }?.reply
        if (row.reply != null && row.reply != old) DinnerReplyEvent(after.id, row.name, row.reply) else null
    }
}
