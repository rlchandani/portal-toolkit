package com.portalinstaller.data.screensaver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageFolderScannerTest {

    @Test fun `legacy folder always present even with zero images`() {
        val result = ImageFolderScanner.rankFolders(findOutput = "", imageCountsByDir = emptyMap())
        assertEquals(1, result.size)
        assertEquals(ImageFolderScanner.LEGACY_FOLDER, result.single().path)
        assertEquals(0, result.single().imageCount)
    }

    @Test fun `folders sorted by image count descending`() {
        val counts = mapOf(
            "/sdcard/DCIM" to 5,
            "/sdcard/Download" to 12,
            "/sdcard/Pictures" to 3
        )
        val result = ImageFolderScanner.rankFolders(
            findOutput = "/sdcard/DCIM\n/sdcard/Download\n/sdcard/Pictures",
            imageCountsByDir = counts
        )
        assertEquals("/sdcard/Download", result.first().path)
        assertEquals(12, result.first().imageCount)
    }

    @Test fun `ties broken alphabetically by path`() {
        val counts = mapOf("/sdcard/B" to 4, "/sdcard/A" to 4)
        val result = ImageFolderScanner.rankFolders("/sdcard/B\n/sdcard/A", counts)
        val fourCount = result.filter { it.imageCount == 4 }.map { it.path }
        assertEquals(listOf("/sdcard/A", "/sdcard/B"), fourCount)
    }

    @Test fun `find output deduplicated and blanks ignored`() {
        val result = ImageFolderScanner.rankFolders(
            findOutput = "/sdcard/DCIM\n\n  \n/sdcard/DCIM\n",
            imageCountsByDir = mapOf("/sdcard/DCIM" to 2)
        )
        assertEquals(1, result.count { it.path == "/sdcard/DCIM" })
    }

    @Test fun `count-only dir absent from find output is still included`() {
        val result = ImageFolderScanner.rankFolders(
            findOutput = "",
            imageCountsByDir = mapOf("/sdcard/Movies" to 7)
        )
        assertTrue(result.any { it.path == "/sdcard/Movies" && it.imageCount == 7 })
    }
}
