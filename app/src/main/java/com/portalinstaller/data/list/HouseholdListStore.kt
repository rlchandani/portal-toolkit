/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.list

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** The phone's pending queue and last known list survive restarts (shown offline, flushed later). */
interface HouseholdListStore {
    fun loadQueue(): List<PendingAction>
    fun saveQueue(actions: List<PendingAction>)
    fun loadSnapshot(): ListSnapshot?
    fun saveSnapshot(snapshot: ListSnapshot)
}

/** App-private SharedPreferences; the snapshot is stored in the Portal's own JSON shape. */
class PrefsHouseholdListStore(context: Context) : HouseholdListStore {
    private val prefs = context.applicationContext.getSharedPreferences("household_list", Context.MODE_PRIVATE)

    override fun loadQueue(): List<PendingAction> = prefs.getString(QUEUE, null)?.let(HouseholdListQueue::decode).orEmpty()
    // commit(): a queued change must be on disk before we report it as saved on this phone.
    override fun saveQueue(actions: List<PendingAction>) { prefs.edit().putString(QUEUE, HouseholdListQueue.encode(actions)).commit() }
    override fun loadSnapshot(): ListSnapshot? = prefs.getString(SNAPSHOT, null)
        ?.let { runCatching { JSONObject(it) }.getOrNull() }?.let(HouseholdListParser::snapshot)
    override fun saveSnapshot(snapshot: ListSnapshot) { prefs.edit().putString(SNAPSHOT, HouseholdListSnapshotCodec.encode(snapshot).toString()).apply() }

    private companion object {
        const val QUEUE = "queue"
        const val SNAPSHOT = "snapshot"
    }
}

/** Writes a snapshot back in the /remote/list shape so [HouseholdListParser] re-validates it on load. */
object HouseholdListSnapshotCodec {
    fun encode(s: ListSnapshot): JSONObject = JSONObject().put("ok", true).put("revision", s.revision).put("portal", s.portal)
        .put("items", JSONArray(s.items.map(::item))).put("usedBefore", JSONArray(s.usedBefore))
        .put("sync", JSONObject().put("state", s.syncState.name.lowercase())
            .put("peers", JSONArray(s.peers.map { JSONObject().put("name", it.name).put("synced", it.synced) })))

    private fun item(i: ListItem): JSONObject = JSONObject().put("id", i.id).put("text", i.text).put("done", i.done)
        .put("order", i.order).put("addedBy", i.addedBy).put("addedAt", i.addedAt).put("updatedAt", i.updatedAt)
        .put("doneBy", i.doneBy ?: JSONObject.NULL).put("doneAt", i.doneAt ?: JSONObject.NULL)
        .put("renamedBy", i.renamedBy ?: JSONObject.NULL).put("renamedAt", i.renamedAt ?: JSONObject.NULL)
        .put("note", i.note?.let { n -> JSONObject().put("kind", n.kind.name.lowercase()).put("text", n.text)
            .put("alt", n.alt ?: JSONObject.NULL).put("altBy", n.altBy ?: JSONObject.NULL) } ?: JSONObject.NULL)
}
