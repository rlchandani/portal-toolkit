/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.house

import com.portalinstaller.data.house.HouseCodec
import com.portalinstaller.data.house.HouseFloor
import com.portalinstaller.data.house.HouseRoom
import com.portalinstaller.data.house.HouseRoomPerson
import com.portalinstaller.data.house.HouseRoomView
import com.portalinstaller.data.house.HouseSnapshot
import com.portalinstaller.data.house.HouseWords
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** People in rooms on the phone's House map: parsing what the Portal allowed, where it lands, and how it reads. */
class HousePeopleTest {
    private val kitchen = HouseRoom("kitchen", "Kitchen", 0, 0, 10, 10, false, emptyList())
    private val hall = HouseRoom("hall", "Hall", 10, 0, 6, 10, false, emptyList())
    private val floor = HouseFloor("g", "Ground", listOf(kitchen, hall), emptyList())
    private val maya = HouseRoomPerson("a:maya", "Maya", "M", 0xE8834A)
    private val sam = HouseRoomPerson("a:sam", "Sam", "S", 0x2F9C95)

    private fun view(id: String, occ: Float = 0f, people: List<HouseRoomPerson> = emptyList()) =
        HouseRoomView(id, "g", 0, 0, null, null, false, 0, null, null, emptyList(), occ, people)

    private fun snap(vararg rooms: HouseRoomView) = HouseSnapshot(rooms = rooms.toList())

    private fun answer(rooms: JSONArray) = JSONObject().put("ok", true).put("rev", 1).put("minute", 1).put("now", 1)
        .put("conn", JSONObject().put("phase", "live")).put("full", true).put("entities", JSONArray()).put("rooms", rooms)

    @Test fun roomsCarryOccupancyAndAllowedPeople() {
        val rooms = JSONArray()
            .put(JSONObject().put("id", "kitchen").put("floor", "g").put("occ", 0.99)
                .put("ppl", JSONArray().put(JSONObject().put("k", "a:maya").put("n", "Maya").put("i", "M").put("c", 0xFFE8834A.toInt()))
                    .put(JSONObject().put("k", "x").put("n", "  ")) ))
            .put(JSONObject().put("id", "hall").put("floor", "g").put("occ", 7.0))
            .put(JSONObject().put("id", "den").put("floor", "g"))
        val s = HouseCodec.merge(null, answer(rooms))
        val k = s.room("kitchen")!!
        assertEquals(0.99f, k.occupancy, 1e-6f)
        assertEquals(listOf(HouseRoomPerson("a:maya", "Maya", "M", 0xE8834A)), k.people)
        assertEquals(1f, s.room("hall")!!.occupancy, 0f)
        assertEquals(0f, s.room("den")!!.occupancy, 0f)
        assertTrue(s.room("den")!!.people.isEmpty())
    }

    @Test fun anOlderPortalSendsNeither() {
        val s = HouseCodec.merge(null, answer(JSONArray().put(JSONObject().put("id", "kitchen").put("motion", true))))
        assertEquals(0f, s.room("kitchen")!!.occupancy, 0f)
        assertTrue(HousePeopleLayout.pulses(floor, s).isEmpty())
    }

    @Test fun aRingPerOccupiedRoomWithoutChipsAndChipsCentredBelowTheName() {
        val s = snap(view("kitchen", 0.99f, listOf(maya, sam)), view("hall", 0.4f))
        assertEquals(listOf(HousePulse(13f, 7f, 0.4f)), HousePeopleLayout.pulses(floor, s))
        val chips = HousePeopleLayout.chips(floor, s)
        assertEquals(listOf("M", "S"), chips.map { it.initial })
        assertEquals(3.7f, chips[0].x, 1e-4f)
        assertEquals(6.3f, chips[1].x, 1e-4f)
        assertEquals(8.1f, chips[0].y, 1e-4f)
    }

    @Test fun aQuietHouseHasNothingToAnimate() {
        assertTrue(HousePeopleLayout.pulses(floor, snap(view("kitchen"), view("hall"))).isEmpty())
        assertTrue(HousePeopleLayout.chips(floor, snap()).isEmpty())
    }

    @Test fun roomLinesAndTheMapDescriptionSayWho() {
        assertEquals(listOf("Maya", "Sam"), HouseWords.roomPeople(view("kitchen", 1f, listOf(maya, sam))))
        assertEquals(listOf("someone here"), HouseWords.roomPeople(view("hall", 0.5f)))
        assertTrue(HouseWords.roomPeople(view("hall")).isEmpty())
        assertTrue(HouseWords.roomPeople(null).isEmpty())
        assertEquals("someone here", HouseWords.roomLine(view("hall", 0.5f), HouseWords.roomPeople(view("hall", 0.5f))))
        val s = snap(view("kitchen", 1f, listOf(maya, sam)), view("hall", 0.5f))
        assertEquals("Floor plan, Ground, 2 rooms, Maya and Sam in Kitchen, someone in Hall", planDescription(floor, s))
        assertEquals("Floor plan, Ground, 2 rooms", planDescription(floor, snap()))
    }
}
