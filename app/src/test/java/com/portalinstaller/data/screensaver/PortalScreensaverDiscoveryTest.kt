/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.screensaver

import com.portalinstaller.data.PortalScreensaverManager
import dadb.AdbShellResponse
import dadb.Dadb
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

class PortalScreensaverDiscoveryTest {
    @Test fun `discovery follows Android shared storage root symlink`() = runTest {
        val dadb = Proxy.newProxyInstance(
            Dadb::class.java.classLoader, arrayOf(Dadb::class.java)
        ) { _, method, args ->
            require(method.name == "shell")
            val command = args!![0] as String
            // API 29 toybox find reports no descendants for a symlink root
            // without the directory slash, even though its exit status is zero.
            val output = if (command.startsWith("find /sdcard/ ")) {
                "/sdcard/Pictures/PortalInstaller/a.JPG\n" +
                    "/sdcard/Pictures/PortalInstaller/b.png\n" +
                    "/sdcard/DCIM/Camera/c.HEIC\n"
            } else ""
            AdbShellResponse(output, "", 0)
        } as Dadb

        assertEquals(
            listOf(
                ImageFolder("/sdcard/Pictures/PortalInstaller", 2),
                ImageFolder("/sdcard/DCIM/Camera", 1)
            ),
            PortalScreensaverManager().findImageFolders(dadb)
        )
    }
}
