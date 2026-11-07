/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.routines

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Monday 9 November 2026 is epoch day 20766. */
class RoutineToolkitModelTest {
    private val mon = 20766
    private val morning = RoutineTemplates.create("morning", ids = sequenceOf("r1", "c0", "c1", "c2", "c3", "c4").iterator()::next)

    private fun snapshot(routines: List<Routine> = listOf(morning), today: JSONArray = JSONArray(), minute: Int = 420, day: Int = mon) =
        RoutineWire.parseSnapshot(JSONObject().put("ok", true).put("rev", 4)
            .put("routines", JSONArray().apply { routines.forEach { put(RoutineWire.routineJson(it)) } })
            .put("kids", JSONArray().put(JSONObject().put("id", "maya").put("name", "Maya").put("avatar", "fox")))
            .put("today", today).put("day", day).put("minute", minute))

    @Test fun pictureIdsMatchThePortal() {
        assertEquals(23, RoutinePictures.ids.size)
        assertEquals(listOf("shirt", "bowl", "toothbrush"), RoutinePictures.ids.take(3))
        assertEquals("car", RoutinePictures.ids.last())
        assertEquals(listOf("Morning", "Any time", "Evening"), RoutinePictures.groups)
    }

    @Test fun templatesMatchThePrototype() {
        assertEquals("School morning", morning.name)
        assertEquals(listOf("shirt", "bowl", "toothbrush", "shoes", "backpack"), morning.cards.map { it.pic })
        assertEquals(435 to 475, morning.start to morning.end)
        assertEquals("Brush teeth", morning.cards[2].label)
        val blank = RoutineTemplates.create("blank")
        assertEquals("", blank.name)
        assertTrue(blank.cards.isEmpty())
        assertEquals(420 to 450, blank.start to blank.end)
    }

    @Test fun wireRoundTripsAndSnapshotParses() {
        val s = snapshot()
        assertEquals(4L, s.rev)
        assertEquals(morning, s.routines.single())
        assertEquals("Maya", s.kids.single().name)
        assertEquals(mon, s.day)
    }

    @Test fun malformedSnapshotEntriesAreDropped() {
        val json = JSONObject().put("rev", 1).put("routines", JSONArray().put(JSONObject().put("id", "x")).put(RoutineWire.routineJson(morning)))
            .put("kids", JSONArray()).put("today", JSONArray().put(JSONObject().put("rid", 5))).put("day", mon).put("minute", 0)
        val s = RoutineWire.parseSnapshot(json)
        assertEquals(listOf("r1"), s.routines.map { it.id })
        assertTrue(s.today.isEmpty())
    }

    @Test fun validationCopy() {
        val others = listOf(morning)
        assertEquals("Give the routine a name.", RoutineValidation.check(morning.copy(id = "r2", name = " "), others))
        assertEquals("Add at least one card.", RoutineValidation.check(morning.copy(id = "r2", cards = emptyList()), others))
        assertEquals("Every card needs a label.", RoutineValidation.check(morning.copy(id = "r2", cards = listOf(RoutineCard("c", "shirt", " "))), others))
        assertEquals("Pick at least one day.", RoutineValidation.check(morning.copy(id = "r2", days = List(7) { false }), others))
        assertEquals("“Ready by” must be after the start time.", RoutineValidation.check(morning.copy(id = "r2", end = 400), others))
        assertEquals("Give it at least 5 minutes.", RoutineValidation.check(morning.copy(id = "r2", start = 600, end = 603), others))
        assertEquals("Keep it under 3 hours.", RoutineValidation.check(morning.copy(id = "r2", start = 600, end = 790), others))
        assertEquals("Overlaps School morning (7:15 AM to 7:55 AM).", RoutineValidation.check(morning.copy(id = "r2", name = "x"), others))
        assertNull(RoutineValidation.check(morning, others))
    }

