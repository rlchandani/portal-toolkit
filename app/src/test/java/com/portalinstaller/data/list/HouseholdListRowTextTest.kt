/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.list

import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class HouseholdListRowTextTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private fun ms(h: Int, m: Int) = LocalDateTime.of(2026, 11, 7, h, m).atZone(zone).toInstant().toEpochMilli()
    private val now = ms(8, 4)
    private val text = HouseholdListRowText(HouseholdListFormat(zone, Locale.US))
    private val eggs = ListItem("h1", "Eggs", false, 1.0, "Living Area", ms(6, 29), ms(6, 29))

    @Test fun rowMetaPrefersWaitingThenNoteThenWho() {
        assertEquals(RowMeta(RowMetaKind.WHO, "Living Area, 6:29 AM"), text.meta(eggs, waiting = false, dismissed = false, now))
        assertEquals(RowMeta(RowMetaKind.WAITING, "Waiting to send"), text.meta(eggs, waiting = true, dismissed = false, now))
        val noted = eggs.copy(note = ItemNote(NoteKind.KEPT, "Kept: Rohit’s Pixel put it back on the list while Living Area removed it."))
        assertEquals(RowMetaKind.NOTE, text.meta(noted, waiting = false, dismissed = false, now).kind)
        assertEquals(RowMetaKind.WHO, text.meta(noted, waiting = false, dismissed = true, now).kind)
        val ticked = eggs.copy(done = true, doneBy = "Rohit’s Pixel", doneAt = ms(7, 2))
        assertEquals("Ticked by Rohit’s Pixel, 7:02 AM", text.meta(ticked, waiting = false, dismissed = false, now).text)
    }

    @Test fun sheetLinesShowWhoAndWhen() {
        val item = eggs.copy(done = true, doneBy = "Rohit’s Pixel", doneAt = ms(7, 2), renamedBy = "Family Area", renamedAt = ms(7, 0))
        assertEquals(listOf("Added by Living Area, today 6:29 AM", "Renamed by Family Area, today 7:00 AM",
            "Ticked by Rohit’s Pixel, today 7:02 AM", "Waiting to send to the Portals"), text.sheetLines(item, waiting = true, now))
    }
}
