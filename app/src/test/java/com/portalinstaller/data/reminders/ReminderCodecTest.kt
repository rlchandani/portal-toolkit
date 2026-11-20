/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.reminders

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Portal's answer is untrusted: bad entries are skipped, a bad envelope reads as null. */
class ReminderCodecTest {
    private fun reminder(id: Any?, title: Any? = "Bins out") = JSONObject().put("id", id).put("title", title)

    private fun envelope(vararg reminders: JSONObject) = JSONObject().put("ok", true).put("ready", true).put("portal", "Kitchen")
        .put("reminders", JSONArray(reminders.toList()))

    @Test fun readsAFullAnswer() {
        val json = envelope(JSONObject().put("id", "r1").put("title", "  Call   the vet ").put("person", "p_maya").put("for", "Maya")
            .put("due", 1_800_000_000_000L).put("done", true).put("by", "Phone").put("seq", 7))
            .put("privateCounts", JSONObject().put("Maya", 2))
            .put("people", JSONArray().put(JSONObject().put("id", "p_maya").put("name", "Maya").put("kid", false))
                .put(JSONObject().put("id", "p_leo").put("name", "Leo").put("kid", true)))
        val board = ReminderCodec.decode(json)!!
        assertTrue(board.ready)
        assertEquals("Kitchen", board.portal)
        assertEquals(Reminder("r1", "Call the vet", "p_maya", "Maya", 1_800_000_000_000L, true, "Phone", 7), board.reminders.single())
        assertEquals(mapOf("Maya" to 2), board.privateCounts)
        assertEquals(listOf(ReminderPerson("p_maya", "Maya", false), ReminderPerson("p_leo", "Leo", true)), board.people)
    }

    @Test fun aMissingListOrEnvelopeIsNull() {
        assertNull(ReminderCodec.decode(null))
        assertNull(ReminderCodec.decode(JSONObject().put("ok", true)))
        assertNull(ReminderCodec.decode(JSONObject().put("reminders", "nope")))
    }

    @Test fun readyMustBeTrueAndDefaultsAreSafe() {
        val board = ReminderCodec.decode(JSONObject().put("ready", "true").put("reminders", JSONArray().put(reminder("r1"))))!!
        assertFalse(board.ready)
        assertEquals("", board.portal)
        val r = board.reminders.single()
        assertEquals(Reminder("r1", "Bins out"), r)
        assertTrue(board.people.isEmpty() && board.privateCounts.isEmpty())
    }

    @Test fun badEntriesAreSkipped() {
        val json = envelope(reminder("r1"), reminder(null), reminder("bad id!"), reminder("r2", "   "), reminder("r3", 42),
            reminder("r1", "Duplicate"))
            .put("people", JSONArray().put(JSONObject().put("id", "p1")).put("junk").put(JSONObject().put("id", "p2").put("name", "Ana")))
        json.getJSONArray("reminders").put("not an object")
        val board = ReminderCodec.decode(json)!!
        assertEquals(listOf("r1"), board.reminders.map { it.id })
        assertEquals("Bins out", board.reminders.single().title)
        assertEquals(listOf(ReminderPerson("p2", "Ana", false)), board.people)
    }

    @Test fun numbersAreChecked() {
        val r = ReminderCodec.reminder(JSONObject().put("id", "r1").put("title", "T").put("due", -5).put("seq", 1.5)
            .put("done", "yes").put("person", "has space").put("for", 3))!!
        assertEquals(0L, r.due)
        assertEquals(0L, r.seq)
        assertFalse(r.done)
        assertEquals("", r.person)
        assertEquals(ReminderLimits.HOME, r.forName)
        assertEquals(0L, ReminderCodec.reminder(JSONObject().put("id", "r1").put("title", "T").put("due", "1800"))!!.due)
        assertEquals(1_000L, ReminderCodec.reminder(JSONObject().put("id", "r1").put("title", "T").put("due", 1000.0))!!.due)
        assertEquals(0L, ReminderCodec.reminder(JSONObject().put("id", "r1").put("title", "T").put("due", 9.9e18))!!.due)
    }

    @Test fun privateCountsKeepOnlyPositiveWholeCounts() {
        val counts = JSONObject().put("Maya", 2).put("Sam", 0).put("Ana", -1).put("Raj", "3").put("Kim", 1.5).put("  ", 4)
        val board = ReminderCodec.decode(envelope().put("privateCounts", counts))!!
        assertEquals(mapOf("Maya" to 2), board.privateCounts)
    }

    @Test fun longTextIsTrimmedToItsLimit() {
        val r = ReminderCodec.reminder(reminder("r1", "x".repeat(500)))!!
        assertEquals(ReminderLimits.TITLE_MAX, r.title.length)
    }
}
