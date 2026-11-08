/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.guests

import java.time.ZoneOffset
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phone side of the Guest card: wire parsing, request bodies, validation and copy. */
class GuestModelsTest {
    private fun snapshotJson(active: Boolean = false, until: Any? = JSONObject.NULL) = JSONObject().put("ok", true)
        .put("card", JSONObject().put("ssid", "Home Net").put("security", "wpa").put("hiddenNetwork", false)
            .put("hidePassword", true).put("welcome", "Hi!").put("doorCode", "4821").put("checkout", "11:00")
            .put("houseNote", "Bins Tuesday").put("emergency", "Rohit 555"))
        .put("hasPassword", true).put("ready", true)
        .put("mode", JSONObject().put("active", active).put("until", until))

    @Test fun parsesASnapshot() {
        val s = GuestWire.parse(snapshotJson(active = true, until = 1_700_000_000_000L))!!
        assertEquals("Home Net", s.card.ssid)
        assertEquals("4821", s.card.doorCode)
        assertTrue(s.hasPassword)
        assertTrue(s.active)
        assertEquals(1_700_000_000_000L, s.untilMs)
    }

    @Test fun rejectsAMalformedSnapshot() {
        assertNull(GuestWire.parse(JSONObject().put("ok", true)))
        assertNull(GuestWire.parse(null))
    }

    @Test fun unknownSecurityReadsAsWpa() {
        val json = snapshotJson().apply { getJSONObject("card").put("security", "bogus") }
        assertEquals("wpa", GuestWire.parse(json)!!.card.security)
    }

    @Test fun saveBodyCarriesTheCardAndOnlyANewPassword() {
        val card = GuestCard(ssid = "Net", doorCode = "1")
        val keep = GuestWire.saveBody(card, password = "")
        assertEquals("save", keep.getString("action"))
        assertEquals("Net", keep.getJSONObject("card").getString("ssid"))
        assertFalse(keep.has("password"))
        assertEquals("s3cret!", GuestWire.saveBody(card, password = "s3cret!").getString("password"))
        assertFalse(GuestWire.saveBody(card, password = "").getJSONObject("card").has("password"))
    }

    @Test fun scheduleBodiesMatchThePortal() {
        assertEquals(2, GuestWire.startBody(GuestStart.ForDays(2)).getInt("days"))
        val tomorrow = GuestWire.startBody(GuestStart.UntilTomorrow(11, 0))
        assertEquals(11, tomorrow.getInt("untilHour"))
        assertEquals(0, tomorrow.getInt("untilMinute"))
        val ended = GuestWire.startBody(GuestStart.UntilEnded)
        assertEquals("start", ended.getString("action"))
        assertFalse(ended.has("days") || ended.has("untilHour"))
        assertEquals("end", GuestWire.endBody().getString("action"))
    }

    @Test fun draftValidation() {
        assertNull(GuestCard(ssid = "Net").problem(password = "", hasPassword = true))
        assertEquals("Add the Wi-Fi network name.", GuestCard(ssid = " ").problem("", hasPassword = false))
        assertEquals("Add the Wi-Fi password.", GuestCard(ssid = "Net").problem("", hasPassword = false))
        assertNull(GuestCard(ssid = "Cafe", security = "none").problem("", hasPassword = false))
        assertEquals("Keep each line under 120 characters.", GuestCard(ssid = "Net", houseNote = "x".repeat(121)).problem("", true))
        assertEquals("The password is too long.", GuestCard(ssid = "Net").problem("p".repeat(65), true))
        assertEquals("Use one line for each item.", GuestCard(ssid = "Net", welcome = "a\nb").problem("", true))
    }

    @Test fun statusCopy() {
        val zone = ZoneOffset.UTC
        assertEquals("Off", GuestCopy.status(active = false, untilMs = null, zone = zone))
        assertEquals("On until you end it", GuestCopy.status(active = true, untilMs = null, zone = zone))
        // 2026-11-08 11:00 UTC is a Sunday.
        assertEquals("On until Sun 11:00", GuestCopy.status(true, 1_794_135_600_000L, zone))
        assertEquals("Saved on Kitchen and Hall.", GuestCopy.done("Saved", listOf("Kitchen", "Hall"), emptyList()))
        assertEquals("Saved on Kitchen. Couldn’t reach Hall.", GuestCopy.done("Saved", listOf("Kitchen"), listOf("Hall")))
        assertEquals("Couldn’t reach Kitchen, Hall and Den.", GuestCopy.done("Saved", emptyList(), listOf("Kitchen", "Hall", "Den")))
    }
}
