/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.share

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareJobCodecTest {
    private val job = ShareJob("j1", ShareKind.LINK, "Rohit's Pixel", 42, listOf(ShareRoom("f", "Family Area")),
        listOf(ShareItem("link", "Link", 1)), link = ShareLink("https://x.org", null))
        .with("f", "link", ItemState(ItemStatus.HELD, reason = "away"))

    @Test fun jobsRoundTripWithoutLosingState() {
        assertEquals(listOf(job), ShareJobCodec.decode(ShareJobCodec.encode(listOf(job))))
    }

    @Test fun corruptOrHostileEntriesAreDropped() {
        assertEquals(emptyList<ShareJob>(), ShareJobCodec.decode("not json"))
        val hostile = """{"version":1,"jobs":[{"id":"../x","kind":"PHOTOS","from":"a","createdAt":1,"rooms":[],"items":[]}]}"""
        assertEquals(emptyList<ShareJob>(), ShareJobCodec.decode(hostile))
    }

    @Test fun senderNamesAreCleanedAndBounded() {
        assertEquals("Rohit's Pixel", SenderName.clean("  Rohit's\u0000 Pixel "))
        assertEquals(40, SenderName.clean("x".repeat(80)).length)
        assertEquals("Phone", SenderName.clean("   "))
    }

    @Test fun stagedNamesAreSafeAndKeepTheirExtension() {
        assertEquals("PXL_1.jpg", ShareStaging.safeName("PXL_1.jpg", 0, "image/jpeg"))
        assertEquals("image-2.png", ShareStaging.safeName("../../etc", 1, "image/png"))
        assertEquals("a b.webp", ShareStaging.safeName("a/b.webp".replace('/', ' '), 0, "image/webp"))
        assertEquals("image-1.jpg", ShareStaging.safeName(null, 0, null))
    }

    @Test fun storeKeepsJobsInItsOwnPrivateDirectory() {
        val dir = Files.createTempDirectory("share").toFile()
        val store = ShareJobStore(dir)
        store.save(listOf(job))
        assertEquals(listOf(job), store.load())
        val staged = store.itemFile("j1", "link").apply { parentFile!!.mkdirs(); writeText("x") }
        assertTrue(staged.exists())
        store.forget("j1")
        assertFalse(staged.exists())
        dir.deleteRecursively()
    }
}
