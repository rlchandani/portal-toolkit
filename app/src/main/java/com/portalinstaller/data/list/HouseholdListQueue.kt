/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.list

import org.json.JSONArray
import org.json.JSONObject

/** JSON form of the pending queue, for storage and for the /remote/list POST body. */
object HouseholdListQueue {
    const val CHUNK = 50
    const val MAX_QUEUE = 500

    /** The contract form: an add carries its text only (the Portal derives the id from it). */
    fun wire(a: PendingAction): JSONObject = JSONObject().put("aid", a.aid).put("op", a.op.wire).put("at", a.at).apply {
        if (a.op != ListOp.ADD) a.id?.let { put("id", it) }
        a.text?.let { put("text", it) }
        a.order?.let { put("order", it) }
        if (a.op == ListOp.CLEAR_DONE) put("ids", JSONArray(a.ids))
    }

    /** Storage form: the wire form plus the add's local id, so queued follow-ups keep pointing at it. */
    fun encode(actions: List<PendingAction>): String =
        JSONArray(actions.map { a -> wire(a).apply { a.id?.let { put("id", it) } } }).toString()

    /** Unreadable storage is an empty queue; individually malformed rows are skipped. */
    fun decode(raw: String): List<PendingAction> {
        val rows = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until minOf(rows.length(), MAX_QUEUE)).mapNotNull { rows.optJSONObject(it)?.let(::action) }
    }

    fun chunks(actions: List<PendingAction>): List<List<PendingAction>> = actions.chunked(CHUNK)

    private fun action(row: JSONObject): PendingAction? {
        val aid = row.optString("aid").takeIf(HouseholdListIds.ID::matches) ?: return null
        val op = ListOp.fromWire(row.optString("op")) ?: return null
        val id = row.optString("id").takeIf(HouseholdListIds.ID::matches)
        val text = (row.opt("text") as? String)?.let(HouseholdListIds::cleanText)
        val order = row.optDouble("order", Double.NaN).takeIf { it.isFinite() }
        val ids = (row.opt("ids") as? JSONArray)?.let { arr ->
            (0 until arr.length()).mapNotNull { (arr.opt(it) as? String)?.takeIf(HouseholdListIds.ID::matches) }
        }.orEmpty()
        return PendingAction(aid, op, row.optLong("at"), id, text, order, ids)
    }
}
