/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.house

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HouseCodecTest {
    private fun e(id: String, s: String, attrs: JSONObject = JSONObject()) = JSONObject().put("id", id).put("s", s).put("n", id).put("lc", 5).put("a", attrs)

    private fun answer(rev: Long, full: Boolean, vararg entities: JSONObject, removed: List<String> = emptyList()) = JSONObject()
        .put("ok", true).put("rev", rev).put("minute", 10).put("now", 600_000).put("portal", "Kitchen")
        .put("conn", JSONObject().put("phase", "live")).put("full", full).put("entities", JSONArray(entities.toList()))
        .put("removed", JSONArray(removed)).put("alerts", JSONArray()).put("planAt", 7).put("hasPlan", true)

    @Test fun fullThenDeltaThenUnchanged() {
        val first = HouseCodec.merge(null, answer(1, true, e("light.a", "on", JSONObject().put("brightness", 255)), e("lock.f", "locked")))
        assertEquals(2, first.entities.size)
        assertEquals(100, first.entity("light.a")?.brightnessPct)
        val delta = HouseCodec.merge(first, answer(2, false, e("light.a", "off"), removed = listOf("lock.f")))
        assertEquals("off", delta.entity("light.a")?.state)
        assertNull(delta.entity("lock.f"))
        val same = HouseCodec.merge(delta, JSONObject().put("rev", 2).put("unchanged", true).put("conn", JSONObject().put("phase", "stale")))
        assertEquals(delta.entities, same.entities)
        assertEquals("stale", same.conn.phase)
        assertEquals("light.a", first.entity("light.a")?.id) // earlier snapshot untouched
        assertEquals("on", first.entity("light.a")?.state)
    }

    private fun scenes(vararg names: String) = JSONArray(names.map { JSONObject().put("id", "scene.$it").put("name", it).put("tone", "warm").put("icon", "sun") })

    @Test fun aSectionLeftOutBecauseItsVersionIsCurrentIsKept() {
        val first = HouseCodec.merge(null, answer(1, true, e("light.a", "on")).put("scenes", scenes("Evening"))
            .put("rooms", JSONArray()).put("tesla", JSONObject.NULL)
            .put("sv", JSONObject().put("alerts", "a1").put("scenes", "s1").put("rooms", "r1").put("tesla", "t0")))
        assertEquals(listOf("Evening"), first.scenes.map { it.name })
        assertEquals(mapOf("alerts" to "a1", "scenes" to "s1", "rooms" to "r1", "tesla" to "t0"), first.sectionVersions)
        // Only the entity delta and the alerts moved: scenes stay as held.
        val delta = JSONObject().put("ok", true).put("rev", 2).put("minute", 10).put("conn", JSONObject().put("phase", "live"))
            .put("full", false).put("entities", JSONArray().put(e("light.a", "off"))).put("removed", JSONArray())
            .put("alerts", JSONArray()).put("sv", JSONObject().put("alerts", "a2").put("scenes", "s1").put("rooms", "r1").put("tesla", "t0"))
        val second = HouseCodec.merge(first, delta)
        assertEquals(listOf("Evening"), second.scenes.map { it.name })
        assertEquals("off", second.entity("light.a")?.state)
        assertEquals("a2", second.sectionVersions["alerts"])
    }

    @Test fun anOlderPortalWithoutVersionsStillReplacesEverySection() {
        val first = HouseCodec.merge(null, answer(1, true).put("scenes", scenes("Evening")).put("sv", JSONObject().put("scenes", "s1")))
        val older = HouseCodec.merge(first, answer(2, false))
        assertEquals("no scenes key from an older Portal means none", emptyList<String>(), older.scenes.map { it.name })
        assertEquals(emptyMap<String, String>(), older.sectionVersions)
    }

    @Test fun aCarThatGoesAwayIsClearedByAnExplicitNull() {
        val car = HouseCodec.merge(null, answer(1, true).put("tesla", JSONObject().put("label", "Model Y").put("headline", "Parked"))
            .put("sv", JSONObject().put("tesla", "t1")))
        assertEquals("Model Y", car.tesla?.label)
        val gone = HouseCodec.merge(car, answer(2, false).put("tesla", JSONObject.NULL).put("sv", JSONObject().put("tesla", "t0")))
        assertNull(gone.tesla)
    }

    @Test fun thePollBodyCarriesTheVersionsHeld() {
        val fresh = HouseCodec.pollBody(null, null)
        assertEquals(0L, fresh.getLong("since"))
        assertEquals("a new phone says it knows versions, holding none", 0, fresh.getJSONObject("sv").length())
        assertFalse(fresh.has("want"))
        val held = HouseSnapshot(rev = 4, minute = 9, sectionVersions = mapOf("rooms" to "r1"))
        val body = HouseCodec.pollBody(held, "tesla")
        assertEquals(4L, body.getLong("since")); assertEquals(9L, body.getLong("minute"))
        assertEquals("r1", body.getJSONObject("sv").getString("rooms"))
        assertEquals("tesla", body.getString("want"))
    }

    @Test fun fullAnswerReplacesEverything() {
        val a = HouseCodec.merge(null, answer(1, true, e("light.a", "on"), e("light.b", "on")))
        val b = HouseCodec.merge(a, answer(9, true, e("light.c", "on")))
        assertEquals(setOf("light.c"), b.entities.keys)
    }

    @Test fun confirmCarriesTheTicket() {
        val c = HouseCodec.confirm(JSONObject().put("ask", "camera_confirm").put("ticket", "ab".repeat(16)).put("title", "Close Garage?")
            .put("button", "Close").put("camera", "camera.garage").put("code", false))
        assertTrue(c!!.withCamera)
        assertEquals("camera.garage", c.camera)
        assertNull(HouseCodec.confirm(JSONObject().put("ask", "confirm")))
    }

    @Test fun slicesParse() {
        val json = answer(1, true).put("security", JSONObject()
            .put("alarm", JSONObject().put("id", "alarm_control_panel.h").put("state", "armed_away").put("title", "Armed · Away")
                .put("modes", JSONArray(listOf("disarm", "home", "away"))).put("code", true).put("current", "away"))
            .put("sections", JSONArray().put(JSONObject().put("group", "garage").put("title", "Garage").put("tag", "1 open").put("ok", false)
                .put("items", JSONArray().put(JSONObject().put("id", "cover.g").put("name", "Garage").put("state", "Open").put("attention", true).put("action", "close"))))))
            .put("tesla", JSONObject().put("label", "Y").put("battery", 80).put("actions", JSONArray().put(JSONObject().put("key", "lock").put("label", "Unlock").put("confirm", true))))
            .put("cameras", JSONArray().put(JSONObject().put("id", "camera.door").put("name", "Door").put("captured", 1000)))
        val s = HouseCodec.merge(null, json)
        assertEquals("away", s.security.alarm?.current)
        assertEquals("close", s.security.sections.single().items.single().action)
        assertFalse(s.security.sections.single().ok ?: true)
        assertEquals(80, s.tesla?.battery)
        assertTrue(s.tesla!!.actions.single().confirm)
        assertEquals(1000L, s.cameras.single().captured)
    }

    @Test fun planUsesThePortalsCompactKeys() {
        val plan = HousePlanCodec.decode(JSONObject().put("v", 1).put("updatedAt", 7).put("floors", JSONArray().put(JSONObject().put("id", "g").put("name", "Ground")
            .put("rooms", JSONArray().put(JSONObject().put("id", "kitchen").put("name", "Kitchen").put("r", JSONArray(listOf(2, 3, 10, 8)))
                .put("d", JSONArray().put(JSONObject().put("e", "light.k").put("x", 0.5).put("y", 0.25)))))
            .put("openings", JSONArray().put(JSONObject().put("e", "binary_sensor.door").put("room", "kitchen").put("side", "S").put("at", 0.5).put("span", 0.2))
                .put(JSONObject().put("e", "binary_sensor.ghost").put("room", "nowhere").put("side", "N"))))))
        val room = plan!!.room("kitchen")!!
        assertEquals(listOf(2, 3, 10, 8), listOf(room.x, room.y, room.w, room.h))
        assertEquals("light.k", room.devices.single().entityId)
        assertEquals(1, plan.floors.single().openings.size)
        assertEquals('S', plan.floors.single().openings.single().side)
    }

    @Test fun badPlanRoomsAreDroppedAndClamped() {
        val plan = HousePlanCodec.decode(JSONObject().put("floors", JSONArray().put(JSONObject().put("id", "g")
            .put("rooms", JSONArray().put(JSONObject().put("id", "big").put("r", JSONArray(listOf(40, 25, 99, 99))))
                .put(JSONObject().put("id", "norect"))))))
        val room = plan!!.floors.single().rooms.single()
        assertEquals(HousePlan.GRID_W, room.w)
        assertEquals(0, room.x)
    }
}
