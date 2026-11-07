/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.list

/** The outcome of a list gesture: queue [actions] (undoable by [inverse]), or just say [snack]. */
internal data class Edit(
    val snack: String,
    val actions: List<PendingAction> = emptyList(),
    val inverse: List<PendingAction> = emptyList(),
    val ghosts: List<ListItem> = emptyList(),
)

/** Pure gesture → action rules, with the mock's snackbar copy. [stamp] makes an action at "now". */
internal class HouseholdListEdits(private val stamp: (ListOp, String?, String?, Double?, List<String>) -> PendingAction) {
    private fun act(op: ListOp, id: String? = null, text: String? = null, order: Double? = null, ids: List<String> = emptyList()) =
        stamp(op, id, text, order, ids)

    fun add(view: ListView, raw: String): Edit {
        if (raw.isBlank()) return Edit("Type something to add first.")
        val text = HouseholdListIds.cleanText(raw) ?: return Edit("Keep it under ${HouseholdListIds.MAX_TEXT} characters.")
        val key = HouseholdListIds.norm(text)
        view.open.firstOrNull { HouseholdListIds.norm(it.text) == key }?.let { return Edit("${it.text} is already on the list.") }
        val done = view.done.firstOrNull { HouseholdListIds.norm(it.text) == key }
        if (done != null) return Edit("Moved ${done.text} back to the list.", listOf(act(ListOp.ADD, done.id, done.text)),
            listOf(act(ListOp.TICK, done.id)))
        val id = HouseholdListIds.itemId(text)
        return Edit("Added $text.", listOf(act(ListOp.ADD, id, text)), listOf(act(ListOp.REMOVE, id)))
    }

    fun toggle(item: ListItem): Edit = if (item.done)
        Edit("Put ${item.text} back on the list.", listOf(act(ListOp.UNTICK, item.id)), listOf(act(ListOp.TICK, item.id)))
    else Edit("Ticked ${item.text}.", listOf(act(ListOp.TICK, item.id)), listOf(act(ListOp.UNTICK, item.id)))

    fun remove(item: ListItem): Edit =
        Edit("Removed ${item.text}.", listOf(act(ListOp.REMOVE, item.id)), listOf(act(ListOp.RESTORE, item.id)), listOf(item))

    fun rename(item: ListItem, raw: String): Edit? {
        if (raw.isBlank()) return Edit("A name can’t be empty. Use Remove to take it off the list.")
        val text = HouseholdListIds.cleanText(raw) ?: return Edit("Keep it under ${HouseholdListIds.MAX_TEXT} characters.")
        if (text == item.text) return null
        return Edit("Renamed to $text.", listOf(act(ListOp.RENAME, item.id, text)), listOf(act(ListOp.RENAME, item.id, item.text)))
    }

    fun useAlt(item: ListItem): Edit? {
        val alt = item.note?.alt ?: return null
        return Edit("Kept “$alt”.", listOf(act(ListOp.RENAME, item.id, alt)))
    }

    fun move(view: ListView, id: String, beforeId: String?): Edit? {
        val item = view.open.firstOrNull { it.id == id } ?: return null
        val rest = view.open.filter { it.id != id }
        val index = beforeId?.let { b -> rest.indexOfFirst { it.id == b }.takeIf { it >= 0 } } ?: rest.size
        val order = HouseholdListApply.orderBetween(rest.getOrNull(index - 1)?.order, rest.getOrNull(index)?.order)
        if (order == item.order) return null
        return Edit("", listOf(act(ListOp.MOVE, id, order = order)), listOf(act(ListOp.MOVE, id, order = item.order)))
    }

    fun moveToTop(view: ListView, id: String): Edit? {
        val first = view.open.firstOrNull() ?: return null
        if (first.id == id) return null
        return move(view, id, first.id)?.copy(snack = "Moved to the top.")
    }

    fun clearDone(view: ListView): Edit? {
        if (view.done.isEmpty()) return null
        val n = view.done.size
        return Edit("Cleared $n done item${if (n > 1) "s" else ""}.", listOf(act(ListOp.CLEAR_DONE, ids = view.done.map { it.id })),
            view.done.map { act(ListOp.RESTORE, it.id) }, view.done)
    }
}
