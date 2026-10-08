package com.portalinstaller.data.files

import dadb.AdbShellResponse
import dadb.Dadb
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.nio.file.Files

class PortalFileManagerReliabilityTest {
    private class Device {
        val files = mutableMapOf("/sdcard/photo.jpg" to "original".toByteArray())
        val commands = mutableListOf<String>()
        var failPush = false
        var failRename = false
        var corruptPush = false
        var escapedPath: String? = null
        val dadb = Proxy.newProxyInstance(Dadb::class.java.classLoader, arrayOf(Dadb::class.java)) { _, method, args ->
            when (method.name) {
                "shell" -> {
                    val command = args!![0] as String
                    commands += command
                    val paths = Regex("'([^']*)'").findAll(command).map { it.groupValues[1] }.toList()
                    val path = paths.firstOrNull().orEmpty()
                    when {
                        command.startsWith("readlink") -> AdbShellResponse(
                            escapedPath ?: path.replace("/sdcard", "/storage/emulated/0"), "", 0
                        )
                        command.startsWith("stat") -> AdbShellResponse(files[path]?.size?.toString().orEmpty(), "", 0)
                        command.startsWith("sha256sum") -> AdbShellResponse(
                            files[path]?.let { bytes ->
                                java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                                    .joinToString("") { "%02x".format(it) }
                            }.orEmpty(), "", 0
                        )
                        command.contains("mv ") -> {
                            if (failRename) AdbShellResponse("", "rename failed", 1)
                            else {
                                val (source, dest) = paths.takeLast(2)
                                files[dest] = files.remove(source) ?: error("Missing staged file")
                                AdbShellResponse("", "", 0)
                            }
                        }
                        command.startsWith("rm ") -> {
                            files.remove(path)
                            AdbShellResponse("", "", 0)
                        }
                        else -> AdbShellResponse("", "", 0)
                    }
                }
                "push" -> {
                    val input = args!![0]
                    val bytes = if (input is File) input.readBytes() else {
                        val buffer = okio.Buffer()
                        buffer.writeAll(input as okio.Source)
                        buffer.readByteArray()
                    }
                    files[args[1] as String] = if (corruptPush) byteArrayOf(1) else bytes
                    if (failPush) throw IOException("link lost")
                    null
                }
                "close" -> null
                "toString" -> "FakeDevice"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> args?.get(0) === this
                else -> error("Unexpected Dadb operation: ${method.name}")
            }
        } as Dadb
    }

    private suspend fun withUpload(test: suspend (File) -> Unit) {
        val dir = Files.createTempDirectory("atomic-upload").toFile()
        try {
            val local = File(dir, "photo.jpg").apply { writeText("replacement") }
            test(local)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `failed push preserves existing destination and removes only staging`() = runTest {
        withUpload { local ->
            val device = Device().apply { failPush = true }
            assertTrue(runCatching { PortalFileManager().push(device.dadb, local, "/sdcard", false) }.isFailure)
            assertEquals("original", device.files["/sdcard/photo.jpg"]?.decodeToString())
            assertEquals(setOf("/sdcard/photo.jpg"), device.files.keys)
        }
    }

    @Test fun `rename failure preserves existing destination`() = runTest {
        withUpload { local ->
            val device = Device().apply { failRename = true }
            assertTrue(runCatching { PortalFileManager().push(device.dadb, local, "/sdcard", false) }.isFailure)
            assertEquals("original", device.files["/sdcard/photo.jpg"]?.decodeToString())
            assertEquals(setOf("/sdcard/photo.jpg"), device.files.keys)
        }
    }

    @Test fun `corrupt upload never replaces destination`() = runTest {
        withUpload { local ->
            val device = Device().apply { corruptPush = true }
            assertTrue(runCatching { PortalFileManager().push(device.dadb, local, "/sdcard", false) }.isFailure)
            assertEquals("original", device.files["/sdcard/photo.jpg"]?.decodeToString())
        }
    }

    @Test fun `cancelled upload preserves destination and removes staging`() = runTest {
        withUpload { local ->
            val device = Device()
            lateinit var upload: kotlinx.coroutines.Job
            upload = launch(start = CoroutineStart.LAZY) {
                PortalFileManager().push(device.dadb, local, "/sdcard", false) { upload.cancel() }
            }
            upload.start()
            upload.join()
            assertTrue(upload.isCancelled)
            assertEquals("original", device.files["/sdcard/photo.jpg"]?.decodeToString())
            assertEquals(setOf("/sdcard/photo.jpg"), device.files.keys)
            assertFalse(device.commands.any { it.contains("mv ") })
        }
    }

    @Test fun `successful upload replaces destination after verification`() = runTest {
        withUpload { local ->
            val device = Device()
            assertEquals("/sdcard/photo.jpg", PortalFileManager().push(device.dadb, local, "/sdcard", false))
            assertEquals("replacement", device.files["/sdcard/photo.jpg"]?.decodeToString())
            assertTrue(device.commands.any { it.startsWith("stat") })
            assertEquals(setOf("/sdcard/photo.jpg"), device.files.keys)
        }
    }

    @Test fun `rename rejects traversal before invoking mv`() = runTest {
        val device = Device()
        val file = RemoteFile("photo.jpg", "/sdcard/photo.jpg", false, false, 8, "", "")
        assertTrue(runCatching { PortalFileManager().rename(device.dadb, file, "../data/file", false) }.isFailure)
        assertFalse(device.commands.any { it.contains("mv ") })
    }

    @Test fun `symlink destination outside storage is rejected`() = runTest {
        withUpload { local ->
            val device = Device()
            // /sdcard resolves normally; its child points outside shared storage.
            val proxy = Proxy.newProxyInstance(Dadb::class.java.classLoader, arrayOf(Dadb::class.java)) { _, method, args ->
                if (method.name == "shell" && (args!![0] as String).startsWith("readlink")) {
                    val command = args[0] as String
                    AdbShellResponse(if (command.contains("'/sdcard'")) "/storage/emulated/0" else "/data/local/tmp", "", 0)
                } else method.invoke(device.dadb, *(args ?: emptyArray()))
            } as Dadb
            assertTrue(runCatching { PortalFileManager().push(proxy, local, "/sdcard/link", false) }.isFailure)
            assertEquals("original", device.files["/sdcard/photo.jpg"]?.decodeToString())
        }
    }
}
