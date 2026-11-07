/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.dinner

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phone side of Call everyone: request bodies, response validation and copy. */
class DinnerModelsTest {
    @Test fun presetsMatchThePortal() {
        assertEquals(listOf("dinner", "down", "leave", "bed", "lights", "home10"), DinnerPresets.all.map { it.id })
        assertEquals("Home in 10 minutes", DinnerPresets.all.last().text)
    }

    @Test fun requestBodies() {
        val preset = DinnerRequest.body(PhoneMessage.Preset("dinner"), listOf("fa", "la"), "Rohit’s Pixel")
        assertEquals("preset", preset.getString("kind"))
        assertEquals("dinner", preset.getString("preset"))
        assertEquals(2, preset.getJSONArray("rooms").length())
        assertEquals("Rohit’s Pixel", preset.getString("sender"))
        val custom = DinnerRequest.body(PhoneMessage.custom(" Pizza’s\nhere ")!!, listOf("fa"), "P")
        assertEquals("Pizza’s here", custom.getString("text"))
        assertEquals("ping", DinnerRequest.body(PhoneMessage.Ping, listOf("fa"), "P").getString("kind"))
        val retry = DinnerRequest.retry("a1b2c3d4", "la")
        assertEquals("la", retry.getJSONObject("retry").getString("room"))
        assertNull(PhoneMessage.custom("x".repeat(41)))
        assertNull(PhoneMessage.custom("  "))
        assertEquals("x".repeat(40), PhoneMessage.limit("x".repeat(50)))
    }

    private fun row(room: String, status: String, reply: Any? = JSONObject.NULL) = JSONObject().put("room", room)
        .put("name", room.uppercase()).put("status", status).put("text", "t").put("retry", status == "noanswer").put("reply", reply)

    private fun send(vararg rows: JSONObject, pending: Boolean = false) = JSONObject().put("id", "a1b2c3d4").put("text", "Dinner’s ready")
        .put("pending", pending).put("rows", JSONArray(rows.toList()))

    @Test fun parsesSendsAndDropsJunk() {
        val parsed = DinnerResult.parse(send(row("fa", "spoken", "On my way"), row("la", "noanswer")))!!
        assertEquals("Dinner’s ready", parsed.text)
        assertEquals(DinnerRowStatus.SPOKEN, parsed.rows[0].status)
        assertEquals("On my way", parsed.rows[0].reply)
        assertTrue(parsed.rows[1].retry)
        assertNull(DinnerResult.parse(JSONObject().put("id", "../x").put("rows", JSONArray())))
        // Unknown statuses and impossible replies don't become UI.
        val odd = DinnerResult.parse(send(row("fa", "exploded"), row("la", "spoken", "Hack the planet")))!!
        assertEquals(DinnerRowStatus.UNKNOWN, odd.rows[0].status)
        assertNull(odd.rows[1].reply)
    }

    @Test fun titlesAndRowCopy() {
        fun r(s: DinnerRowStatus) = DinnerResultRow("x", "X", s, false, null)
        val sending = DinnerResult("a1b2c3d4", "Dinner’s ready", true, listOf(r(DinnerRowStatus.SENDING)))
        assertEquals("Sending “Dinner’s ready”", DinnerCopy.title(sending))
        assertEquals("Sent “Dinner’s ready”", DinnerCopy.title(sending.copy(pending = false, rows = listOf(r(DinnerRowStatus.SPOKEN)))))
        assertEquals("Sent to 1 of 2 Portals", DinnerCopy.title(sending.copy(pending = false, rows = listOf(r(DinnerRowStatus.SPOKEN), r(DinnerRowStatus.NO_ANSWER)))))
        assertEquals("Not delivered", DinnerCopy.title(sending.copy(pending = false, rows = listOf(r(DinnerRowStatus.NO_ANSWER)))))
        assertEquals("Offline, not delivered", DinnerRowStatus.OFFLINE.text)
        assertEquals("Shown quietly, quiet hours", DinnerRowStatus.QUIET.text)
    }

    @Test fun subtitleSaysWhoHearsIt() {
        assertEquals("Both Portals chime and say it once.", DinnerCopy.subtitle(listOf("Family Area", "Living Area"), emptyList(), false))
        assertEquals("Living Area chimes and says it once.", DinnerCopy.subtitle(listOf("Living Area"), emptyList(), false))
        assertEquals("Living Area is in quiet hours, so no sound there.", DinnerCopy.subtitle(listOf("Family Area", "Living Area"), listOf("Living Area"), false))
        assertEquals("Waiting for a connection.", DinnerCopy.subtitle(listOf("Living Area"), emptyList(), true))
    }

    @Test fun newRepliesAreDetectedOnce() {
        val before = DinnerResult.parse(send(row("fa", "spoken")))!!
        val after = DinnerResult.parse(send(row("fa", "spoken", "Coming")))!!
        assertEquals(listOf(DinnerReplyEvent("a1b2c3d4", "FA", "Coming")), DinnerCopy.newReplies(before, after))
        assertTrue(DinnerCopy.newReplies(after, after).isEmpty())
        assertFalse(DinnerCopy.newReplies(null, after).isEmpty())
    }
}
