/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.list

import com.portalinstaller.data.companion.CompanionError
import com.portalinstaller.data.companion.CompanionException
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/** A Portal that keeps a tiny list and answers /remote/list like the real one. */
class FakeListPortal(override val name: String, var reachable: Boolean = true) : ListPortal {
    val posts = mutableListOf<JSONObject>()
    var items = listOf<JSONObject>()
    var rejectWith: Int? = null
    private var removed = listOf<JSONObject>()

    private fun state(): JSONObject = JSONObject().put("ok", true).put("revision", "%012x".format(items.size + 0xabc))
        .put("portal", name).put("items", JSONArray(items)).put("usedBefore", JSONArray())
        .put("sync", JSONObject().put("state", "ok").put("peers", JSONArray()))

    override suspend fun get(): JSONObject {
        if (!reachable) throw IOException("unreachable")
        return state()
    }

    override suspend fun post(body: JSONObject): JSONObject {
        if (!reachable) throw IOException("unreachable")
        rejectWith?.let { throw CompanionException(CompanionError("bad_actions", "bad", it)) }
        posts += body
        val results = JSONArray()
        val actions = body.getJSONArray("actions")
        for (i in 0 until actions.length()) {
            val a = actions.getJSONObject(i)
            val top = items.minOfOrNull { it.getDouble("order") } ?: 1.0
            if (a.getString("op") == "add") items = items + JSONObject().put("id", HouseholdListIds.itemId(a.getString("text")))
                .put("text", a.getString("text")).put("done", false).put("order", top - 1.0).put("addedBy", body.getString("device")).put("addedAt", a.getLong("at"))
                .put("updatedAt", a.getLong("at"))
            val id = a.optString("id")
            when (a.getString("op")) {
                "tick" -> items = items.map { if (it.getString("id") == id) JSONObject(it.toString()).put("done", true).put("doneAt", a.getLong("at")) else it }
                "clear_done" -> { val ids = a.getJSONArray("ids").let { arr -> (0 until arr.length()).map(arr::getString) }
                    removed = removed + items.filter { it.getString("id") in ids }; items = items.filterNot { it.getString("id") in ids } }
                "restore" -> { items = items + removed.filter { it.getString("id") == id }; removed = removed.filterNot { it.getString("id") == id } }
            }
            results.put(JSONObject().put("aid", a.getString("aid")).put("status", "applied"))
        }
        return state().put("results", results).put("stale", false)
    }
}
