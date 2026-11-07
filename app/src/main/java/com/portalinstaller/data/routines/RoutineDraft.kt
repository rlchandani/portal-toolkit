/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.routines

import java.security.SecureRandom

/** New-routine templates from the prototype's empty state. */
object RoutineTemplates {
    private val random = SecureRandom()
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
    fun newId(prefix: String): String = prefix + (1..8).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

    fun create(kind: String, ids: () -> String = { newId("r") }): Routine {
        val weekdays = listOf(true, true, true, true, true, false, false)
        val (name, pics, times) = when (kind) {
            "morning" -> Triple("School morning", listOf("shirt", "bowl", "toothbrush", "shoes", "backpack"), 435 to 475)
            "bedtime" -> Triple("Bedtime", listOf("bath", "pyjamas", "toothbrush", "book"), 1170 to 1200)
            else -> Triple("", emptyList(), 420 to 450)
        }
        val id = ids()
        val cards = pics.map { RoutineCard(ids(), it, RoutinePictures.label(it)) }
        return Routine(id, name, emptyList(), cards, if (kind == "bedtime") List(7) { true } else weekdays,
            times.first, times.second, enabled = true, skips = emptyList())
    }
}

/**
 * The editor's draft: immutable, every edit returns a new draft. [dirty] drives "Discard changes?".
 */
data class RoutineDraft(val routine: Routine, val isNew: Boolean, private val original: Routine) {
    val dirty: Boolean get() = routine != original

    fun withName(name: String) = copy(routine = routine.copy(name = name.take(RoutineValidation.NAME_MAX)))
    fun withStart(min: Int) = copy(routine = routine.copy(start = min))
    fun withEnd(min: Int) = copy(routine = routine.copy(end = min))
    fun toggleDay(i: Int) = copy(routine = routine.copy(days = routine.days.mapIndexed { j, d -> if (j == i) !d else d }))
    fun toggleSkip(iso: String) = copy(routine = routine.copy(skips = (if (iso in routine.skips) routine.skips - iso else routine.skips + iso).sorted()))

    /** [EVERYONE] clears the list; a kid id toggles that kid. */
    fun toggleKid(kid: String) = copy(routine = routine.copy(kids = when (kid) {
        EVERYONE -> emptyList()
        in routine.kids -> routine.kids - kid
        else -> routine.kids + kid
    }))

    fun addCard(pic: String, label: String, ids: () -> String = { RoutineTemplates.newId("c") }): RoutineDraft =
        if (routine.cards.size >= RoutineValidation.MAX_CARDS) this
        else copy(routine = routine.copy(cards = routine.cards + RoutineCard(ids(), pic, clean(label))))

    fun updateCard(index: Int, pic: String, label: String): RoutineDraft =
        copy(routine = routine.copy(cards = routine.cards.mapIndexed { i, c -> if (i == index) c.copy(pic = pic, label = clean(label)) else c }))

    fun removeCard(index: Int) = copy(routine = routine.copy(cards = routine.cards.filterIndexed { i, _ -> i != index }))
    fun withCards(cards: List<RoutineCard>) = copy(routine = routine.copy(cards = cards))

    fun moveCard(from: Int, to: Int): RoutineDraft {
        val cards = routine.cards
        if (from !in cards.indices || to !in cards.indices || from == to) return this
        val list = cards.toMutableList().apply { add(to, removeAt(from)) }
        return copy(routine = routine.copy(cards = list.toList()))
    }

    companion object {
        const val EVERYONE = "all"
        fun new(r: Routine) = RoutineDraft(r, true, r)
        fun edit(r: Routine) = RoutineDraft(r, false, r)
        private fun clean(label: String) = label.trim().take(RoutineValidation.LABEL_MAX)

        fun firstUnusedPicture(r: Routine): String = RoutinePictures.ids.firstOrNull { id -> r.cards.none { it.pic == id } } ?: "shirt"

        /** The time sheet's ±60 / ±5 steppers wrap around midnight. */
        fun stepTime(min: Int, delta: Int) = Math.floorMod(min + delta, 1440)
        fun setPm(min: Int, pm: Boolean) = if (pm == (min >= 720)) min else Math.floorMod(min + if (pm) 720 else -720, 1440)
    }
}
