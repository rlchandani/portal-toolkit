/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.house

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Camera liveness, the screen states, control bodies, optimistic looks and row wording. */
class HouseRulesTest {
    @Test fun liveNeedsTwoRealChangesWithin15s() {
        var w = HouseCamWatch().frame(1, 1_000, null)
        assertFalse(HouseCamLiveness.label(w, 1_000).live)
        w = w.frame(2, 2_000, null)
        assertFalse("one change is a snapshot", HouseCamLiveness.label(w, 2_000).live)
        w = w.frame(3, 3_000, null)
        assertTrue(HouseCamLiveness.label(w, 3_000).live)
        assertFalse("stale after the window", HouseCamLiveness.label(w, 3_000 + HouseCamLiveness.LIVE_WINDOW_MS + 1).live)
    }

    @Test fun sameBytesNeverLookLiveAndCaptureTimeWins() {
        var w = HouseCamWatch()
        repeat(5) { w = w.frame(42, 1_000L + it * 1_000, null) }
        assertEquals("Snapshot · checking", HouseCamLiveness.label(w, 5_000).text)
        assertEquals("Snapshot · unchanged 1 min", HouseCamLiveness.label(w, 61_000).text)
        val now = 5 * 3_600_000L
        val shot = HouseCamWatch().frame(7, now, captured = now - 3 * 3_600_000L)
        assertEquals("Snapshot · 3 h ago", HouseCamLiveness.label(shot, now).text)
        assertEquals(HouseCamLiveness.hash(byteArrayOf(1, 2)), HouseCamLiveness.hash(byteArrayOf(1, 2)))
    }

    @Test fun statusOrder() {
        val live = HouseSnapshot(conn = HouseConn("live"), entities = mapOf("a.b" to HouseEntity("a.b", "on", "A")))
        assertEquals(HouseStatus.LIVE, HouseStatusRules.status(live, HouseReach.OK, 1_000, 2_000))
        assertEquals(HouseStatus.STALE, HouseStatusRules.status(live, HouseReach.OK, 1_000, 1_000 + HouseStatusRules.STALE_MS + 1))
        assertEquals(HouseStatus.HA_UNREACHABLE, HouseStatusRules.status(live.copy(conn = HouseConn("unreachable")), HouseReach.OK, 0, 0))
        assertEquals(HouseStatus.TOKEN_REFUSED, HouseStatusRules.status(live.copy(conn = HouseConn("auth_failed")), HouseReach.OK, 0, 0))
        assertEquals(HouseStatus.LOADING, HouseStatusRules.status(null, HouseReach.OK, 0, 0))
        assertEquals(HouseStatus.PORTAL_OFFLINE, HouseStatusRules.status(null, HouseReach.OFFLINE, 0, 0))
        assertEquals(HouseStatus.CONNECTING, HouseStatusRules.status(HouseSnapshot(), HouseReach.OK, 0, 0))
        assertTrue(HouseStatusRules.canControl(HouseStatus.LIVE))
        assertFalse(HouseStatusRules.canControl(HouseStatus.STALE))
        assertEquals("Reconnecting… values from 7:36 pm", HouseStatusRules.banner(HouseStatus.STALE, "Kitchen", "7:36 pm"))
    }

    @Test fun actBodiesNameEntityAndActionOnly() {
        val lamp = HouseEntity("light.lamp", "on", "Lamp")
        val b = HouseAct.brightness(lamp, 140).body()
        assertEquals(100, b.getInt("value"))
        assertFalse(b.has("ticket"))
        val confirmed = HouseAct.unlock(HouseEntity("lock.f", "locked", "Front")).body(ticket = "t", code = "")
        assertEquals("t", confirmed.getString("ticket"))
        assertFalse("an empty code is never sent", confirmed.has("code"))
        assertEquals("cool", HouseAct.hvac(HouseEntity("climate.c", "heat", "C"), "cool").body().getString("value"))
        assertNull(HouseAct.fix("explode", lamp))
        assertEquals("close", HouseAct.fix("close", HouseEntity("cover.g", "open", "G"))?.action)
    }

    @Test fun optimisticLooksAndNoMutation() {
        val s = HouseSnapshot(entities = mapOf("light.a" to HouseEntity("light.a", "off", "A")))
        val next = HouseOptimistic.apply(s, HouseAct("brightness", "light.a", "", value = 50))
        assertEquals("on", next.entity("light.a")?.state)
        assertEquals(50, next.entity("light.a")?.brightnessPct)
        assertEquals("off", s.entity("light.a")?.state)
        assertEquals(s, HouseOptimistic.apply(s, HouseAct("power", "light.missing", "", on = true)))
    }

    @Test fun rowWords() {
        assertEquals("72%", HouseWords.status(HouseEntity("light.a", "on", "A", attrs = mapOf("brightness" to 184))))
        assertEquals("WATER DETECTED", HouseWords.status(HouseEntity("binary_sensor.l", "on", "L", attrs = mapOf("device_class" to "moisture"))))
        assertEquals("Locked · Sam", HouseWords.status(HouseEntity("lock.f", "locked", "F", attrs = mapOf("changed_by" to "Sam"))))
        assertEquals("Set 21° · Heating · 20° now", HouseWords.status(HouseEntity("climate.h", "heat", "H",
            attrs = mapOf("temperature" to 21, "hvac_action" to "heating", "current_temperature" to 20.4))))
        assertEquals("Not responding", HouseWords.status(HouseEntity("light.x", "unavailable", "X")))
        assertEquals("71° · 2 on · 1 open", HouseWords.roomLine(HouseRoomView("k", "g", 2, 3, 71.2, null, false, 1, null, null, emptyList()), emptyList()))
    }

    @Test fun copyNeverShowsRawCodes() {
        assertEquals("That device isn't on the Portal any more.", HouseCopy.refused("unknown_entity", null))
        assertEquals("The Portal didn't accept that.", HouseCopy.refused("bad_action", null))
    }
}
