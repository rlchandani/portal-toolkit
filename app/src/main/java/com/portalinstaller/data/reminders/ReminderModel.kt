/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.reminders

import com.portalinstaller.data.chores.ChoreText

/** Someone enrolled in Who's there on the answering Portal. [id] is only meaningful on that Portal. */
data class ReminderPerson(val id: String, val name: String, val kid: Boolean)

/** One shared reminder. [person] "" = the household ("Home"); [due] epoch ms, 0 = none. */
data class Reminder(
    val id: String,
    val title: String,
    val person: String = "",
    val forName: String = ReminderLimits.HOME,
    val due: Long = 0L,
    val done: Boolean = false,
    val by: String = "",
    val seq: Long = 0L,
)

/**
 * What one Portal answered for `/remote/reminders`. Private reminders are never listed, only
 * counted per person in [privateCounts].
 */
data class ReminderBoard(
    val ready: Boolean = false,
    val portal: String = "",
    val reminders: List<Reminder> = emptyList(),
    val privateCounts: Map<String, Int> = emptyMap(),
    val people: List<ReminderPerson> = emptyList(),
) {
    /** Open first (soonest due, then undated newest), then done (newest first). */
    val open: List<Reminder> get() = reminders.filter { !it.done }
        .sortedWith(compareBy<Reminder>({ it.due == 0L }, { it.due }, { -it.seq }, { it.id }))
    val finished: List<Reminder> get() = reminders.filter { it.done }.sortedWith(compareBy({ -it.seq }, { it.id }))

    fun person(id: String): ReminderPerson? = if (id.isEmpty()) null else people.firstOrNull { it.id == id }
}

object ReminderLimits {
    const val HOME = "Home"
    const val TITLE_MAX = 80
    const val NAME_MAX = 40
    const val BY_MAX = 40
    const val ID_MAX = 64
    const val MAX_REMINDERS = 500
    const val MAX_PEOPLE = 50
    const val MAX_PRIVATE = 10_000
}

/**
 * What the phone edits. [id] null = a new reminder; [person] "" = Home; [private] null = let the
 * Portal decide (Home and kids are never private), otherwise the switch's value; [base] = the seq seen.
 */
data class ReminderDraft(
    val id: String? = null,
    val title: String = "",
    val person: String = "",
    val due: Long = 0L,
    val private: Boolean? = null,
    val base: Long = 0L,
) {
    val valid: Boolean get() = cleanTitle != null
    val cleanTitle: String? get() = ChoreText.clean(title, ReminderLimits.TITLE_MAX)

    /** The Private switch only makes sense for a grown-up. */
    fun showsPrivate(board: ReminderBoard): Boolean = board.person(person)?.kid == false

    /** Who it's for; a new reminder for a grown-up defaults to private, an edit keeps showing (it was visible). */
    fun forPerson(target: ReminderPerson?): ReminderDraft = copy(
        person = target?.id.orEmpty(),
        private = if (target == null || target.kid) null else private ?: (id == null),
    )

    companion object {
        fun of(r: Reminder, board: ReminderBoard): ReminderDraft =
            ReminderDraft(id = r.id, title = r.title, due = r.due, base = r.seq).forPerson(board.person(r.person))
    }
}
