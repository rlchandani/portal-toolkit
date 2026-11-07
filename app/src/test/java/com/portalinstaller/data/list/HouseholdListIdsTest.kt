/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.list

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The item id rule is a contract with the Portal app (same known answers are pinned there). */
class HouseholdListIdsTest {
    @Test fun idMatchesThePortalKnownAnswers() {
        assertEquals("h213e23d0419e211", HouseholdListIds.itemId("Milk"))
        assertEquals("h7a2c4d4f5f96886", HouseholdListIds.itemId("eggs"))
        assertEquals("hd2af6bed4b1b915", HouseholdListIds.itemId("Paper  Towels "))
    }

    @Test fun normalizesCaseAndWhitespace() {
        assertEquals("paper towels", HouseholdListIds.norm("  Paper \t Towels "))
    }

    @Test fun cleanTextCollapsesWhitespaceAndRejectsBlankOrTooLong() {
        assertEquals("Oat milk", HouseholdListIds.cleanText("  Oat   milk "))
        assertNull(HouseholdListIds.cleanText("   "))
        assertNull(HouseholdListIds.cleanText("x".repeat(61)))
        assertEquals(60, HouseholdListIds.cleanText("x".repeat(60))!!.length)
    }
}
