/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.house

import com.portalinstaller.data.house.HousePlanCodec.objects
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads `/remote/controlcenter` answers (pure, unit tested) and folds them into the snapshot the
 * phone already holds: an `unchanged` answer keeps everything but the connection, a delta replaces
 * only the entities it names, a `full` answer replaces them all. Never mutates [previous].
 */
object HouseCodec {
    const val VERSION = 1
    private const val MAX_ENTITIES = 2_000

    fun merge(previous: HouseSnapshot?, json: JSONObject): HouseSnapshot {
        val base = previous ?: HouseSnapshot()
        val head = base.copy(rev = json.optLong("rev"), minute = json.optLong("minute", -1L), now = json.optLong("now"),
            portal = json.optString("portal", base.portal), conn = conn(json.optJSONObject("conn")),
            planAt = json.optLong("planAt", -1L), hasPlan = json.optBoolean("hasPlan"))
        if (json.optBoolean("unchanged")) return head
        val changed = objects(json.optJSONArray("entities")).take(MAX_ENTITIES).mapNotNull(::entity)
        val removed = strings(json.optJSONArray("removed")).toSet()
        val start = if (json.optBoolean("full") || previous == null) emptyMap() else base.entities
        val entities = (start - removed) + changed.associateBy { it.id }
        return head.copy(entities = entities, asks = asks(json.optJSONObject("asks")), fahrenheit = json.optBoolean("fahrenheit"),
            alerts = objects(json.optJSONArray("alerts")).map(::alert),
            security = HouseSliceCodec.security(json.optJSONObject("security")),
            cameras = objects(json.optJSONArray("cameras")).map(HouseSliceCodec::camera),
            scenes = objects(json.optJSONArray("scenes")).map(HouseSliceCodec::scene),
            rooms = objects(json.optJSONArray("rooms")).map(HouseSliceCodec::room),
            tesla = json.optJSONObject("tesla")?.let(HouseTeslaCodec::tesla))
    }

    fun conn(o: JSONObject?): HouseConn = if (o == null) HouseConn() else HouseConn(o.optString("phase", "connecting"),
        o.optString("step"), o.optLong("lastLiveAt"), o.optLong("retryAt"), o.str("reason"))

    private fun asks(o: JSONObject?): HouseAsks = if (o == null) HouseAsks() else HouseAsks(o.optBoolean("unlock", true),
        o.optBoolean("garage", true), o.optBoolean("valve", true), o.optBoolean("scenes", true))

    fun entity(o: JSONObject): HouseEntity? {
        val id = o.optString("id").takeIf { it.contains('.') } ?: return null
        return HouseEntity(id, o.optString("s"), o.optString("n").ifBlank { pretty(id) }, o.optLong("lc"), attrs(o.optJSONObject("a")))
    }

    private fun attrs(o: JSONObject?): Map<String, Any?> {
        if (o == null) return emptyMap()
        val out = HashMap<String, Any?>()
        o.keys().forEach { k -> out[k] = value(o.opt(k)) }
        return out
    }

    private fun value(v: Any?): Any? = when (v) {
        is JSONArray -> (0 until v.length()).map { value(v.opt(it)) }
        JSONObject.NULL -> null
        is JSONObject -> null
        else -> v
    }

    private fun alert(o: JSONObject) = HouseAlert(o.optString("id"), o.optString("sev"), o.optString("kind"), o.optString("entity"),
        o.optString("title"), o.optString("body"), o.optLong("since"), o.optString("icon", "warn"), o.optString("action"),
        o.str("label"), o.str("act"), o.str("actEntity"), o.str("room"))

    fun confirm(details: JSONObject?): HouseConfirm? {
        val d = details ?: return null
        val ticket = d.str("ticket") ?: return null
        return HouseConfirm(d.optString("ask", "confirm"), ticket, d.optString("title", "Are you sure?"), d.optString("body"),
            d.optString("button", "Confirm"), d.optBoolean("code"), d.str("camera"))
    }

    internal fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optString(it).ifBlank { null } }

    internal fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k).takeIf { it.isNotBlank() }

    internal fun JSONObject.dbl(k: String): Double? = if (isNull(k) || !has(k)) null else optDouble(k).takeIf { it.isFinite() }

    internal fun JSONObject.int(k: String): Int? = if (isNull(k) || !has(k)) null else optInt(k)

    internal fun JSONObject.bool(k: String): Boolean? = if (isNull(k) || !has(k)) null else optBoolean(k)

    private fun pretty(id: String) = id.substringAfter('.').replace('_', ' ').replaceFirstChar { it.uppercase() }
}
