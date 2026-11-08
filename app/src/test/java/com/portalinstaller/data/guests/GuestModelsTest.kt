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

/** Phone side of the Guest card: wire parsing (v1 and v2 Portals), request bodies and copy. */
class GuestModelsTest {
    private fun v2Json(active: Boolean = false, until: Any? = JSONObject.NULL) = JSONObject().put("ok", true).put("version", 2)
        .put("card", JSONObject().put("ssid", "Home Net").put("security", "sae").put("hiddenNetwork", false)
            .put("hidePassword", true).put("welcome", "Hi!").put("checkout", "11:00")
            .put("goodToKnow", "shoes_off,future_rule,quiet_hours").put("customNotes", "Bins go out Tuesday|Gate sticks")
            .put("contact", "text").put("contactName", "Rohit"))
        .put("hasPassword", true).put("hasDoorCode", true).put("ready", true)
        .put("mode", JSONObject().put("active", active).put("until", until))

    /** What a Calder release from before the setup redesign answers. */
    private fun v1Json() = JSONObject().put("ok", true)
        .put("card", JSONObject().put("ssid", "Old Net").put("security", "wpa").put("welcome", "Hi!")
            .put("doorCode", "4821").put("checkout", "11:00 on Sunday").put("houseNote", "Bins").put("emergency", "555"))
        .put("hasPassword", true).put("ready", true)
        .put("mode", JSONObject().put("active", false).put("until", JSONObject.NULL))

    @Test fun parsesAV2Snapshot() {
        val s = GuestWire.parse(v2Json(active = true, until = 1_700_000_000_000L))!!
        assertEquals(2, s.version)
        assertFalse(s.needsUpdate)
        assertEquals("Home Net", s.card.ssid)
        assertEquals("sae", s.card.security)
        assertEquals("11:00", s.card.checkout)
        assertEquals(listOf("shoes_off", "future_rule", "quiet_hours"), s.card.goodToKnow)
        assertEquals(listOf("Bins go out Tuesday", "Gate sticks"), s.card.customNotes)
        assertEquals("text", s.card.contact)
        assertEquals("Rohit", s.card.contactName)
        assertTrue(s.hasPassword)
        assertTrue(s.hasDoorCode)
        assertTrue(s.active)
        assertEquals(1_700_000_000_000L, s.untilMs)
    }

    @Test fun anOldPortalParsesButNeedsAnUpdate() {
        val s = GuestWire.parse(v1Json())!!
        assertEquals(1, s.version)
        assertTrue(s.needsUpdate)
        assertEquals("Old Net", s.card.ssid)
        assertEquals("", s.card.checkout) // free text is not a check-out time
        assertFalse(s.hasDoorCode)
        assertFalse(s.toString().contains("4821"))
    }

    @Test fun rejectsAMalformedSnapshot() {
        assertNull(GuestWire.parse(JSONObject().put("ok", true)))
        assertNull(GuestWire.parse(null))
    }

    @Test fun unknownSecurityAndContactFallBack() {
        val json = v2Json().apply { getJSONObject("card").put("security", "bogus").put("contact", "carrier_pigeon") }
        val card = GuestWire.parse(json)!!.card
        assertEquals("wpa", card.security)
        assertEquals("none", card.contact)
    }

    @Test fun cardJsonRoundTripsAndKeepsUnknownPresetIds() {
        val card = GuestWire.parse(v2Json())!!.card
        val json = card.toJson()
        assertEquals("shoes_off,future_rule,quiet_hours", json.getString("goodToKnow"))
        assertEquals("Bins go out Tuesday|Gate sticks", json.getString("customNotes"))
        assertEquals(setOf("ssid", "security", "hiddenNetwork", "hidePassword", "welcome", "checkout", "goodToKnow",
            "customNotes", "contact", "contactName"), json.keys().asSequence().toSet())
    }

    @Test fun saveBodyIsVersionedAndCarriesOnlyNewSecrets() {
        val card = GuestCard(ssid = "Net")
        val keep = GuestWire.saveBody(card, password = "", doorCode = DoorCodeChange.Keep)
        assertEquals("save", keep.getString("action"))
        assertEquals(2, keep.getInt("version"))
        assertEquals("Net", keep.getJSONObject("card").getString("ssid"))
        assertFalse(keep.has("password") || keep.has("doorCode") || keep.has("clearDoorCode") || keep.has("clearPassword"))
        val set = GuestWire.saveBody(card, "s3cret!!", DoorCodeChange.Set("4821"))
        assertEquals("s3cret!!", set.getString("password"))
        assertEquals("4821", set.getString("doorCode"))
        assertTrue(GuestWire.saveBody(card, "", DoorCodeChange.Clear).getBoolean("clearDoorCode"))
        assertFalse(set.getJSONObject("card").has("password") || set.getJSONObject("card").has("doorCode"))
    }

    @Test fun anOpenNetworkNeverSendsAPassword() {
        val body = GuestWire.saveBody(GuestCard(ssid = "Cafe", security = "none"), "leftover", DoorCodeChange.Keep)
        assertFalse(body.has("password"))
    }

    @Test fun doorCodeChangeNeverPrintsTheCode() {
        assertFalse(DoorCodeChange.Set("4821").toString().contains("4821"))
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

    @Test fun statusCopy() {
        val zone = ZoneOffset.UTC
        assertEquals("Off", GuestCopy.status(active = false, untilMs = null, zone = zone))
        assertEquals("On until you end it", GuestCopy.status(active = true, untilMs = null, zone = zone))
        // 2026-11-08 11:00 UTC is a Sunday.
        assertEquals("On until Sun 11:00", GuestCopy.status(true, 1_794_135_600_000L, zone))
        assertEquals("Saved on Kitchen and Hall.", GuestCopy.done("Saved", listOf("Kitchen", "Hall"), emptyList()))
        assertEquals("Saved on Kitchen. Couldn’t reach Hall.", GuestCopy.done("Saved", listOf("Kitchen"), listOf("Hall")))
        assertEquals("Couldn’t reach Kitchen, Hall and Den.", GuestCopy.done("Saved", emptyList(), listOf("Kitchen", "Hall", "Den")))
        assertEquals("Saved on Kitchen. Update Calder on Hall to use the new guest setup.",
            GuestCopy.done("Saved", listOf("Kitchen"), emptyList(), outdated = listOf("Hall")))
    }
}