    @Test fun daysAndSkipHints() {
        assertEquals("Mon to Fri", RoutineText.daysText(morning.days))
        assertEquals("Every day", RoutineText.daysText(List(7) { true }))
        assertEquals("Weekends", RoutineText.daysText(listOf(false, false, false, false, false, true, true)))
        assertEquals("Mon, Wed", RoutineText.daysText(listOf(true, false, true, false, false, false, false)))
        assertEquals("Skips Saturday and Sunday.", RoutineText.skipHint(morning.days))
        assertEquals("Runs every day.", RoutineText.skipHint(List(7) { true }))
        assertEquals("Skips Tuesday, Thursday and Saturday.", RoutineText.skipHint(listOf(true, false, true, false, true, false, true)))
    }

    @Test fun phoneStatusFollowsWhatThePortalSent() {
        val room = "Family Area"
        assertEquals("Next: Today, 7:15 AM", RoutineText.status(snapshot(), morning, room).text)
        assertEquals("Next: Tomorrow, 7:15 AM", RoutineText.status(snapshot(minute = 500), morning, room).text)
        val running = JSONArray().put(JSONObject().put("rid", "r1").put("status", "running").put("endedBy", JSONObject.NULL)
            .put("endedAt", JSONObject.NULL).put("deadline", 475).put("leftMin", 24).put("done", 2).put("total", 5))
        val st = RoutineText.status(snapshot(today = running, minute = 451), morning, room)
        assertEquals(RoutineText.Kind.RUNNING, st.kind)
        assertEquals("On Family Area now, 2 of 5 done", st.text)
        val auto = JSONArray().put(JSONObject().put("rid", "r1").put("status", "closed").put("endedBy", "auto")
            .put("endedAt", 505).put("deadline", 475).put("leftMin", -30).put("done", 2).put("total", 5))
        assertEquals("Closed itself at 8:25 AM, 2 of 5 done", RoutineText.status(snapshot(today = auto, minute = 506), morning, room).text)
        val done = JSONArray().put(JSONObject().put("rid", "r1").put("status", "done").put("endedBy", "done")
            .put("endedAt", 461).put("deadline", 475).put("leftMin", 10).put("done", 5).put("total", 5))
        assertEquals("Done today at 7:41 AM", RoutineText.status(snapshot(today = done, minute = 462), morning, room).text)
        assertEquals("Off. It won’t start until you switch it on.", RoutineText.status(snapshot(), morning.copy(enabled = false), room).text)
        assertEquals("Skipped today", RoutineText.status(snapshot(), morning.copy(skips = listOf("2026-11-09")), room).text)
        assertEquals("Next: Monday, 7:15 AM", RoutineText.status(snapshot(day = mon + 4, minute = 600), morning, room).text)
    }

    @Test fun savedSnackbarCopy() {
        assertEquals("Saved. Starts on Family Area today at 7:15 AM.", RoutineText.savedText(snapshot(), morning, "Family Area"))
        assertEquals("Saved. School morning is off, so it won’t start.", RoutineText.savedText(snapshot(), morning.copy(enabled = false), "Family Area"))
        assertEquals("Saved. Starts on Family Area tomorrow at 7:15 AM.", RoutineText.savedText(snapshot(minute = 600), morning, "Family Area"))
    }

    @Test fun usSchoolHolidaysAreLabelled() {
        assertEquals("Veterans Day", RoutineHolidays.name(RoutineDates.epochDayOf("2026-11-11")!!))
        assertEquals("Thanksgiving", RoutineHolidays.name(RoutineDates.epochDayOf("2026-11-26")!!))
        assertEquals("Day after Thanksgiving", RoutineHolidays.name(RoutineDates.epochDayOf("2026-11-27")!!))
        assertEquals("Labor Day", RoutineHolidays.name(RoutineDates.epochDayOf("2026-09-07")!!))
        assertNull(RoutineHolidays.name(mon))
    }

    @Test fun skipRowsAndShortDates() {
        assertEquals("Wed 11 Nov", RoutineText.shortDate(mon + 2))
        assertEquals("Today", RoutineText.dayRowLabel(mon, mon))
        assertEquals("Tomorrow", RoutineText.dayRowLabel(mon + 1, mon))
    }
}
