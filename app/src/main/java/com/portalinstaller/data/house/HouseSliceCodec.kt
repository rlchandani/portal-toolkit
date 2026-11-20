/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.house

import com.portalinstaller.data.house.HouseCodec.bool
import com.portalinstaller.data.house.HouseCodec.dbl
import com.portalinstaller.data.house.HouseCodec.int
import com.portalinstaller.data.house.HouseCodec.str
import com.portalinstaller.data.house.HouseCodec.strings
import com.portalinstaller.data.house.HousePlanCodec.objects
import org.json.JSONObject

/** The derived slices of a state answer: Security, cameras, scenes and room summaries (pure). */
internal object HouseSliceCodec {
    fun security(o: JSONObject?): HouseSecurity {
        if (o == null) return HouseSecurity()
        val r = o.optJSONObject("readiness")
        return HouseSecurity(
            alarm = o.optJSONObject("alarm")?.let(::alarm),
            readinessTitle = r?.optString("title").orEmpty(),
            readinessLine = r?.str("line"),
            sections = objects(o.optJSONArray("sections")).map(::section),
            people = objects(o.optJSONArray("people")).map { HousePerson(it.optString("id"), it.optString("name"), it.optBoolean("home"), it.optString("line")) },
            checks = objects(o.optJSONArray("checks")).map {
                HouseCheck(it.optString("icon"), it.optString("title"), it.optString("detail"), it.str("tag"), it.bool("ok"))
            },
        )
    }

    private fun alarm(o: JSONObject) = HouseAlarm(o.optString("id"), o.optString("state"), o.optString("title"), o.optString("subtitle"),
        o.optString("tone"), o.optBoolean("code"), strings(o.optJSONArray("modes")), o.str("current"))

    private fun section(o: JSONObject) = HouseSecSection(o.optString("group"), o.optString("title"), o.optString("icon"),
        o.optString("tag"), o.bool("ok"), o.optString("summary"),
        objects(o.optJSONArray("items")).map { i ->
            HouseSecItem(i.optString("id"), i.optString("name"), i.str("area"), i.optString("state"), i.optLong("since"),
                i.optBoolean("attention"), i.optBoolean("unavailable"), i.str("action"))
        })

    fun camera(o: JSONObject) = HouseCamera(o.optString("id"), o.optString("name"), o.optBoolean("canStream"),
        o.optLong("captured").takeIf { o.has("captured") && it > 0 }, o.str("platform"), o.optString("why"), o.optBoolean("refresh"),
        o.optBoolean("unavailable"), o.str("room"))

    fun scene(o: JSONObject) = HouseScene(o.optString("id"), o.optString("name"), o.optString("tone", "neutral"),
        o.optString("icon", "sparkle"), o.optBoolean("sensitive"))

    fun room(o: JSONObject) = HouseRoomView(o.optString("id"), o.optString("floor"), o.optInt("lightsOn"), o.optInt("lights"),
        o.dbl("temp"), o.int("humidity"), o.optBoolean("motion"), o.optInt("open"), o.dbl("powerW"), o.str("climate"),
        objects(o.optJSONArray("glows")).map { g ->
            HouseGlow(g.optDouble("fx", 0.5).toFloat(), g.optDouble("fy", 0.5).toFloat(), g.optInt("rgb"), g.optDouble("k", 1.0).toFloat())
        },
        occupancy = o.optDouble("occ", 0.0).toFloat().takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f,
        people = objects(o.optJSONArray("ppl")).mapNotNull(::person).take(MAX_ROOM_PEOPLE))

    /** One placed person; anything malformed is dropped (the Portal already decided who may appear). */
    private fun person(p: JSONObject): HouseRoomPerson? {
        val name = p.optString("n").trim().take(MAX_NAME).takeIf { it.isNotEmpty() } ?: return null
        val initial = p.optString("i").trim().take(2).ifEmpty { name.take(1).uppercase() }
        return HouseRoomPerson(p.optString("k").take(MAX_KEY), name, initial, p.optInt("c") and RGB_MASK)
    }

    private const val MAX_ROOM_PEOPLE = 4
    private const val MAX_NAME = 20
    private const val MAX_KEY = 80
    private const val RGB_MASK = 0xFFFFFF
}

/** The Tesla screen's answer (pure). */
internal object HouseTeslaCodec {
    fun tesla(o: JSONObject) = HouseTesla(
        label = o.optString("label", "Tesla"), model = o.str("model"), life = o.optString("life", "awake"),
        paintTop = o.optInt("paintTop", GRAPHITE_TOP), paintBottom = o.optInt("paintBottom", GRAPHITE_BOTTOM), paintName = o.optString("paintName"),
        battery = o.int("battery"), range = o.str("range"), headline = o.optString("headline"), charging = o.optBoolean("charging"),
        powerKw = o.dbl("powerKw"), addedKwh = o.dbl("addedKwh"), toFull = o.str("toFull"), limit = o.int("limit"),
        limitMin = o.optInt("limitMin", DEFAULT_LIMIT_MIN), limitMax = o.optInt("limitMax", MAX_PCT), limitSettable = o.optBoolean("limitEntity"),
        idleTitle = o.optString("idleTitle"), idleDetail = o.str("idleDetail"),
        rows = objects(o.optJSONArray("rows")).map { HouseCarRow(it.optString("icon"), it.optString("label"), it.optString("value"), it.optString("tone")) },
        actions = objects(o.optJSONArray("actions")).map {
            HouseCarAction(it.optString("key"), it.optString("icon"), it.optString("label"), it.optBoolean("on"), it.optBoolean("confirm"))
        },
        charger = o.optJSONObject("charger")?.let { c ->
            HouseCharger(c.optString("source"), c.dbl("powerKw"), c.dbl("sessionKwh"), c.str("status"), c.dbl("cost"))
        },
        week = o.optJSONObject("week")?.let { w ->
            val kwh = w.optJSONArray("kwh")
            HouseChargeWeek(List(kwh?.length() ?: 0) { kwh?.optDouble(it, 0.0) ?: 0.0 }, strings(w.optJSONArray("days")), w.dbl("price"))
        },
    )

    private const val GRAPHITE_TOP = 0x5A5F66
    private const val GRAPHITE_BOTTOM = 0x2E3236
    private const val DEFAULT_LIMIT_MIN = 50
    private const val MAX_PCT = 100
}
