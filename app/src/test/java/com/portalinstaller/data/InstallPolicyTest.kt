package com.portalinstaller.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallPolicyTest {
    @Test fun `device API 28 rejects apps requiring API 29`() {
        assertFalse(InstallPolicy.isCompatible(29, 28, listOf("arm64-v8a")))
        assertTrue(InstallPolicy.isCompatible(29, 29, listOf("arm64-v8a")))
        assertFalse(InstallPolicy.isCompatible(24, 29, listOf("arm64-v8a"), setOf("x86")))
        assertTrue(InstallPolicy.isCompatible(24, 29, listOf("arm64-v8a", "armeabi-v7a"), setOf("armeabi-v7a")))
    }

    @Test
    fun `null minSdk is treated as compatible`() {
        assertTrue(InstallPolicy.isCompatible(null))
    }

    @Test
    fun `minSdk below the portal ceiling is compatible`() {
        assertTrue(InstallPolicy.isCompatible(21))
    }

    @Test
    fun `minSdk equal to the portal ceiling is compatible`() {
        assertTrue(InstallPolicy.isCompatible(PortalDeviceConfig.MAX_SDK))
    }

    @Test
    fun `minSdk above the portal ceiling is incompatible`() {
        assertFalse(InstallPolicy.isCompatible(PortalDeviceConfig.MAX_SDK + 1))
        assertFalse(InstallPolicy.isCompatible(34))
    }
}
