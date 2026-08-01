package com.portalinstaller.data.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellCommandClassifierTest {

    @Test
    fun `benign read commands are safe`() {
        assertFalse(ShellCommandClassifier.isDestructive("pm list packages -3"))
        assertFalse(ShellCommandClassifier.isDestructive("getprop ro.build.version.sdk"))
        assertFalse(ShellCommandClassifier.isDestructive("df -h"))
        assertFalse(ShellCommandClassifier.isDestructive("  wm size  "))
    }

    @Test
    fun `rm and recursive deletes are destructive`() {
        assertTrue(ShellCommandClassifier.isDestructive("rm -rf /sdcard/x"))
        assertTrue(ShellCommandClassifier.isDestructive("rm file"))
    }

    @Test
    fun `power and package mutations are destructive`() {
        assertTrue(ShellCommandClassifier.isDestructive("reboot"))
        assertTrue(ShellCommandClassifier.isDestructive("reboot recovery"))
        assertTrue(ShellCommandClassifier.isDestructive("pm disable com.x"))
        assertTrue(ShellCommandClassifier.isDestructive("pm uninstall com.x"))
        assertTrue(ShellCommandClassifier.isDestructive("pm clear com.x"))
    }

    @Test
    fun `settings put mutates state`() {
        assertTrue(ShellCommandClassifier.isDestructive("settings put system screen_brightness 10"))
        assertFalse(ShellCommandClassifier.isDestructive("settings get system screen_brightness"))
    }

    @Test
    fun `classification ignores leading whitespace and case of keyword`() {
        assertTrue(ShellCommandClassifier.isDestructive("   RM -rf /x"))
    }
}
