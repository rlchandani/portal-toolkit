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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The step-by-step setup draft: steps, per-step problems, secret changes and the preview copy. */
class GuestDraftTest {
    private fun snapshot(card: GuestCard = GuestCard(ssid = "Home"), hasPassword: Boolean = true, hasDoorCode: Boolean = false) =
        GuestSnapshot(card, hasPassword, hasDoorCode, ready = hasPassword, active = false, untilMs = null, version = 2)

    @Test fun openNetworksSkipThePasswordStep() {
        val secured = GuestDraft.from(snapshot())
        assertEquals(GuestStep.entries, secured.steps)
        assertEquals(GuestStep.PASSWORD, secured.next(GuestStep.NETWORK))
        val open = secured.withNetwork("Cafe", "none", hidden = false)
        assertEquals(GuestStep.BASICS, open.next(GuestStep.NETWORK))
        assertEquals(GuestStep.NETWORK, open.previous(GuestStep.BASICS))
        assertNull(open.next(GuestStep.PREVIEW))
        assertNull(open.previous(GuestStep.NETWORK))
    }

    @Test fun pickingANetworkUpdatesTheCardImmutably() {
        val draft = GuestDraft.from(snapshot())
        val picked = draft.withNetwork("Guest Wi-Fi", "sae", hidden = true)
        assertEquals("Home", draft.card.ssid)
        assertEquals("Guest Wi-Fi", picked.card.ssid)
        assertEquals("sae", picked.card.security)
        assertTrue(picked.card.hiddenNetwork)
    }

    @Test fun stepProblems() {
        val blank = GuestDraft.from(snapshot(GuestCard(), hasPassword = false))
        assertEquals("Choose your Wi-Fi network.", blank.problem(GuestStep.NETWORK))
        val named = blank.withNetwork("Home", "wpa", false)
        assertEquals("Add the Wi-Fi password.", named.problem(GuestStep.PASSWORD))
        assertEquals("Wi-Fi passwords are at least 8 characters.", named.copy(password = "short").problem(GuestStep.PASSWORD))
        assertNull(named.copy(password = "longenough").problem(GuestStep.PASSWORD))
        assertNull(named.withNetwork("Old", "wep", false).copy(password = "abcde").problem(GuestStep.PASSWORD))
        assertEquals("The password is too long.", named.copy(password = "p".repeat(65)).problem(GuestStep.PASSWORD))
        assertEquals("Add the Wi-Fi password.", named.problem(GuestStep.PREVIEW))
        assertNull(GuestDraft.from(snapshot()).problem(GuestStep.PASSWORD)) // the Portal keeps the saved one
    }

    @Test fun doorCodeChanges() {
        val none = GuestDraft.from(snapshot())
        assertFalse(none.showDoorCode)
        assertEquals(DoorCodeChange.Keep, none.doorCodeChange)
        val typing = none.copy(showDoorCode = true, doorCodeInput = "48")
        assertEquals("Door codes are 4 to 8 digits.", typing.problem(GuestStep.BASICS))
        assertEquals("Type the door code.", none.copy(showDoorCode = true).problem(GuestStep.BASICS))
        assertEquals(DoorCodeChange.Set("4821"), typing.copy(doorCodeInput = "4821").doorCodeChange)
        val saved = GuestDraft.from(snapshot(hasDoorCode = true))
        assertTrue(saved.showDoorCode)
        assertNull(saved.problem(GuestStep.BASICS))
        assertEquals(DoorCodeChange.Keep, saved.doorCodeChange)
        assertEquals(DoorCodeChange.Clear, saved.copy(showDoorCode = false).doorCodeChange)
    }

    @Test fun toStringHidesSecrets() {
        val draft = GuestDraft.from(snapshot()).copy(password = "hunter2-secret", showDoorCode = true, doorCodeInput = "4821")
        assertFalse(draft.toString().contains("hunter2"))
        assertFalse(draft.toString().contains("4821"))
    }

    @Test fun previewLinesMatchThePortalCard() {
        val card = GuestCard(ssid = "Home", welcome = "Hi!", checkout = "11:00", goodToKnow = listOf("pets", "future_rule"),
            customNotes = listOf("Bins Tuesday"), contact = "ask", contactName = "Rohit")
        val draft = GuestDraft.from(snapshot(card, hasDoorCode = true))
        val preview = draft.preview(use24 = true, Locale.US)
        assertEquals("Hi!", preview.welcome)
        assertEquals("Home", preview.ssid)
        assertEquals("Tap to show", preview.password)
        assertEquals("Check-out by 11:00", preview.checkout)
        assertEquals(listOf("Pets in the house", "Bins Tuesday"), preview.notes)
        assertEquals("Tap to show", preview.doorCode)
        assertEquals(listOf("Need anything? Just ask.", "Questions? Ask Rohit."), preview.contact)
        val open = draft.withNetwork("Cafe", "none", false).copy(showDoorCode = false).preview(true, Locale.US)
        assertEquals("No password needed", open.password)
        assertNull(open.doorCode)
        val shown = draft.copy(card = card.copy(hidePassword = false), password = "s3cretpass").preview(true, Locale.US)
        assertEquals("s3cretpass", shown.password)
    }
}
