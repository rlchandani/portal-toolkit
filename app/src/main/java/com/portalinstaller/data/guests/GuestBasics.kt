/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.guests

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A "Good to know" chip. [glyph] is a plain text symbol (the app has no icon font). */
data class GuestPreset(val id: String, val label: String, val glyph: String)

/** A contact chip and the line the card shows for it. */
data class GuestContactOption(val id: String, val label: String, val line: String)

/** The option-based house basics; ids and copy match the Portal's guest card. */
object GuestBasics {
    const val CONTACT_NONE = "none"
    const val MAX_NOTES = 6
    const val MAX_NOTE = 40
    const val MAX_CONTACT_NAME = 40

    val PRESETS = listOf(
        GuestPreset("shoes_off", "Shoes off inside", "👟"),
        GuestPreset("quiet_hours", "Quiet after 10 PM", "🌙"),
        GuestPreset("snacks", "Help yourself to snacks", "🍪"),
        GuestPreset("coffee", "Coffee is in the kitchen", "☕"),
        GuestPreset("pets", "Pets in the house", "🐾"),
        GuestPreset("no_smoking", "No smoking", "🚭"),
        GuestPreset("towels", "Towels in the bathroom", "🛁"),
        GuestPreset("street_parking", "Park on the street", "🚗"),
    )

    val CONTACTS = listOf(
        GuestContactOption("call_portal", "Call us on the Portal", "Need anything? Call us on the Portal."),
        GuestContactOption("text", "Text us", "Need anything? Text us."),
        GuestContactOption("ask", "Ask for help", "Need anything? Just ask."),
    )

    fun presetLabel(id: String): String? = PRESETS.firstOrNull { it.id == id }?.label

    fun decodeIds(raw: String): List<String> = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    fun encodeIds(ids: List<String>): String = ids.distinct().joinToString(",")

    /** Adds [id] at the end, or removes it; unknown ids already present are left alone. */
    fun toggle(ids: List<String>, id: String): List<String> = if (id in ids) ids - id else ids + id

    fun cleanNote(text: String): String =
        text.replace('|', '/').filterNot { it.isISOControl() }.trim().take(MAX_NOTE).trim()

    /** [notes] plus [text], unless it's blank, already there (any case) or the list is full. */
    fun addNote(notes: List<String>, text: String): List<String> {
        val note = cleanNote(text)
        val duplicate = notes.any { it.equals(note, ignoreCase = true) }
        return if (note.isEmpty() || duplicate || notes.size >= MAX_NOTES) notes else notes + note
    }

    fun encodeNotes(notes: List<String>): String = notes
        .map { note -> note.replace('|', '/').filterNot { it.isISOControl() }.trim() }
        .filter { it.isNotEmpty() }.distinct().take(MAX_NOTES).joinToString("|")

    fun decodeNotes(raw: String): List<String> = raw.split('|').map { it.trim() }.filter { it.isNotEmpty() }

    fun contactLine(id: String): String? = CONTACTS.firstOrNull { it.id == id }?.line
    fun nameLine(name: String): String? = name.trim().takeIf { it.isNotEmpty() }?.let { "Questions? Ask $it." }

    /** A display name only: one line, bounded, and never something that reads as a phone number. */
    fun cleanContactName(name: String): String {
        val clean = name.filterNot { it.isISOControl() }.trim().take(MAX_CONTACT_NAME).trim()
        val looksLikeNumber = clean.count { it.isDigit() } >= 3 && clean.all { it.isDigit() || it in " +-().#" }
        return if (looksLikeNumber) "" else clean
    }
}

/** Check-out: none, a preset hour or a custom time, stored as 24h "HH:mm". */
object GuestCheckout {
    val PRESETS = listOf("10:00", "11:00", "12:00")
    private val PATTERN = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")

    fun isValid(value: String): Boolean = value.isEmpty() || PATTERN.matches(value)
    fun of(hour: Int, minute: Int): String = "%02d:%02d".format(Locale.ROOT, hour, minute)
    fun parts(value: String): Pair<Int, Int>? =
        value.takeIf { it.isNotEmpty() && isValid(it) }?.split(':')?.let { it[0].toInt() to it[1].toInt() }

    fun display(value: String, use24: Boolean, locale: Locale = Locale.getDefault()): String {
        val (h, m) = parts(value) ?: return "No check-out"
        return LocalTime.of(h, m).format(DateTimeFormatter.ofPattern(if (use24) "H:mm" else "h:mm a", locale))
    }

    fun line(value: String, use24: Boolean, locale: Locale = Locale.getDefault()): String? =
        parts(value)?.let { "Check-out by " + display(value, use24, locale) }
}

/** The door code: ASCII digits only, 4–8 of them. A secret, like the Wi-Fi password. */
object GuestDoorCode {
    const val MIN = 4
    const val MAX = 8
    fun isValid(code: String): Boolean = code.length in MIN..MAX && code.all { it in '0'..'9' }
    fun clean(input: String): String = input.filter { it in '0'..'9' }.take(MAX)
}

/** The welcome line: the only free text, with a default and samples to tap. */
object GuestWelcome {
    const val MAX = 80
    const val DEFAULT = "Welcome! Make yourself at home."
    val SAMPLES = listOf(
        DEFAULT,
        "So glad you're here. Wi-Fi's below.",
        "Hi! Help yourself to anything in the kitchen.",
        "Welcome to our home — enjoy your stay.",
    )
    fun clean(text: String): String = text.replace('\n', ' ').replace('\r', ' ').filterNot { it.isISOControl() }.take(MAX)
}
