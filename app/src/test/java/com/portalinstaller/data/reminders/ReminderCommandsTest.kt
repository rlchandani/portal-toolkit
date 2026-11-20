/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.reminders

import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Command bodies, drafts, ordering and copy. */
class ReminderCommandsTest {
    private val zone = ZoneId.of("Europe/London")
    private val maya = ReminderPerson("p_maya", "Maya", kid = false)
    private val leo = ReminderPerson("p_leo", "Leo", kid = true)
    private val board = ReminderBoard(ready = true, people = listOf(maya, leo))
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0) = ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli()

    @Test fun addSendsTheCleanedFieldsAndOmitsPrivateWhenUnset() {
        val body = ReminderCommand.save(ReminderDraft(title = "  Bins  out ")).body()
        assertEquals("add", body.getString("action"))
        assertEquals("Bins out", body.getString("title"))
        assertEquals("", body.getString("person"))
        assertEquals(0L, body.getLong("due"))
        assertFalse(body.has("private"))
        assertFalse(body.has("id"))
    }

    @Test fun editCarriesIdBaseAndPrivate() {
        val draft = ReminderDraft(id = "r1", title = "Vet", person = "p_maya", due = 123L, private = false, base = 9)
        val body = ReminderCommand.save(draft).body()
        assertEquals("edit", body.getString("action"))
        assertEquals("r1", body.getString("id"))
        assertEquals(9L, body.getLong("base"))
        assertEquals(123L, body.getLong("due"))
        assertFalse(body.getBoolean("private"))
        assertTrue(ReminderCommand.save(draft).namesPerson)
        assertFalse(ReminderCommand.save(draft.copy(person = "")).namesPerson)
    }

    @Test fun toggleAndDelete() {
        val open = Reminder("r1", "Vet", seq = 4)
        assertEquals(ReminderCommand.Done("r1", 4), ReminderCommand.toggle(open))
        assertEquals(ReminderCommand.Undo("r1", 4), ReminderCommand.toggle(open.copy(done = true)))
        assertEquals("done", ReminderCommand.Done("r1", 4).body().getString("action"))
        assertEquals("undo", ReminderCommand.Undo("r1", 4).body().getString("action"))
        val delete = ReminderCommand.Delete("r1", 4).body()
        assertEquals("delete", delete.getString("action"))
        assertEquals("r1", delete.getString("id"))
        assertEquals(4L, delete.getLong("base"))
        assertFalse(ReminderCommand.Delete("r1", 4).namesPerson)
    }

    @Test fun privateDefaultsOnForNewGrownUpReminders() {
        val forMaya = ReminderDraft(title = "Gift").forPerson(maya)
        assertEquals(true, forMaya.private)
        assertTrue(forMaya.showsPrivate(board))
        assertNull(forMaya.forPerson(leo).private)
        assertFalse(forMaya.forPerson(leo).showsPrivate(board))
        assertNull(forMaya.forPerson(null).private)
        assertEquals(false, forMaya.copy(private = false).forPerson(maya).private)
    }

    @Test fun editingAVisibleReminderStartsNotPrivate() {
        val draft = ReminderDraft.of(Reminder("r1", "Vet", "p_maya", "Maya", 50L, seq = 3), board)
        assertEquals(ReminderDraft("r1", "Vet", "p_maya", 50L, private = false, base = 3), draft)
        val gone = ReminderDraft.of(Reminder("r2", "Old", "p_gone"), board)
        assertEquals("", gone.person)
        assertFalse(ReminderDraft(title = " ").valid)
    }

    @Test fun openFirstSoonestThenUndatedThenDone() {
        val b = ReminderBoard(reminders = listOf(
            Reminder("a", "Undated old", seq = 1), Reminder("b", "Later", due = 200), Reminder("c", "Done", done = true, seq = 2),
            Reminder("d", "Soon", due = 100), Reminder("e", "Undated new", seq = 5), Reminder("f", "Done new", done = true, seq = 9)))
        assertEquals(listOf("d", "b", "e", "a"), b.open.map { it.id })
        assertEquals(listOf("f", "c"), b.finished.map { it.id })
        assertEquals(maya, board.person("p_maya"))
        assertNull(board.person(""))
    }

    @Test fun dueCopyReadsNaturally() {
        val now = at(2026, 11, 19, 10)
        assertEquals("6 pm", ReminderCopy.due(at(2026, 11, 19, 18), now, zone))
        assertEquals("Tomorrow 9 am", ReminderCopy.due(at(2026, 11, 20, 9), now, zone))
        assertEquals("Yesterday 6:30 pm", ReminderCopy.due(at(2026, 11, 18, 18, 30), now, zone))
        assertEquals("Mon 12 pm", ReminderCopy.due(at(2026, 11, 23, 12), now, zone))
        assertEquals("12 Dec 12 am", ReminderCopy.due(at(2026, 12, 12, 0), now, zone))
        assertEquals("9:05 am", ReminderCopy.time(LocalTime.of(9, 5)))
    }

    @Test fun rowLineAndPrivateLine() {
        val now = at(2026, 11, 19, 10)
        val r = Reminder("r1", "Vet", "p_maya", "Maya", at(2026, 11, 19, 18), by = "Phone")
        assertEquals("For Maya · 6 pm · by Phone", ReminderCopy.line(r, now, zone))
        assertEquals("For Home", ReminderCopy.line(Reminder("r2", "Bins"), now, zone))
        assertEquals("🔒 2 private for Maya — only on Maya’s Home", ReminderCopy.privateLine("Maya", 2))
        assertFalse(ReminderCopy.overdue(r, now))
        assertTrue(ReminderCopy.overdue(r, at(2026, 11, 19, 19)))
        assertFalse(ReminderCopy.overdue(r.copy(done = true), at(2026, 11, 19, 19)))
        assertFalse(ReminderCopy.overdue(r.copy(due = 0), now))
    }

    @Test fun refusalsAreFriendly() {
        assertEquals("That reminder was removed on another device.", ReminderCopy.refused("unknown_reminder"))
        listOf("unknown_person", "bad_title", "stale", "not_ready", "too_many", "unauthorized").forEach {
            assertFalse(it, ReminderCopy.refused(it) == ReminderCopy.refused("whatever"))
        }
    }

    @Test fun duePresetsAndPicker() {
        val morning = at(2026, 11, 19, 10)
        assertEquals(at(2026, 11, 19, 18), ReminderDue.thisEvening(morning, zone))
        assertNull(ReminderDue.thisEvening(at(2026, 11, 19, 18), zone))
        assertEquals(at(2026, 11, 20, 9), ReminderDue.tomorrowMorning(at(2026, 11, 19, 23, 59), zone))
        val utcMidnight = ReminderDue.pickerDate(at(2026, 11, 25, 0, 30), zone)
        assertEquals(java.time.LocalDate.of(2026, 11, 25).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli(), utcMidnight)
        assertEquals(at(2026, 11, 25, 7, 45), ReminderDue.combine(utcMidnight, 7, 45, zone))
        assertEquals(LocalTime.of(7, 45), ReminderDue.localTime(at(2026, 11, 25, 7, 45), zone))
    }
}
