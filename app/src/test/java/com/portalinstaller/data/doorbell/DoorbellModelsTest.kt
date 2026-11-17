/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.doorbell

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Untrusted Portal events are validated; one press from many Portals is announced once, fresh only. */
class DoorbellModelsTest {
    private fun json(id: String = "01JCX7Q4ZK3M8N2P5R6S7T8V9W", age: Long = 2_000L, kind: String = "ring") = JSONObject()
        .put("id", id).put("kind", kind).put("title", "Someone’s at the door").put("camera", "Front door")
        .put("room", "Living Area").put("ageMs", age)

    private fun event(id: String, age: Long) = DoorbellEvent.parse(json(id, age))!!

    @Test fun parsesAndValidates() {
        val e = DoorbellEvent.parse(json())!!
        assertEquals("Front door · Living Area", e.detail)
        assertEquals(false, e.motion)
        assertEquals(true, DoorbellEvent.parse(json(kind = "motion"))!!.motion)
        assertNull(DoorbellEvent.parse(json(id = "bad id")))
        assertNull(DoorbellEvent.parse(json(kind = "knock")))
        assertNull(DoorbellEvent.parse(json(age = -5)))
        assertNull(DoorbellEvent.parse(json().put("room", "  ")))
        assertEquals("Living Area", DoorbellEvent.parse(json().put("camera", ""))!!.detail)
    }

    @Test fun onePressFromSeveralPortalsIsOneEventKeepingTheFreshestCopy() {
        val merged = DoorbellBannerPolicy.merge(listOf(listOf(event("aaaaaaaa", 4_000L)), listOf(event("aaaaaaaa", 1_000L))))
        assertEquals(1, merged.size)
        assertEquals(1_000L, merged.single().ageMs)
    }

    @Test fun announcesOnlyFreshUnseenEventsNewestFirst() {
        val reports = listOf(listOf(event("aaaaaaaa", 5_000L), event("bbbbbbbb", 1_000L), event("cccccccc", 120_000L)))
        val first = DoorbellBannerPolicy.announce(reports, emptyList())
        assertEquals(listOf("bbbbbbbb", "aaaaaaaa"), first.fresh.map { it.id })
        val again = DoorbellBannerPolicy.announce(reports, first.remembered)
        assertTrue(again.fresh.isEmpty())
    }

    @Test fun rememberedIdsAreBounded() {
        var remembered = emptyList<String>()
        repeat(DoorbellBannerPolicy.MAX_REMEMBERED + 10) { remembered = DoorbellBannerPolicy.remember(remembered, listOf("id%06d".format(it))) }
        assertEquals(DoorbellBannerPolicy.MAX_REMEMBERED, remembered.size)
        assertEquals("id%06d".format(DoorbellBannerPolicy.MAX_REMEMBERED + 9), remembered.last())
    }
}
