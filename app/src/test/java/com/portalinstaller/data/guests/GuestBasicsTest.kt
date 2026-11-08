/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.guests

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The option-based house basics: presets, notes, check-out, door code, contact and welcome. */
class GuestBasicsTest {
    @Test fun presetCatalogMatchesThePortal() {
        assertEquals(listOf("shoes_off", "quiet_hours", "snacks", "coffee", "pets", "no_smoking", "towels", "street_parking"),
            GuestBasics.PRESETS.map { it.id })
        assertEquals("Quiet after 10 PM", GuestBasics.presetLabel("quiet_hours"))
        assertEquals(null, GuestBasics.presetLabel("future_rule"))
    }

    @Test fun idsDecodeTrimmedAndDistinct() {
        assertEquals(listOf("pets", "coffee"), GuestBasics.decodeIds(" pets, coffee,,pets "))
        assertEquals(emptyList<String>(), GuestBasics.decodeIds(""))
        assertEquals("pets,coffee", GuestBasics.encodeIds(listOf("pets", "coffee")))
    }

    @Test fun togglingAPresetIsImmutableAndKeepsOrder() {
        val start = listOf("future_rule", "pets")
        val added = GuestBasics.toggle(start, "coffee")
        assertEquals(listOf("future_rule", "pets"), start)
        assertEquals(listOf("future_rule", "pets", "coffee"), added)
        assertEquals(listOf("future_rule", "coffee"), GuestBasics.toggle(added, "pets"))
    }

    @Test fun customNotesAreCleanedBoundedAndDeduped() {
        assertEquals("Gate a/b", GuestBasics.cleanNote("  Gate a|b\n"))
        assertEquals(40, GuestBasics.cleanNote("x".repeat(60)).length)
        val one = GuestBasics.addNote(emptyList(), "Bins Tuesday")
        assertEquals(listOf("Bins Tuesday"), one)
        assertEquals(one, GuestBasics.addNote(one, "bins tuesday"))
        assertEquals(one, GuestBasics.addNote(one, "   "))
        val full = (1..6).fold(emptyList<String>()) { acc, i -> GuestBasics.addNote(acc, "Note $i") }
        assertEquals(6, GuestBasics.addNote(full, "Seventh").size)
        assertEquals("A|B", GuestBasics.encodeNotes(listOf("A", "B")))
        assertEquals(listOf("A", "B"), GuestBasics.decodeNotes(" A || B "))
    }

    @Test fun checkoutValuesAndDisplay() {
        assertTrue(GuestCheckout.isValid(""))
        assertTrue(GuestCheckout.isValid("09:30"))
        assertFalse(GuestCheckout.isValid("9:30"))
        assertFalse(GuestCheckout.isValid("24:00"))
        assertFalse(GuestCheckout.isValid("11:00 on Sunday"))
        assertEquals("07:05", GuestCheckout.of(7, 5))
        assertEquals(listOf("10:00", "11:00", "12:00"), GuestCheckout.PRESETS)
        assertEquals("No check-out", GuestCheckout.display("", use24 = true, Locale.US))
        assertEquals("11:00", GuestCheckout.display("11:00", use24 = true, Locale.US))
        assertEquals("11:00 AM", GuestCheckout.display("11:00", use24 = false, Locale.US))
        assertEquals("12:30 PM", GuestCheckout.display("12:30", use24 = false, Locale.US))
        assertEquals("Check-out by 11:00 AM", GuestCheckout.line("11:00", use24 = false, Locale.US))
        assertEquals(null, GuestCheckout.line("", use24 = false, Locale.US))
        assertEquals(10 to 0, GuestCheckout.parts("10:00"))
    }

    @Test fun doorCodesAreFourToEightDigits() {
        assertTrue(GuestDoorCode.isValid("4821"))
        assertTrue(GuestDoorCode.isValid("12345678"))
        assertFalse(GuestDoorCode.isValid("482"))
        assertFalse(GuestDoorCode.isValid("123456789"))
        assertFalse(GuestDoorCode.isValid("48a1"))
        assertFalse(GuestDoorCode.isValid("４８２１")) // full-width digits are not keypad digits
        assertEquals("4821", GuestDoorCode.clean("48-21#"))
        assertEquals("12345678", GuestDoorCode.clean("1234567890"))
    }

    @Test fun contactOptionsAndLines() {
        assertEquals(listOf("call_portal", "text", "ask"), GuestBasics.CONTACTS.map { it.id })
        assertEquals("Need anything? Call us on the Portal.", GuestBasics.contactLine("call_portal"))
        assertEquals(null, GuestBasics.contactLine("none"))
        assertEquals("Questions? Ask Rohit.", GuestBasics.nameLine("Rohit"))
        assertEquals(null, GuestBasics.nameLine(""))
    }

    @Test fun contactNamesNeverCarryANumber() {
        assertEquals("Mum", GuestBasics.cleanContactName("  Mum \n"))
        assertEquals("", GuestBasics.cleanContactName("+1 555 0100"))
        assertEquals(40, GuestBasics.cleanContactName("N".repeat(55)).length)
    }

    @Test fun welcomeDefaultAndSamples() {
        assertEquals("Welcome! Make yourself at home.", GuestWelcome.DEFAULT)
        assertEquals(GuestWelcome.DEFAULT, GuestWelcome.SAMPLES.first())
        assertEquals(4, GuestWelcome.SAMPLES.size)
        assertTrue(GuestWelcome.SAMPLES.all { it.length <= GuestWelcome.MAX })
        assertEquals("a b", GuestWelcome.clean("a\nb"))
        assertEquals(GuestWelcome.MAX, GuestWelcome.clean("x".repeat(100)).length)
    }
}
