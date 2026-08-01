package com.portalinstaller.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AdbErrorsTest {
    @Test fun `device offline maps to offline key`() =
        assertEquals(AdbReasonKey.DEVICE_OFFLINE, classifyAdbReason("error: device offline"))

    @Test fun `broken pipe maps to connection closed`() =
        assertEquals(AdbReasonKey.CONNECTION_CLOSED, classifyAdbReason("Broken pipe"))

    @Test fun `connection reset maps to connection closed`() =
        assertEquals(AdbReasonKey.CONNECTION_CLOSED, classifyAdbReason("Connection reset by peer"))

    @Test fun `timeout maps to timed out`() =
        assertEquals(AdbReasonKey.TIMED_OUT, classifyAdbReason("operation timed out"))

    @Test fun `permission denied maps to permission key`() =
        assertEquals(AdbReasonKey.PERMISSION_DENIED, classifyAdbReason("shell: permission denied"))

    @Test fun `no space maps to storage full`() =
        assertEquals(AdbReasonKey.NO_SPACE_LEFT, classifyAdbReason("No space left on device"))

    @Test fun `version downgrade maps to its own key`() =
        assertEquals(AdbReasonKey.INSTALL_VERSION_DOWNGRADE, classifyAdbReason("INSTALL_FAILED_VERSION_DOWNGRADE"))

    @Test fun `update incompatible maps to its own key`() =
        assertEquals(AdbReasonKey.INSTALL_UPDATE_INCOMPATIBLE, classifyAdbReason("INSTALL_FAILED_UPDATE_INCOMPATIBLE"))

    @Test fun `invalid apk maps to its own key`() =
        assertEquals(AdbReasonKey.INSTALL_INVALID_APK, classifyAdbReason("INSTALL_FAILED_INVALID_APK"))

    @Test fun `older sdk maps to its own key`() =
        assertEquals(AdbReasonKey.INSTALL_OLDER_SDK, classifyAdbReason("INSTALL_FAILED_OLDER_SDK"))

    @Test fun `parse failed maps to its own key`() =
        assertEquals(AdbReasonKey.INSTALL_PARSE_FAILED, classifyAdbReason("INSTALL_PARSE_FAILED_NO_CERTIFICATES"))

    @Test fun `security exception maps to its own key`() =
        assertEquals(AdbReasonKey.SECURITY_EXCEPTION, classifyAdbReason("java.lang.SecurityException"))

    @Test fun `unknown message maps to fallback`() =
        assertEquals(AdbReasonKey.FALLBACK, classifyAdbReason("something weird"))

    @Test fun `null maps to fallback`() =
        assertEquals(AdbReasonKey.FALLBACK, classifyAdbReason(null))

    @Test fun `blank maps to fallback`() =
        assertEquals(AdbReasonKey.FALLBACK, classifyAdbReason("   "))
}
