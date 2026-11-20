/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.reminders

import org.json.JSONObject

/** A phone command for `/remote/reminders` POST. [base] is the seq the phone last saw. */
sealed interface ReminderCommand {
    data class Add(val draft: ReminderDraft) : ReminderCommand
    data class Edit(val draft: ReminderDraft) : ReminderCommand
    data class Done(val id: String, val base: Long) : ReminderCommand
    data class Undo(val id: String, val base: Long) : ReminderCommand
    data class Delete(val id: String, val base: Long) : ReminderCommand

    /** Person ids are per Portal, so a command naming one must go to the Portal that listed it. */
    val namesPerson: Boolean get() = when (this) {
        is Add -> draft.person.isNotEmpty()
        is Edit -> draft.person.isNotEmpty()
        else -> false
    }

    fun body(): JSONObject = when (this) {
        is Add -> fields(JSONObject().put("action", "add"), draft)
        is Edit -> fields(JSONObject().put("action", "edit").put("id", draft.id.orEmpty()).put("base", draft.base), draft)
        is Done -> JSONObject().put("action", "done").put("id", id).put("base", base)
        is Undo -> JSONObject().put("action", "undo").put("id", id).put("base", base)
        is Delete -> JSONObject().put("action", "delete").put("id", id).put("base", base)
    }

    companion object {
        /** Add for a new draft, edit for an existing one. */
        fun save(draft: ReminderDraft): ReminderCommand = if (draft.id == null) Add(draft) else Edit(draft)

        fun toggle(r: Reminder): ReminderCommand = if (r.done) Undo(r.id, r.seq) else Done(r.id, r.seq)

        private fun fields(json: JSONObject, d: ReminderDraft): JSONObject {
            json.put("title", d.cleanTitle.orEmpty()).put("person", d.person).put("due", d.due.coerceAtLeast(0L))
            d.private?.let { json.put("private", it) }
            return json
        }
    }
}
