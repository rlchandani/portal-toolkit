/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.share

import java.io.IOException
import com.portalinstaller.data.companion.CompanionError
import com.portalinstaller.data.companion.CompanionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareCopyTest {
    private val family = ShareRoom("f", "Family Area")
    private val living = ShareRoom("l", "Living Area")
    private val items = listOf(ShareItem("1", "a.jpg", 1_000_000), ShareItem("2", "b.jpg", 2_800_000), ShareItem("3", "c.jpg", 100))
    private fun job(rooms: List<ShareRoom> = listOf(family)) = ShareJob("j", ShareKind.PHOTOS, "Rohit's Pixel", 0, rooms, items)
    private fun ShareJob.all(status: ItemStatus) = rooms.fold(this) { acc, r -> items.fold(acc) { a, i -> a.with(r.id, i.id, ItemState(status)) } }

    @Test fun rowsSayExactlyWhatHappened() {
        val j = job()
        assertEquals("Waiting · 2.8 MB", ShareCopy.row(j, family, items[1]))
        assertEquals("Sending · 62%", ShareCopy.row(j.with("f", "2", ItemState(ItemStatus.SENDING, 1_736_000)), family, items[1]))
        assertEquals("Waiting for Family Area to confirm", ShareCopy.row(j.with("f", "2", ItemState(ItemStatus.CONFIRMING, 2_800_000)), family, items[1]))
        assertEquals("On Family Area", ShareCopy.row(j.with("f", "2", ItemState(ItemStatus.CONFIRMED)), family, items[1]))
        assertEquals("Sent, not confirmed. Family Area hasn’t said it saved this.", ShareCopy.row(j.with("f", "2", ItemState(ItemStatus.UNCONFIRMED)), family, items[1]))
        assertEquals("Didn’t arrive. Nope.", ShareCopy.row(j.with("f", "2", ItemState(ItemStatus.FAILED, reason = "Nope.")), family, items[1]))
        assertEquals("Waiting for a connection", ShareCopy.row(j.with("f", "2", ItemState(ItemStatus.HELD)), family, items[1]))
    }

    @Test fun resultTitlesCountOnlyConfirmedFiles() {
        assertEquals("3 photos on Family Area", ShareCopy.resultTitle(job().all(ItemStatus.CONFIRMED)))
        assertEquals("3 photos on both Portals", ShareCopy.resultTitle(job(listOf(family, living)).all(ItemStatus.CONFIRMED)))
        val partial = job().all(ItemStatus.CONFIRMED).with("f", "2", ItemState(ItemStatus.FAILED))
        assertEquals("2 of 3 photos on Family Area", ShareCopy.resultTitle(partial))
        val both = job(listOf(family, living)).all(ItemStatus.CONFIRMED).with("l", "2", ItemState(ItemStatus.FAILED))
        assertEquals("5 of 6 confirmed across both rooms", ShareCopy.resultTitle(both))
    }

    @Test fun aSingleFailureIsNamedWithItsReason() {
        val j = job().all(ItemStatus.CONFIRMED).with("f", "2", ItemState(ItemStatus.FAILED, reason = "The connection to Family Area dropped at 55%."))
        val head = ShareCopy.headline(j)
        assertEquals("2 of 3 photos on Family Area", head.title)
        assertEquals("b.jpg didn’t arrive on Family Area. The connection to Family Area dropped at 55%.", head.body)
        assertEquals("Retry 1 photo", ShareCopy.retryLabel(j))
    }

    @Test fun aSingleUnconfirmedFileIsNeverCalledSent() {
        val j = job().all(ItemStatus.CONFIRMED).with("f", "2", ItemState(ItemStatus.UNCONFIRMED))
        val head = ShareCopy.headline(j)
        assertEquals("Sent, not confirmed", head.title)
        assertEquals("b.jpg reached Family Area, but it hasn’t confirmed saving it. It may still show up.", head.body)
    }

    @Test fun heldJobsSayNothingWasSent() {
        val head = ShareCopy.headline(job().hold("f", "Your phone isn’t on home Wi-Fi."))
        assertEquals("Waiting to send", head.title)
        assertTrue(head.body.startsWith("Your phone isn’t on home Wi-Fi. Nothing has been sent yet."))
        assertTrue(head.body.contains("as soon as it can reach Family Area"))
    }

    @Test fun activeJobsExplainTheConfirmationRule() {
        val head = ShareCopy.headline(job())
        assertEquals("Sending to Family Area", head.title)
        assertEquals("Each one counts only after the Portal confirms it’s saved.", head.body)
    }

    @Test fun failureReasonsNameTheRoomAndWhere() {
        val unconfirmed = CompanionException(CompanionError("upload_unconfirmed", "x"))
        assertEquals(ItemState(ItemStatus.FAILED, 0, "Couldn’t reach Family Area."), ShareFailures.upload(unconfirmed, "Family Area", 0, 100))
        assertEquals(ItemState(ItemStatus.FAILED, 55, "The connection to Family Area dropped at 55%."), ShareFailures.upload(unconfirmed, "Family Area", 55, 100))
        assertEquals(ItemStatus.UNCONFIRMED, ShareFailures.upload(unconfirmed, "Family Area", 100, 100).status)
        assertEquals(ItemStatus.UNCONFIRMED, ShareFailures.upload(CompanionException(CompanionError("upload_unconfirmed", "x", 200)), "Family Area", 10, 100).status)
        val full = CompanionException(CompanionError("upload_failed", "x", 507))
        assertEquals("Family Area is out of storage. Free up space, then retry.", ShareFailures.upload(full, "Family Area", 100, 100).reason)
        val auth = CompanionException(CompanionError("unauthorized", "x", 401))
        assertEquals("Family Area needs to be paired with this phone again.", ShareFailures.upload(auth, "Family Area", 0, 100).reason)
        assertEquals("Couldn’t reach Family Area.", ShareFailures.upload(IOException("boom"), "Family Area", 0, 100).reason)
    }
}
