/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.list

/**
 * Optimistic view: the last Portal state with this phone's queued changes folded on top, in
 * order. Pure; the Portal's merge rules stay the source of truth once the queue is accepted.
 */
object HouseholdListApply {
    private data class Row(val item: ListItem, val removed: Boolean)

    /** [ghosts] are items removed earlier in this session, so a queued restore can bring them back. */
    fun view(base: List<ListItem>, queue: List<PendingAction>, ghosts: List<ListItem>, device: String): ListView {
        val start = LinkedHashMap<String, Row>()
        ghosts.forEach { start[it.id] = Row(it, removed = true) }
        base.forEach { start[it.id] = Row(it, removed = false) }
        val rows = queue.fold(start as Map<String, Row>) { acc, action -> apply(acc, action, device) }
        val live = rows.values.filterNot { it.removed }.map { it.item }
        val waiting = queue.flatMap { listOfNotNull(it.id ?: it.text?.takeIf { _ -> it.op == ListOp.ADD }?.let(HouseholdListIds::itemId)) + it.ids }.toSet()
        return ListView(sortOpen(live.filterNot { it.done }), sortDone(live.filter { it.done }), waiting)
    }

    fun sortOpen(items: List<ListItem>): List<ListItem> = items.sortedWith(compareBy<ListItem> { it.order }.thenBy { it.addedAt })
    fun sortDone(items: List<ListItem>): List<ListItem> = items.sortedByDescending { it.doneAt ?: 0L }

    /** Order value placing an item between neighbours (null = no neighbour on that side). */
    fun orderBetween(before: Double?, after: Double?): Double = when {
        before == null && after == null -> 0.0
        before == null -> after!! - 1.0
        after == null -> before + 1.0
        else -> (before + after) / 2.0
    }

    private fun apply(rows: Map<String, Row>, a: PendingAction, device: String): Map<String, Row> {
        if (a.op == ListOp.ADD) return add(rows, a, device)
        if (a.op == ListOp.CLEAR_DONE) return a.ids.fold(rows) { acc, id -> update(acc, id) { it.copy(removed = true) } }
        val id = a.id ?: return rows
        return update(rows, id) { row ->
            val item = row.item
            when (a.op) {
                ListOp.TICK -> row.copy(item = item.copy(done = true, doneBy = device, doneAt = a.at, updatedAt = a.at))
                ListOp.UNTICK -> row.copy(item = item.copy(done = false, doneBy = null, doneAt = null, updatedAt = a.at))
                ListOp.REMOVE -> row.copy(removed = true)
                ListOp.RESTORE -> row.copy(removed = false)
                ListOp.RENAME -> a.text?.let { row.copy(item = item.copy(text = it, renamedBy = device, renamedAt = a.at, note = null, updatedAt = a.at)) } ?: row
                ListOp.MOVE -> a.order?.let { row.copy(item = item.copy(order = it)) } ?: row
                else -> row
            }
        }
    }

    private fun add(rows: Map<String, Row>, a: PendingAction, device: String): Map<String, Row> {
        val text = a.text ?: return rows
        val same = rows.values.firstOrNull { !it.removed && HouseholdListIds.norm(it.item.text) == HouseholdListIds.norm(text) }
        if (same != null) {
            if (!same.item.done) return rows
            return rows + (same.item.id to same.copy(item = same.item.copy(done = false, doneBy = null, doneAt = null,
                order = orderBetween(null, topOrder(rows)), updatedAt = a.at)))
        }
        val top = topOrder(rows)
        val id = a.id ?: HouseholdListIds.itemId(text)
        val item = ListItem(id, text, false, orderBetween(null, top), device, a.at, a.at)
        return rows + (id to Row(item, removed = false))
    }

    private fun topOrder(rows: Map<String, Row>): Double? =
        rows.values.filter { !it.removed && !it.item.done }.minOfOrNull { it.item.order }

    private fun update(rows: Map<String, Row>, id: String, change: (Row) -> Row): Map<String, Row> {
        val row = rows[id] ?: return rows
        return rows + (id to change(row))
    }
}
