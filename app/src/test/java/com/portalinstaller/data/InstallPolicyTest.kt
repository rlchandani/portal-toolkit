package com.portalinstaller.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallPolicyTest {

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
