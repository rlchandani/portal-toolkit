package com.portalinstaller.data.files

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PathSafetyTest {
    @Test fun `traversal is normalized before confinement`() {
        assertTrue(!PathSafety.isWithinSdcard("/sdcard/../data/local/tmp"))
        assertTrue(!PathSafety.isWithinSdcard("/sdcard/Pictures/../../data"))
        assertTrue(PathSafety.isWithinSdcard("/sdcard/Pictures/../Download"))
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
}
