package com.portalinstaller.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LsParserTest {
    private val sample = """
        total 40
        drwxrwx--x  4 root sdcard_rw  3452 2024-08-01 12:04 DCIM
        -rw-rw----  1 root sdcard_rw 91234 2024-07-30 09:11 photo one.jpg
        lrwxrwxrwx  1 root root         21 2024-01-01 00:00 link -> /storage/emulated/0
        drwxrwx--x  2 root sdcard_rw  3452 2024-08-01 12:04 .
        drwxrwx--x 20 root sdcard_rw  3452 2024-08-01 12:04 ..
    """.trimIndent()

    @Test fun `parses dirs files and symlinks skipping dot entries`() {
        val files = LsParser.parse("/sdcard", sample)
        assertEquals(3, files.size)

        val dir = files.first { it.name == "DCIM" }
        assertTrue(dir.isDirectory)
        assertTrue(!dir.isSymlink)
        assertEquals("/sdcard/DCIM", dir.path)

        val file = files.first { it.name == "photo one.jpg" }
        assertEquals(91234L, file.sizeBytes)
        assertTrue(!file.isDirectory)
        assertEquals("/sdcard/photo one.jpg", file.path)
        assertEquals("2024-07-30 09:11", file.modifiedAt)

        val link = files.first { it.name == "link" }
        assertTrue(link.isSymlink)
        assertEquals("/sdcard/link", link.path)
    }

    @Test fun `blank and total lines ignored`() {
        assertEquals(emptyList<RemoteFile>(), LsParser.parse("/sdcard", "total 0\n\n"))
    }

    @Test fun `trailing slash on parent path does not double up`() {
        val files = LsParser.parse("/sdcard/", "drwxrwx--x 2 root sdcard_rw 3452 2024-08-01 12:04 DCIM")
        assertEquals("/sdcard/DCIM", files.single().path)
    }

    @Test fun `line with too few columns is skipped`() {
        // Doar 4 campuri: nu are size/data/nume, deci nu producem un RemoteFile fals.
        assertEquals(emptyList<RemoteFile>(), LsParser.parse("/sdcard", "drwxrwx--x 2 root sdcard_rw"))
    }

    @Test fun `non-numeric size falls back to zero`() {
        val files = LsParser.parse(
            "/sdcard",
            "-rw-rw---- 1 root sdcard_rw ????? 2024-07-30 09:11 weird.bin"
        )
        assertEquals(0L, files.single().sizeBytes)
        assertEquals("weird.bin", files.single().name)
    }

    @Test fun `garbage line among valid lines does not break parse`() {
        val mixed = """
            total 8
            garbage-without-columns
            -rw-rw---- 1 root sdcard_rw 10 2024-07-30 09:11 ok.txt
        """.trimIndent()
        val files = LsParser.parse("/sdcard", mixed)
        assertEquals(1, files.size)
        assertEquals("ok.txt", files.single().name)
    }
}
