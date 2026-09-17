package com.portalinstaller.data.screensaver

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure unit tests for the Google Photos album model — no Android stubs (Uri.parse
 * throws in JVM tests; those cases belong in instrumented tests).
 */
class GooglePhotosAlbumTest {

    @Test
    fun `album with no photos has photoCount zero`() {
        val a = GooglePhotoAlbum(bucketId = "1", displayName = "Vacation", photoCount = 0)
        assertEquals(0, a.photoCount)
    }

    @Test
    fun `albums sorted by display name are case-insensitive`() {
        val albums = listOf(
            GooglePhotoAlbum("3", "zoo", 5),
            GooglePhotoAlbum("1", "Apple", 10),
            GooglePhotoAlbum("2", "beach", 3),
        )
        val sorted = albums.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
        assertEquals(listOf("Apple", "beach", "zoo"), sorted.map { it.displayName })
    }

    @Test
    fun `dedupe key from photo entry name and size matches the existing pipeline contract`() {
        // GooglePhotoEntry carries displayName+sizeBytes so DedupeKey(entry.displayName, entry.sizeBytes)
        // is the correct construction — same as the existing upload plan uses for camera-roll URIs.
        // Verify the field contract without constructing a Uri (Uri.EMPTY throws in JVM tests).
        val name = "IMG_1234.jpg"
        val size = 2_048_000L
        val key = DedupeKey(name, size)
        assertEquals(name, key.name)
        assertEquals(size, key.sizeBytes)
    }
}
