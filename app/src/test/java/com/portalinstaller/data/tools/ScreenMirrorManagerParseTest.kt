package com.portalinstaller.data.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenMirrorManagerParseTest {

    @Test
    fun `parses physical size line`() {
        val size = ScreenMirrorManager.parseWmSize("Physical size: 1280x800")
        assertEquals(MirrorSize(1280, 800), size)
    }

    @Test
    fun `override size wins over physical size`() {
        val out = "Physical size: 1280x800\nOverride size: 800x1280"
        assertEquals(MirrorSize(800, 1280), ScreenMirrorManager.parseWmSize(out))
    }

    @Test
    fun `tolerates surrounding whitespace and carriage returns`() {
        val size = ScreenMirrorManager.parseWmSize("  Physical size: 1920x1080\r\n")
        assertEquals(MirrorSize(1920, 1080), size)
    }

    @Test
    fun `returns null for malformed output`() {
        assertNull(ScreenMirrorManager.parseWmSize("cannot determine size"))
    }

    @Test
    fun `returns null for empty output`() {
        assertNull(ScreenMirrorManager.parseWmSize(""))
    }
}
