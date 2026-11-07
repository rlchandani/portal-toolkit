/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareJobTest {
    private val family = ShareRoom("f", "Family Area")
    private val living = ShareRoom("l", "Living Area")
    private val items = listOf(ShareItem("1", "a.jpg", 100), ShareItem("2", "b.jpg", 200), ShareItem("3", "c.jpg", 300))
    private fun job(rooms: List<ShareRoom> = listOf(family)) =
        ShareJob("j1", ShareKind.PHOTOS, "Rohit's Pixel", 0, rooms, items)

    @Test fun roomOptionsOfferBothWhenTwoRooms() {
        val options = ShareRooms.options(listOf(family, living))
        assertEquals(listOf("f", "l", ShareRooms.ALL_KEY), options.map { it.key })
        assertEquals("Both rooms", options.last().title)
        assertEquals("Family Area and Living Area", options.last().subtitle)
        assertEquals(1, ShareRooms.options(listOf(family)).size)
    }

    @Test fun lastUsedRoomIsTheDefaultWhileItExists() {
        val options = ShareRooms.options(listOf(family, living))
        assertEquals("l", ShareRooms.defaultKey(options, "l"))
        assertEquals(ShareRooms.ALL_KEY, ShareRooms.defaultKey(options, ShareRooms.ALL_KEY))
        assertEquals("f", ShareRooms.defaultKey(options, "gone"))
        assertEquals(null, ShareRooms.defaultKey(emptyList(), "f"))
    }

    @Test fun sendLabelsNameTheDestination() {
        assertEquals("Send to Family Area", ShareRooms.sendLabel(ShareKind.PHOTOS, listOf(family)))
        assertEquals("Send to Both rooms", ShareRooms.sendLabel(ShareKind.PHOTOS, listOf(family, living)))
        assertEquals("Send note to both rooms", ShareRooms.sendLabel(ShareKind.NOTE, listOf(family, living)))
        assertEquals("Open on Family Area", ShareRooms.sendLabel(ShareKind.LINK, listOf(family)))
    }

    @Test fun newJobIsActiveAndNothingIsConfirmed() {
        val s = job().summary()
        assertEquals(3, s.total); assertEquals(0, s.confirmed); assertTrue(s.active); assertFalse(s.done)
    }

    @Test fun retryResendsOnlyFailedAndStoppedFiles() {
        val j = job().with("f", "1", ItemState(ItemStatus.CONFIRMED))
            .with("f", "2", ItemState(ItemStatus.FAILED, reason = "dropped"))
            .with("f", "3", ItemState(ItemStatus.UNCONFIRMED))
        assertEquals(1, j.retryCount())
        val retried = j.retryFailed()
        assertEquals(ItemStatus.CONFIRMED, retried.state("f", "1").status)
        assertEquals(ItemStatus.WAITING, retried.state("f", "2").status)
        assertEquals(ItemStatus.UNCONFIRMED, retried.state("f", "3").status)
    }

    @Test fun holdingARoomNeverTouchesTheOtherRoom() {
        val j = job(listOf(family, living)).with("f", "1", ItemState(ItemStatus.CONFIRMED)).hold("l", "Living Area isn't responding.")
        assertEquals(ItemStatus.CONFIRMED, j.state("f", "1").status)
        assertEquals(ItemStatus.WAITING, j.state("f", "2").status)
        assertEquals(ItemStatus.HELD, j.state("l", "1").status)
        assertTrue(j.summary().held)
        val released = j.release("l")
        assertEquals(ItemStatus.WAITING, released.state("l", "1").status)
    }

    @Test fun stopAndCancelLeaveConfirmedFilesAlone() {
        val j = job().with("f", "1", ItemState(ItemStatus.CONFIRMED)).hold("f", "away").cancelWaiting()
        assertEquals(ItemStatus.CONFIRMED, j.state("f", "1").status)
        assertEquals(ItemStatus.STOPPED, j.state("f", "2").status)
        assertTrue(j.cancelled); assertTrue(j.summary().done)
    }

    @Test fun restartTurnsInFlightFilesIntoUnconfirmedNotSent() {
        val j = job().with("f", "1", ItemState(ItemStatus.SENDING, 50)).with("f", "2", ItemState(ItemStatus.CONFIRMING, 200))
            .recoverAfterRestart()
        assertEquals(ItemStatus.UNCONFIRMED, j.state("f", "1").status)
        assertEquals(ItemStatus.UNCONFIRMED, j.state("f", "2").status)
        assertEquals(ItemStatus.WAITING, j.state("f", "3").status)
    }

    @Test fun allConfirmedOnlyWhenEveryRoomConfirmedEveryFile() {
        var j = job(listOf(family, living))
        items.forEach { j = j.with("f", it.id, ItemState(ItemStatus.CONFIRMED)) }
        assertFalse(j.summary().allConfirmed)
        assertEquals(3 to 3, j.roomSummary("f"))
        items.forEach { j = j.with("l", it.id, ItemState(ItemStatus.CONFIRMED)) }
        assertTrue(j.summary().allConfirmed)
    }
}
