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

/**
 * The Guest card's non-secret values, keyed exactly like the Portal's v2 `guests` settings domain.
 * The Wi-Fi password and the door code are secrets: they only leave the phone in a save body.
 */
data class GuestCard(
    val ssid: String = "",
    val security: String = SECURITY_WPA,
    val hiddenNetwork: Boolean = false,
    val hidePassword: Boolean = true,
    val welcome: String = GuestWelcome.DEFAULT,
    /** "" for no check-out, else "HH:mm". */
    val checkout: String = "",
    /** Preset ids, in order; ids this app doesn't know are kept so a newer Portal's choices survive. */
    val goodToKnow: List<String> = emptyList(),
    val customNotes: List<String> = emptyList(),
    val contact: String = GuestBasics.CONTACT_NONE,
    val contactName: String = "",
) {
    val isOpen: Boolean get() = security == SECURITY_NONE

    fun toJson(): JSONObject = JSONObject().put("ssid", ssid.trim()).put("security", security)
        .put("hiddenNetwork", hiddenNetwork).put("hidePassword", hidePassword).put("welcome", welcome.trim())
        .put("checkout", checkout).put("goodToKnow", GuestBasics.encodeIds(goodToKnow))
        .put("customNotes", GuestBasics.encodeNotes(customNotes)).put("contact", contact)
        .put("contactName", contactName.trim())

    companion object {
        const val MAX_SSID = 32
        const val MAX_PASSWORD = 64
        const val MIN_WPA_PASSWORD = 8
        const val SECURITY_WPA = "wpa"
        const val SECURITY_SAE = "sae"
        const val SECURITY_WEP = "wep"
        const val SECURITY_NONE = "none"
        val SECURITIES = setOf(SECURITY_WPA, SECURITY_SAE, SECURITY_WEP, SECURITY_NONE)
        val SECURITY_OPTIONS = listOf(SECURITY_WPA to "WPA/WPA2", SECURITY_SAE to "WPA3", SECURITY_WEP to "WEP",
            SECURITY_NONE to "Open (no password)")

        fun securityLabel(value: String): String = SECURITY_OPTIONS.firstOrNull { it.first == value }?.second ?: "WPA/WPA2"
    }
}

/** One Portal's `/remote/guests` read-back. The password and door code are never returned. */
data class GuestSnapshot(
    val card: GuestCard,
    val hasPassword: Boolean,
    val hasDoorCode: Boolean,
    val ready: Boolean,
    val active: Boolean,
    val untilMs: Long?,
    /** 1 for a Calder release from before the step-by-step setup. */
    val version: Int,
) {
    val needsUpdate: Boolean get() = version < GuestWire.VERSION
}

/** How a save treats the Portal's stored door code. [toString] never shows the code. */
sealed interface DoorCodeChange {
    data object Keep : DoorCodeChange
    data object Clear : DoorCodeChange
    class Set(val code: String) : DoorCodeChange {
        override fun equals(other: Any?) = other is Set && other.code == code
        override fun hashCode() = code.hashCode()
        override fun toString() = "Set(****)"
    }
}

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

/** Bodies and parsing for `/remote/guests` (the Portal's GuestApi), payload version [VERSION]. */
object GuestWire {
    const val ROUTE = "guests"
    const val VERSION = 2

    fun parse(json: JSONObject?): GuestSnapshot? = runCatching {
        val card = json?.optJSONObject("card") ?: return null
        val mode = json.optJSONObject("mode") ?: return null
        GuestSnapshot(
            card = parseCard(card),
            hasPassword = json.optBoolean("hasPassword"),
            hasDoorCode = json.optBoolean("hasDoorCode"),
            ready = json.optBoolean("ready"),
            active = mode.optBoolean("active"),
            untilMs = if (mode.isNull("until")) null else mode.optLong("until"),
            version = json.optInt("version", 1),
        )
    }.getOrNull()

    /** v1 keys (doorCode, houseNote, emergency) are ignored: an old Portal is shown as needing an update. */
    private fun parseCard(card: JSONObject) = GuestCard(
        ssid = card.optString("ssid"),
        security = card.optString("security").takeIf { it in GuestCard.SECURITIES } ?: GuestCard.SECURITY_WPA,
        hiddenNetwork = card.optBoolean("hiddenNetwork"),
        hidePassword = card.optBoolean("hidePassword", true),
        welcome = card.optString("welcome"),
        checkout = card.optString("checkout").takeIf(GuestCheckout::isValid).orEmpty(),
        goodToKnow = GuestBasics.decodeIds(card.optString("goodToKnow")),
        customNotes = GuestBasics.decodeNotes(card.optString("customNotes")),
        contact = card.optString("contact").takeIf { id -> GuestBasics.CONTACTS.any { it.id == id } }
            ?: GuestBasics.CONTACT_NONE,
        contactName = card.optString("contactName"),
    )

    /** A blank [password] keeps the Portal's stored one; an open network never sends one. */
    fun saveBody(card: GuestCard, password: String, doorCode: DoorCodeChange): JSONObject =
        JSONObject().put("action", "save").put("version", VERSION).put("card", card.toJson()).apply {
            if (password.isNotEmpty() && !card.isOpen) put("password", password)
            when (doorCode) {
                DoorCodeChange.Keep -> Unit
                DoorCodeChange.Clear -> put("clearDoorCode", true)
                is DoorCodeChange.Set -> put("doorCode", doorCode.code)
            }
        }

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
    fun done(
        verb: String,
        ok: List<String>,
        failed: List<String>,
        rejected: List<String> = emptyList(),
        outdated: List<String> = emptyList(),
    ): String = listOfNotNull(
        ok.takeIf { it.isNotEmpty() }?.let { "$verb on ${names(it)}." },
        failed.takeIf { it.isNotEmpty() }?.let { "Couldn’t reach ${names(it)}." },
        rejected.takeIf { it.isNotEmpty() }?.let { "${names(it)} didn’t accept the change. Update Calder there and try again." },
        outdated.takeIf { it.isNotEmpty() }?.let { "Update Calder on ${names(it)} to use the new guest setup." },
    ).joinToString(" ")

    private fun names(list: List<String>): String =
        if (list.size == 1) list[0] else list.dropLast(1).joinToString(", ") + " and " + list.last()
}
