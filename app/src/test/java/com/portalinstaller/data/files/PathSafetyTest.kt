package com.portalinstaller.data.files

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PathSafetyTest {
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
