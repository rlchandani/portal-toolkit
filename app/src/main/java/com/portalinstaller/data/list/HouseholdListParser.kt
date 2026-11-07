/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.list

import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads a Portal's /remote/list response. The Portal is untrusted input: a response without a
 * valid revision or item array is rejected whole; individual malformed items are skipped and
 * every string is length-capped, so one bad row never hides the rest of the list.
 */
object HouseholdListParser {
    const val MAX_ITEMS = 500
    const val MAX_NAME = 40
    private const val MAX_REMOTE_TEXT = 200
    private const val MAX_NOTE = 240
    private const val MAX_USED_BEFORE = 8
    private val REVISION = Regex("^[0-9a-f]{1,64}$")

    fun snapshot(json: JSONObject): ListSnapshot? {
        val revision = json.optString("revision").takeIf(REVISION::matches) ?: return null
        val rows = json.opt("items") as? JSONArray ?: return null
        val seen = HashSet<String>()
        val items = (0 until minOf(rows.length(), MAX_ITEMS)).mapNotNull { rows.optJSONObject(it)?.let(::item) }
            .filter { seen.add(it.id) }
        val used = (json.opt("usedBefore") as? JSONArray)?.let { arr ->
            (0 until arr.length()).mapNotNull { (arr.opt(it) as? String)?.let(HouseholdListIds::cleanText) }
        }.orEmpty().distinctBy(HouseholdListIds::norm).take(MAX_USED_BEFORE)
        val sync = json.optJSONObject("sync")
        return ListSnapshot(revision, cap(json.optString("portal"), MAX_NAME).ifEmpty { "Portal" }, items, used,
            syncState(sync?.optString("state")), peers(sync?.opt("peers") as? JSONArray))
    }

    fun results(json: JSONObject): Map<String, ActionStatus> {
        val rows = json.opt("results") as? JSONArray ?: return emptyMap()
        return (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.mapNotNull { row ->
            val aid = row.optString("aid").takeIf(HouseholdListIds.ID::matches) ?: return@mapNotNull null
            aid to when (row.optString("status")) {
                "applied" -> ActionStatus.APPLIED
                "duplicate" -> ActionStatus.DUPLICATE
                "missing" -> ActionStatus.MISSING
                else -> ActionStatus.INVALID
            }
        }.toMap()
    }

    fun stale(json: JSONObject): Boolean = json.optBoolean("stale", false)

    private fun item(row: JSONObject): ListItem? {
        val id = row.optString("id").takeIf(HouseholdListIds.ID::matches) ?: return null
        val text = row.optString("text").trim().takeIf { it.isNotEmpty() && it.length <= MAX_REMOTE_TEXT } ?: return null
        val order = row.optDouble("order", Double.NaN).takeIf { it.isFinite() } ?: return null
        if (!row.has("addedAt")) return null
        val addedAt = row.optLong("addedAt")
        val done = row.optBoolean("done", false)
        return ListItem(id, text, done, order, name(row, "addedBy") ?: "another device", addedAt,
            row.optLong("updatedAt", addedAt), name(row, "doneBy"), time(row, "doneAt"),
            name(row, "renamedBy"), time(row, "renamedAt"), row.optJSONObject("note")?.let(::note))
    }

    private fun note(row: JSONObject): ItemNote? {
        val kind = when (row.optString("kind")) { "kept" -> NoteKind.KEPT; "edited" -> NoteKind.EDITED; else -> return null }
        val text = cap(row.optString("text"), MAX_NOTE).ifEmpty { return null }
        return ItemNote(kind, text, string(row, "alt")?.let(HouseholdListIds::cleanText), name(row, "altBy"))
    }

    private fun syncState(value: String?): PortalSyncState =
        PortalSyncState.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: PortalSyncState.OK

    private fun peers(rows: JSONArray?): List<PeerStatus> = rows?.let { arr ->
        (0 until minOf(arr.length(), 16)).mapNotNull { arr.optJSONObject(it) }.mapNotNull { row ->
            name(row, "name")?.let { PeerStatus(it, row.optBoolean("synced", false)) }
        }
    }.orEmpty()

    private fun string(row: JSONObject, key: String): String? = (row.opt(key) as? String)
    private fun name(row: JSONObject, key: String): String? = string(row, key)?.let { cap(it, MAX_NAME) }?.takeIf { it.isNotEmpty() }
    private fun time(row: JSONObject, key: String): Long? = (row.opt(key) as? Number)?.toLong()
    private fun cap(text: String, max: Int): String = text.trim().filterNot { it.isISOControl() }.take(max)
}
