package com.portalinstaller.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PortalBackupManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `only the current format version is supported`() {
        assertTrue(PortalBackupManager.isSupportedFormat(PortalBackupManager.FORMAT_VERSION))
        assertFalse(PortalBackupManager.isSupportedFormat(0))
        assertFalse(PortalBackupManager.isSupportedFormat(PortalBackupManager.FORMAT_VERSION + 1))
    }

    @Test
    fun `package names are validated against shell-safe characters`() {
        assertTrue(PortalBackupManager.isValidPackageName("com.facebook.portal"))
        assertTrue(PortalBackupManager.isValidPackageName("com.example.app_2"))
        assertFalse(PortalBackupManager.isValidPackageName("com.evil; rm -rf /"))
        assertFalse(PortalBackupManager.isValidPackageName("has space"))
        assertFalse(PortalBackupManager.isValidPackageName(""))
    }

    @Test
    fun `restore stops after the consecutive failure threshold`() {
        assertFalse(PortalBackupManager.shouldStopRestore(1))
        assertFalse(PortalBackupManager.shouldStopRestore(2))
        assertTrue(PortalBackupManager.shouldStopRestore(3))
        assertTrue(PortalBackupManager.shouldStopRestore(4))
    }

    @Test
    fun `safeArchiveFile resolves entries inside the work directory`() {
        val root = tempFolder.newFolder("work")
        val resolved = PortalBackupManager.safeArchiveFile(root, "apps/com.example/0_base.apk")
        assertEquals(
            File(root, "apps/com.example/0_base.apk").canonicalPath,
            resolved.canonicalPath
        )
    }

    @Test
    fun `safeArchiveFile rejects zip-slip path traversal`() {
        val root = tempFolder.newFolder("work")
        assertThrows(IllegalArgumentException::class.java) {
            PortalBackupManager.safeArchiveFile(root, "../evil.apk")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PortalBackupManager.safeArchiveFile(root, "apps/../../etc/passwd")
        }
    }
}
