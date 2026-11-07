package com.portalinstaller.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PathSafetyTest {
    @Test fun `traversal is normalized before confinement`() {
        assertTrue(!PathSafety.isWithinSdcard("/sdcard/../data/local/tmp"))
        assertTrue(!PathSafety.isWithinSdcard("/sdcard/Pictures/../../data"))
        // Any parent segment is refused outside Advanced mode, even one that stays inside /sdcard
        // (merged policy with the maintenance line, 2026-11-07).
        assertTrue(!PathSafety.isWithinSdcard("/sdcard/Pictures/../Download"))
        assertTrue(PathSafety.isWithinSdcard("//sdcard///Pictures/./file"))
        assertThrows(IllegalArgumentException::class.java) {
            PathSafety.requireAllowed("/sdcard/\u0000file", false)
        }
    }
    @Test fun `sdcard paths are within`() {
        assertTrue(PathSafety.isWithinSdcard("/sdcard/DCIM/x.jpg"))
        assertTrue(PathSafety.isWithinSdcard("/sdcard"))
        assertTrue(PathSafety.isWithinSdcard("/sdcard/"))
    }

    @Test fun `system paths are outside`() {
        assertTrue(!PathSafety.isWithinSdcard("/data/data/com.x"))
        assertTrue(!PathSafety.isWithinSdcard("/sdcardX/y")) // capcana de prefix
    }

    @Test fun `requireAllowed throws outside sdcard without advanced mode`() {
        assertThrows(SecurityException::class.java) {
            PathSafety.requireAllowed("/data/local/tmp", advancedMode = false)
        }
    }

    @Test fun `requireAllowed permits inside sdcard without advanced mode`() {
        PathSafety.requireAllowed("/sdcard/DCIM", advancedMode = false) // fara exceptie
    }

    @Test fun `requireAllowed permits outside sdcard in advanced mode`() {
        PathSafety.requireAllowed("/data/local/tmp", advancedMode = true) // fara exceptie
    }

    @Test fun `requireAllowed rejects parent segment at sdcard root`() {
        val path = "/sdcard/.."
        assertThrows(SecurityException::class.java) {
            PathSafety.requireAllowed(path, advancedMode = false)
        }
        assertFalse(PathSafety.isWithinSdcard(path))
    }

    @Test fun `requireAllowed rejects nested parent traversal`() {
        val path = "/sdcard/DCIM/../../data/local/tmp"
        assertThrows(SecurityException::class.java) {
            PathSafety.requireAllowed(path, advancedMode = false)
        }
        assertFalse(PathSafety.isWithinSdcard(path))
    }

    @Test fun `requireAllowed rejects leave and return path`() {
        val path = "/sdcard/../sdcard/DCIM/photo.jpg"
        assertThrows(SecurityException::class.java) {
            PathSafety.requireAllowed(path, advancedMode = false)
        }
        assertFalse(PathSafety.isWithinSdcard(path))
    }

    @Test fun `requireAllowed rejects composed rename parent destination`() {
        val dest = RemotePaths.renameDest("/sdcard/photo.jpg", "../renamed.jpg")
        assertEquals("/sdcard/../renamed.jpg", dest)
        assertThrows(SecurityException::class.java) {
            PathSafety.requireAllowed(dest, advancedMode = false)
        }
    }

    @Test fun `requireAllowed rejects raw move parent destination`() {
        val destDir = "/sdcard/../data/local/tmp"
        assertThrows(SecurityException::class.java) {
            PathSafety.requireAllowed(destDir, advancedMode = false)
        }
    }

    @Test fun `requireAllowed permits literal dotted and hidden filenames`() {
        PathSafety.requireAllowed("/sdcard/DCIM/holiday..jpg", advancedMode = false)
        PathSafety.requireAllowed("/sdcard/DCIM/.holiday.jpg", advancedMode = false)
    }

    @Test fun `requireAllowed permits dot and repeated slash segments within sdcard`() {
        PathSafety.requireAllowed("/sdcard/./DCIM/photo.jpg", advancedMode = false)
        PathSafety.requireAllowed("/sdcard//DCIM//photo.jpg", advancedMode = false)
    }

    @Test fun `requireAllowed permits parent traversal and outside paths in advanced mode`() {
        PathSafety.requireAllowed("/sdcard/..", advancedMode = true)
        PathSafety.requireAllowed("/sdcard/DCIM/../../data/local/tmp", advancedMode = true)
        PathSafety.requireAllowed("/data/local/tmp", advancedMode = true)
    }
}
