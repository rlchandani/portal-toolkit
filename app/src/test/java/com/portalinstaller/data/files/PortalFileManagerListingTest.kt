/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.files

import dadb.AdbShellResponse
import dadb.Dadb
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class PortalFileManagerListingTest {
    private class Device(
        private val directory: String = "/sdcard",
        private val resolvedDirectory: String = "/storage/emulated/0",
        private val isSymlink: Boolean = true,
        private val contents: AdbShellResponse = AdbShellResponse(
            """
                total 16
                drwxrwx--x 2 root sdcard_rw 4096 2024-08-01 12:04 DCIM
                -rw-rw---- 1 root sdcard_rw 8 2024-07-30 09:11 photo.jpg
                lrwxrwxrwx 1 root root 15 2024-01-01 00:00 link -> /data/local/tmp
                -rw-rw---- 1 root sdcard_rw 2 2024-07-30 09:12 .hidden
            """.trimIndent(), "", 0
        )
    ) {
        val commands = mutableListOf<String>()
        val dadb = Proxy.newProxyInstance(Dadb::class.java.classLoader, arrayOf(Dadb::class.java)) { _, method, args ->
            require(method.name == "shell") { "Unexpected Dadb operation: ${method.name}" }
            val command = args!![0] as String
            commands += command
            when {
                command.startsWith("readlink -f ") -> {
                    val path = quotedArgument(command.removePrefix("readlink -f "))
                    AdbShellResponse(
                        when (path) {
                            "/sdcard" -> "/storage/emulated/0"
                            directory -> resolvedDirectory
                            else -> error("Unexpected readlink path: $path")
                        }, "", 0
                    )
                }
                command.startsWith("ls -laA ") -> {
                    val path = quotedArgument(command.removePrefix("ls -laA "))
                    when (path) {
                        directory.trimEnd('/') + "/" -> contents
                        directory -> if (isSymlink) {
                            // Toybox ls -l reports the symlink itself without a directory operand.
                            AdbShellResponse(
                                "lrwxrwxrwx 1 root root 21 2024-01-01 00:00 $directory -> /storage/self/primary",
                                "", 0
                            )
                        } else contents
                        else -> error("Unexpected ls path: $path")
                    }
                }
                else -> error("Unexpected shell command: $command")
            }
        } as Dadb

        private fun quotedArgument(argument: String): String {
            require(argument.startsWith("'") && argument.endsWith("'"))
            val body = argument.drop(1).dropLast(1)
            require("'" !in body.replace("'\\''", "")) { "Expected one safely quoted shell argument" }
            return body.replace("'\\''", "'")
        }
    }

    @Test fun `sdcard listing follows parent symlink and preserves child metadata in both modes`() = runTest {
        for (advancedMode in listOf(false, true)) {
            val device = Device()
            assertEquals(
                listOf(
                    RemoteFile("DCIM", "/sdcard/DCIM", true, false, 4096, "2024-08-01 12:04", "drwxrwx--x"),
                    RemoteFile("photo.jpg", "/sdcard/photo.jpg", false, false, 8, "2024-07-30 09:11", "-rw-rw----"),
                    RemoteFile("link", "/sdcard/link", false, true, 15, "2024-01-01 00:00", "lrwxrwxrwx"),
                    RemoteFile(".hidden", "/sdcard/.hidden", false, false, 2, "2024-07-30 09:12", "-rw-rw----")
                ),
                PortalFileManager().list(device.dadb, "/sdcard", advancedMode)
            )
        }
    }

    @Test fun `listing accepts an existing trailing slash without changing child paths`() = runTest {
        val files = PortalFileManager().list(Device().dadb, "/sdcard/")
        assertEquals(listOf("/sdcard/DCIM", "/sdcard/photo.jpg", "/sdcard/link", "/sdcard/.hidden"), files.map { it.path })
    }

    @Test fun `root directory listing keeps child symlink metadata`() = runTest {
        val device = Device(
            directory = "/", resolvedDirectory = "/", isSymlink = false,
            contents = AdbShellResponse("lrwxrwxrwx 1 root root 21 2024-01-01 00:00 sdcard -> /storage/self/primary", "", 0)
        )
        assertEquals(
            listOf(RemoteFile("sdcard", "/sdcard", false, true, 21, "2024-01-01 00:00", "lrwxrwxrwx")),
            PortalFileManager().list(device.dadb, "/", advancedMode = true)
        )
    }

    @Test fun `listing safely quotes directory paths containing spaces apostrophes and shell syntax`() = runTest {
        val path = "/sdcard/Album's \$(touch injected); photos"
        val device = Device(directory = path, resolvedDirectory = "/storage/emulated/0/album")
        val files = PortalFileManager().list(device.dadb, path)
        assertEquals(
            listOf("$path/DCIM", "$path/photo.jpg", "$path/link", "$path/.hidden"),
            files.map { it.path }
        )
    }

    @Test fun `listing rejects a parent symlink outside shared storage before ls`() = runTest {
        val device = Device(directory = "/sdcard/escape", resolvedDirectory = "/data/local/tmp")
        val failure = runCatching { PortalFileManager().list(device.dadb, "/sdcard/escape") }.exceptionOrNull()
        assertTrue(failure is SecurityException)
        assertFalse(device.commands.any { it.startsWith("ls ") })
    }

    @Test fun `listing rejects traversal outside shared storage before any shell command`() = runTest {
        val device = Device()
        val failure = runCatching { PortalFileManager().list(device.dadb, "/sdcard/../data") }.exceptionOrNull()
        assertTrue(failure is SecurityException)
        assertTrue(device.commands.isEmpty())
    }

    @Test fun `listing rejects control characters even in advanced mode before any shell command`() = runTest {
        for (path in listOf("/sdcard/\u0000", "/sdcard/\n", "/sdcard/\r")) {
            val device = Device()
            val failure = runCatching { PortalFileManager().list(device.dadb, path, advancedMode = true) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(device.commands.isEmpty())
        }
    }

    @Test fun `inaccessible directory listing fails instead of appearing empty`() = runTest {
        val device = Device(contents = AdbShellResponse("", "Permission denied", 1))
        val failure = runCatching { PortalFileManager().list(device.dadb, "/sdcard") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("Permission denied"))
    }
}
