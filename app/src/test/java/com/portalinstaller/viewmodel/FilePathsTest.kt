package com.portalinstaller.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class FilePathsTest {
    @Test fun `parent of nested path`() =
        assertEquals("/sdcard/DCIM", FilePaths.parentOf("/sdcard/DCIM/x.jpg"))

    @Test fun `parent of sdcard stays at sdcard`() =
        assertEquals("/sdcard", FilePaths.parentOf("/sdcard"))

    @Test fun `parent of top-level dir is root`() =
        assertEquals("/", FilePaths.parentOf("/data"))

    @Test fun `parent of root stays at root`() =
        assertEquals("/", FilePaths.parentOf("/"))

    @Test fun `trailing slash tolerated`() =
        assertEquals("/sdcard", FilePaths.parentOf("/sdcard/DCIM/"))
}
