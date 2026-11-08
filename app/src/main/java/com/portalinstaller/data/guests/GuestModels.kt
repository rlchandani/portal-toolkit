/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.guests

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONObject

/** The Guest card's non-secret text, keyed exactly like the Portal's `guests` settings domain. */
data class GuestCard(
    val ssid: String = "",
    val security: String = SECURITY_WPA,
    val hiddenNetwork: Boolean = false,
    val hidePassword: Boolean = true,
    val welcome: String = "",
    val doorCode: String = "",
    val checkout: String = "",
    val houseNote: String = "",
    val emergency: String = "",
) {
    private val lines get() = listOf(ssid, welcome, doorCode, checkout, houseNote, emergency)

    /** The first reason this draft can't be saved, or null. [password] blank keeps the stored one. */
    fun problem(password: String, hasPassword: Boolean): String? = when {
        ssid.isBlank() -> "Add the Wi-Fi network name."
        lines.any { line -> line.any { it.isISOControl() } } -> "Use one line for each item."
        lines.any { it.length > MAX_TEXT } -> "Keep each line under $MAX_TEXT characters."
        password.length > MAX_PASSWORD || password.any { it.isISOControl() } -> "The password is too long."
        security != SECURITY_NONE && password.isEmpty() && !hasPassword -> "Add the Wi-Fi password."
        else -> null
    }

    fun toJson(): JSONObject = JSONObject().put("ssid", ssid.trim()).put("security", security)
        .put("hiddenNetwork", hiddenNetwork).put("hidePassword", hidePassword).put("welcome", welcome.trim())
        .put("doorCode", doorCode.trim()).put("checkout", checkout.trim()).put("houseNote", houseNote.trim())
        .put("emergency", emergency.trim())

    companion object {
        const val MAX_TEXT = 120
        const val MAX_PASSWORD = 64
        const val SECURITY_WPA = "wpa"
        const val SECURITY_WEP = "wep"
        const val SECURITY_NONE = "none"
        val SECURITY_OPTIONS = listOf(SECURITY_WPA to "WPA/WPA2/WPA3", SECURITY_WEP to "WEP", SECURITY_NONE to "No password")
    }
}

/** One Portal's `/remote/guests` read-back. The password itself is never returned. */
data class GuestSnapshot(
    val card: GuestCard,
    val hasPassword: Boolean,
    val ready: Boolean,
    val active: Boolean,
    val untilMs: Long?,
)

/** When Guest mode ends on its own; resolved in the Portal's time zone. */
sealed interface GuestStart {
    data object UntilEnded : GuestStart
    data class UntilTomorrow(val hour: Int, val minute: Int) : GuestStart
    data class ForDays(val days: Int) : GuestStart

    companion object {
        val choices: List<Pair<String, GuestStart>> = listOf(
            "Until I end it" to UntilEnded,
            "Until tomorrow 11:00" to UntilTomorrow(11, 0),
            "For 2 days" to ForDays(2),
        )
    }
}

/** Bodies and parsing for `/remote/guests` (the Portal's GuestApi). */
object GuestWire {
    const val ROUTE = "guests"
    private val SECURITIES = setOf(GuestCard.SECURITY_WPA, GuestCard.SECURITY_WEP, GuestCard.SECURITY_NONE)

    fun parse(json: JSONObject?): GuestSnapshot? = runCatching {
        val card = json?.optJSONObject("card") ?: return null
        val mode = json.optJSONObject("mode") ?: return null
        GuestSnapshot(
            card = GuestCard(
                ssid = card.optString("ssid"),
                security = card.optString("security").takeIf { it in SECURITIES } ?: GuestCard.SECURITY_WPA,
                hiddenNetwork = card.optBoolean("hiddenNetwork"),
                hidePassword = card.optBoolean("hidePassword", true),
                welcome = card.optString("welcome"),
                doorCode = card.optString("doorCode"),
                checkout = card.optString("checkout"),
                houseNote = card.optString("houseNote"),
                emergency = card.optString("emergency"),
            ),
            hasPassword = json.optBoolean("hasPassword"),
            ready = json.optBoolean("ready"),
            active = mode.optBoolean("active"),
            untilMs = if (mode.isNull("until")) null else mode.optLong("until"),
        )
    }.getOrNull()

    /** A blank [password] leaves the Portal's stored password untouched. */
    fun saveBody(card: GuestCard, password: String): JSONObject = JSONObject().put("action", "save")
        .put("card", card.toJson()).apply { if (password.isNotEmpty()) put("password", password) }

    fun startBody(start: GuestStart): JSONObject = JSONObject().put("action", "start").apply {
        when (start) {
            GuestStart.UntilEnded -> Unit
            is GuestStart.ForDays -> put("days", start.days)
            is GuestStart.UntilTomorrow -> put("untilHour", start.hour).put("untilMinute", start.minute)
        }
    }

    fun endBody(): JSONObject = JSONObject().put("action", "end")
}

object GuestCopy {
    fun status(active: Boolean, untilMs: Long?, zone: ZoneId = ZoneId.systemDefault()): String = when {
        !active -> "Off"
        untilMs == null -> "On until you end it"
        else -> "On until " + Instant.ofEpochMilli(untilMs).atZone(zone)
            .format(DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH))
    }

    /** "Saved on Kitchen. Couldn’t reach Hall." */
    fun done(verb: String, ok: List<String>, failed: List<String>, rejected: List<String> = emptyList()): String = listOfNotNull(
        ok.takeIf { it.isNotEmpty() }?.let { "$verb on ${names(it)}." },
        failed.takeIf { it.isNotEmpty() }?.let { "Couldn’t reach ${names(it)}." },
        rejected.takeIf { it.isNotEmpty() }?.let { "${names(it)} didn’t accept the change. Update Calder there and try again." },
    ).joinToString(" ")

    private fun names(list: List<String>): String =
        if (list.size == 1) list[0] else list.dropLast(1).joinToString(", ") + " and " + list.last()
}
