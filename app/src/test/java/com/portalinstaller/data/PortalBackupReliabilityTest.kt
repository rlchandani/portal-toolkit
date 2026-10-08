/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data

import dadb.AdbShellResponse
import dadb.Dadb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import com.portalinstaller.viewmodel.BackupState
import com.portalinstaller.viewmodel.backupRestoreOutcome
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PortalBackupReliabilityTest {
    @get:Rule val temporary = TemporaryFolder()
    private val manager = PortalBackupManager()

    /** Only this in-memory transport can receive install/setting/cleanup commands. */
    private class Device {
        val commands = mutableListOf<String>()
        val attempts = mutableListOf<List<String>>()
        val staging = mutableMapOf<String, ByteArray>()
        val installed = mutableMapOf("com.example.one" to "old-apk")
        val appData = mutableMapOf("com.example.one" to "owner-data")
        val settings = mutableMapOf("system.screen_brightness" to "88")
        val responses = ArrayDeque<AdbShellResponse>()
        var pushFailure: Exception? = null
        var pullFailure: Exception? = null
        var emptyPull = false
        val pulled = mutableListOf<String>()
        var settingResponse = AdbShellResponse("", "", 0)

        private fun install(bytes: List<ByteArray>): AdbShellResponse {
            val apks = bytes.map { it.decodeToString() }
            attempts += apks
            val response = responses.removeFirstOrNull() ?: AdbShellResponse("Success", "", 0)
            if (response.exitCode == 0 && response.allOutput.trim() == "Success") {
                installed[apks.first().substringBefore(':')] = apks.joinToString()
            }
            return response
        }

        val dadb = Proxy.newProxyInstance(Dadb::class.java.classLoader, arrayOf(Dadb::class.java)) { proxy, method, args ->
            when (method.name) {
                "push" -> {
                    val path = args!![1] as String
                    staging[path] = (args[0] as File).readBytes()
                    pushFailure?.let { throw it }
                    null
                }
                "pull" -> {
                    val remote = args!![1] as String
                    pulled += remote
                    pullFailure?.let { throw it }
                    val pkg = remote.removePrefix("/data/app/").substringBefore('/')
                    (args[0] as File).writeText(if (emptyPull) "" else "$pkg:backup")
                    null
                }
                "installMultiple" -> {
                    check((args!![1] as Array<*>).toList() == listOf("-r")) {
                        "Restore must retain installed app data"
                    }
                    val result = install((args[0] as List<*>).map { (it as File).readBytes() })
                    if (result.exitCode != 0 || result.allOutput.trim() != "Success") {
                        throw IOException(result.allOutput)
                    }
                    null
                }
                "shell" -> {
                    val command = args!![0] as String
                    commands += command
                    val quoted = Regex("'([^']*)'").findAll(command).map { it.groupValues[1] }.toList()
                    when {
                        command == "pm list packages -3" -> AdbShellResponse(
                            "package:com.example.one\npackage:com.example.one.beta", "", 0)
                        command.startsWith("pm path ") -> AdbShellResponse(
                            "package:/data/app/${quoted.single()}/base.apk", "", 0)
                        command.startsWith("settings get ") -> AdbShellResponse(
                            if (command.endsWith("screen_brightness")) "88" else "null", "", 0)
                        command.startsWith("getprop ") -> AdbShellResponse("fake-device", "", 0)
                        command.startsWith("pm install -r ") -> install(listOf(staging.getValue(quoted.single())))
                        command.startsWith("rm -f ") -> {
                            check(quoted.single().startsWith("/data/local/tmp/portal_restore_"))
                            staging.remove(quoted.single())
                            AdbShellResponse("", "", 0)
                        }
                        command.startsWith("settings put ") -> {
                            if (settingResponse.exitCode == 0) {
                                val parts = command.split(' ')
                                settings["${parts[2]}.${parts[3]}"] = quoted.single()
                            }
                            settingResponse
                        }
                        else -> error("Unexpected device mutation: $command")
                    }
                }
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "BackupFakeDevice"
                else -> error("Unexpected transport operation: ${method.name}")
            }
        } as Dadb
    }

    private fun app(packageName: String, vararg files: String) =
        JSONObject().put("packageName", packageName).put("files", JSONArray(files.toList()))

    private fun backup(vararg apps: JSONObject): File {
        val root = temporary.newFolder()
        File(root, "manifest.json").writeText(
            JSONObject().put("formatVersion", 1).put("apps", JSONArray(apps.toList()))
                .put("settings", JSONObject().put("system.screen_brightness", "17")).toString()
        )
        return root
    }

    private fun apk(root: File, packageName: String, name: String = "base.apk", content: String = "$packageName:replacement") {
        File(root, "apps/$packageName/$name").apply { requireNotNull(parentFile).mkdirs(); writeText(content) }
    }

    private suspend fun restore(root: File, device: Device, progress: (Int, Int, String) -> Unit = { _, _, _ -> }) =
        manager.restoreBackup(device.dadb, root,
            readPackageName = { it.readText().substringBefore(':') },
            stoppedReason = { count, reason -> "$count: $reason" },
            onProgress = progress)

    private fun assertOwnerData(device: Device) {
        assertEquals(mapOf("com.example.one" to "owner-data"), device.appData)
        assertFalse(device.commands.any { it.startsWith("pm uninstall") || it.startsWith("pm clear") })
        assertTrue(device.staging.isEmpty())
    }

    private suspend fun assertPreflightRejected(root: File) {
        val device = Device()
        assertTrue(runCatching { restore(root, device) }.isFailure)
        assertEquals(mapOf("com.example.one" to "old-apk"), device.installed)
        assertEquals(mapOf("system.screen_brightness" to "88"), device.settings)
        assertTrue(device.attempts.isEmpty())
        assertTrue(device.commands.isEmpty())
        assertOwnerData(device)
    }

    @Test fun `selected backup targets exact installed package and exports complete archive`() = runTest {
        val device = Device()
        val output = ByteArrayOutputStream()
        val summary = manager.createBackup(device.dadb, temporary.newFolder(), { output },
            selectedPackages = setOf("com.example.one"))
        assertEquals(PortalBackupSummary(1, 1), summary)
        assertEquals(listOf("/data/app/com.example.one/base.apk"), device.pulled)
        val entries = mutableMapOf<String, String>()
        java.util.zip.ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes().decodeToString()
                entry = zip.nextEntry
            }
        }
        assertEquals(setOf("manifest.json", "apps/com.example.one/0_base.apk"), entries.keys)
        val manifest = JSONObject(entries.getValue("manifest.json"))
        assertEquals("com.example.one", manifest.getJSONArray("apps").getJSONObject(0).getString("packageName"))
        assertEquals("com.example.one:backup", entries["apps/com.example.one/0_base.apk"])
        assertEquals(1, manager.inspectBackup(ByteArrayInputStream(output.toByteArray())).appCount)
        assertOwnerData(device)
    }

    @Test fun `failed or empty APK collection preserves existing backup destination`() = runTest {
        listOf(Device().apply { pullFailure = IOException("link lost") }, Device().apply { emptyPull = true }).forEach { device ->
            val destination = ByteArrayOutputStream().apply { write("existing-backup".toByteArray()) }
            val failure = runCatching {
                manager.createBackup(device.dadb, temporary.newFolder(),
                    openOutput = { destination.apply { reset() } },
                    selectedPackages = setOf("com.example.one"))
            }.exceptionOrNull()
            assertNotNull(failure)
            assertEquals("existing-backup", destination.toString("UTF-8"))
            assertOwnerData(device)
        }
    }

    @Test fun `cancelled APK collection preserves existing backup destination`() = runTest {
        val device = Device()
        val destination = ByteArrayOutputStream().apply { write("existing-backup".toByteArray()) }
        lateinit var job: kotlinx.coroutines.Job
        job = launch(start = CoroutineStart.LAZY) {
            manager.createBackup(device.dadb, temporary.newFolder(),
                openOutput = { destination.apply { reset() } },
                onProgress = { _, _, _ -> job.cancel() })
        }
        job.start(); job.join()
        assertTrue(job.isCancelled)
        assertEquals("existing-backup", destination.toString("UTF-8"))
        assertTrue(device.pulled.isEmpty())
        assertOwnerData(device)
    }

    @Test fun `missing later APK rejects entire restore before first device mutation`() = runTest {
        val root = backup(app("com.example.one", "base.apk"), app("com.example.two", "base.apk"))
        apk(root, "com.example.one")
        assertPreflightRejected(root)
    }

    @Test fun `missing split cannot silently downgrade restore to base only`() = runTest {
        val root = backup(app("com.example.one", "base.apk", "split.apk"))
        apk(root, "com.example.one")
        assertPreflightRejected(root)
    }

    @Test fun `empty split is rejected before installing base`() = runTest {
        val root = backup(app("com.example.one", "base.apk", "split.apk"))
        apk(root, "com.example.one")
        apk(root, "com.example.one", "split.apk", "")
        assertPreflightRejected(root)
    }

    @Test fun `wrong package in later APK is rejected before earlier app installation`() = runTest {
        val root = backup(app("com.example.one", "base.apk"), app("com.example.two", "base.apk"))
        apk(root, "com.example.one")
        apk(root, "com.example.two", content = "com.example.unrelated:replacement")
        assertPreflightRejected(root)
    }

    @Test fun `duplicate package is rejected before earlier app installation`() = runTest {
        val root = backup(app("com.example.one", "base.apk"), app("com.example.one", "base.apk"))
        apk(root, "com.example.one")
        assertPreflightRejected(root)
    }

    @Test fun `duplicate APK reference is rejected before split install`() = runTest {
        val root = backup(app("com.example.one", "base.apk", "base.apk"))
        apk(root, "com.example.one")
        assertPreflightRejected(root)
    }

    @Test fun `manifest APK path cannot reuse a sibling package file`() = runTest {
        val root = backup(app("com.example.one", "../com.example.two/base.apk"))
        apk(root, "com.example.two", content = "com.example.one:replacement")
        assertPreflightRejected(root)
    }

    @Test fun `cancelled push propagates cancellation without continuing or changing settings`() = runTest {
        val root = backup(app("com.example.one", "base.apk"), app("com.example.two", "base.apk"))
        apk(root, "com.example.one"); apk(root, "com.example.two")
        val device = Device().apply { pushFailure = CancellationException("cancelled") }
        val result = runCatching { restore(root, device) }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(mapOf("system.screen_brightness" to "88"), device.settings)
        assertEquals(mapOf("com.example.one" to "old-apk"), device.installed)
        assertTrue(device.attempts.isEmpty())
        assertOwnerData(device)
    }

    @Test fun `cancellation from progress prevents that app reaching transport`() = runTest {
        val root = backup(app("com.example.one", "base.apk"))
        apk(root, "com.example.one")
        val device = Device()
        lateinit var job: kotlinx.coroutines.Job
        job = launch(start = CoroutineStart.LAZY) { restore(root, device) { _, _, _ -> job.cancel() } }
        job.start(); job.join()
        assertTrue(job.isCancelled)
        assertTrue(device.attempts.isEmpty())
        assertEquals(mapOf("com.example.one" to "old-apk"), device.installed)
        assertEquals(mapOf("system.screen_brightness" to "88"), device.settings)
        assertOwnerData(device)
    }

    @Test fun `signature rejection retains installed app data and original settings`() = runTest {
        val root = backup(app("com.example.one", "base.apk"))
        apk(root, "com.example.one")
        val device = Device().apply {
            responses += AdbShellResponse("", "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]", 1)
        }
        val result = restore(root, device)
        assertEquals(0, result.installedCount)
        assertEquals(listOf("com.example.one"), result.failedPackages)
        assertEquals(0, result.restoredSettingCount)
        assertEquals(mapOf("com.example.one" to "old-apk"), device.installed)
        assertEquals(mapOf("system.screen_brightness" to "88"), device.settings)
        assertOwnerData(device)
    }

    @Test fun `API ABI and version rejection preserve installed app data and settings`() = runTest {
        listOf("INSTALL_FAILED_OLDER_SDK", "INSTALL_FAILED_NO_MATCHING_ABIS", "INSTALL_FAILED_VERSION_DOWNGRADE").forEach { code ->
            val root = backup(app("com.example.one", "base.apk"))
            apk(root, "com.example.one")
            val device = Device().apply { responses += AdbShellResponse("", "Failure [$code]", 1) }
            val result = restore(root, device)
            assertEquals(0, result.installedCount)
            assertEquals(listOf("com.example.one"), result.failedPackages)
            assertEquals(0, result.restoredSettingCount)
            assertEquals(mapOf("com.example.one" to "old-apk"), device.installed)
            assertEquals(mapOf("system.screen_brightness" to "88"), device.settings)
            assertOwnerData(device)
        }
    }

    @Test fun `failed push removes only staging and retains all owner data`() = runTest {
        val root = backup(app("com.example.one", "base.apk"))
        apk(root, "com.example.one")
        val device = Device().apply { pushFailure = IOException("link lost") }
        val result = restore(root, device)
        assertEquals(0, result.installedCount)
        assertEquals(listOf("com.example.one"), result.failedPackages)
        assertEquals(0, result.restoredSettingCount)
        assertEquals(mapOf("com.example.one" to "old-apk"), device.installed)
        assertEquals(mapOf("system.screen_brightness" to "88"), device.settings)
        assertTrue(device.attempts.isEmpty())
        assertOwnerData(device)
    }

    @Test fun `three consecutive failures stop restore and success resets the threshold`() = runTest {
        val names = (1..7).map { "com.example.app$it" }
        val root = backup(*names.map { app(it, "base.apk") }.toTypedArray())
        names.forEach { apk(root, it) }
        val device = Device().apply {
            listOf(false, false, true, false, false, false).forEach {
                responses += if (it) AdbShellResponse("Success", "", 0) else AdbShellResponse("", "Failure [rejected]", 1)
            }
        }
        val result = restore(root, device)
        assertEquals(1, result.installedCount)
        assertEquals(listOf(names[0], names[1], names[3], names[4], names[5]), result.failedPackages)
        assertNotNull(result.stoppedEarlyReason)
        assertEquals(6, device.attempts.size)
        assertFalse(device.installed.containsKey(names[6]))
        assertEquals(0, result.restoredSettingCount)
        assertEquals(mapOf("system.screen_brightness" to "88"), device.settings)
        assertOwnerData(device)
    }

    @Test fun `nonzero install exit cannot count misleading Success output as installed`() = runTest {
        val root = backup(app("com.example.one", "base.apk"))
        apk(root, "com.example.one")
        val device = Device().apply { responses += AdbShellResponse("Success", "", 1) }
        val result = restore(root, device)
        assertEquals(0, result.installedCount)
        assertEquals(listOf("com.example.one"), result.failedPackages)
        assertEquals(mapOf("system.screen_brightness" to "88"), device.settings)
        assertOwnerData(device)
    }

    @Test fun `failed setting write is not counted as restored`() = runTest {
        val root = backup()
        val device = Device().apply { settingResponse = AdbShellResponse("", "Permission denied", 1) }
        val result = restore(root, device)
        assertEquals(0, result.restoredSettingCount)
        assertEquals(listOf("system.screen_brightness: Permission denied"), result.failedSettings)
        assertEquals(mapOf("system.screen_brightness" to "88"), device.settings)
        assertOwnerData(device)
    }

    @Test fun `failed setting write cannot report a successful restore outcome`() = runTest {
        val root = backup()
        val device = Device().apply { settingResponse = AdbShellResponse("", "Permission denied", 1) }
        val summary = restore(root, device)
        assertTrue(backupRestoreOutcome(summary, "Restore result") is BackupState.Failed)
        assertEquals(mapOf("system.screen_brightness" to "88"), device.settings)
        assertOwnerData(device)
    }

    @Test fun `complete split restore keeps data and applies settings after confirmed install`() = runTest {
        val root = backup(app("com.example.one", "base.apk", "split.apk"))
        apk(root, "com.example.one"); apk(root, "com.example.one", "split.apk", "com.example.one:split")
        val device = Device()
        val result = restore(root, device)
        assertEquals(1, result.installedCount)
        assertTrue(result.failedPackages.isEmpty())
        assertEquals(1, result.restoredSettingCount)
        assertEquals(listOf(listOf("com.example.one:replacement", "com.example.one:split")), device.attempts)
        assertEquals(mapOf("system.screen_brightness" to "17"), device.settings)
        assertOwnerData(device)
    }

    private fun archive(vararg entries: Pair<String, String>): ByteArray =
        ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray()); zip.closeEntry()
                }
            }
        }.toByteArray()

    @Test fun `invalid archives fail extraction or preflight without any device mutation`() = runTest {
        val cases = listOf(
            "not a zip".toByteArray(),
            archive("manifest.json" to "{"),
            archive("manifest.json" to """{"formatVersion":99,"apps":[]}"""),
            archive("manifest.json" to """{"formatVersion":1,"apps":[{"packageName":"com.evil;rm","files":["base.apk"]}]}"""),
            archive("../outside.apk" to "bad"),
            archive("apps/com.example.one/base.apk" to "com.example.one:replacement")
        )
        cases.forEach { bytes ->
            val root = temporary.newFolder()
            val device = Device()
            assertTrue(runCatching {
                manager.extractArchive(ByteArrayInputStream(bytes), root)
                restore(root, device)
            }.isFailure)
            assertTrue(device.commands.isEmpty())
            assertTrue(device.attempts.isEmpty())
            assertOwnerData(device)
        }
        assertFalse(File(temporary.root, "outside.apk").exists())
    }

    @Test fun `truncated entry is rejected before restore`() = runTest {
        val bytes = archive("manifest.json" to """{"formatVersion":1,"apps":[]}""")
        val root = temporary.newFolder()
        val device = Device()
        assertTrue(runCatching {
            manager.extractArchive(ByteArrayInputStream(bytes.copyOf(50)), root)
            restore(root, device)
        }.isFailure)
        assertTrue(device.commands.isEmpty())
        assertOwnerData(device)
    }

    @Test fun `corrupt CRC is rejected before device mutation`() = runTest {
        val payload = "com.example.one:replacement".toByteArray()
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            val entry = ZipEntry("apps/com.example.one/base.apk").apply {
                method = ZipEntry.STORED
                size = payload.size.toLong()
                compressedSize = size
                crc = java.util.zip.CRC32().apply { update(payload) }.value
            }
            zip.putNextEntry(entry); zip.write(payload); zip.closeEntry()
        }
        val bytes = output.toByteArray()
        val dataOffset = 30 + "apps/com.example.one/base.apk".length
        bytes[dataOffset] = (bytes[dataOffset].toInt() xor 1).toByte()
        val root = temporary.newFolder()
        val device = Device()
        assertTrue(runCatching {
            manager.extractArchive(ByteArrayInputStream(bytes), root)
            restore(root, device)
        }.exceptionOrNull() is java.util.zip.ZipException)
        assertTrue(device.commands.isEmpty())
        assertOwnerData(device)
    }

    @Test fun `duplicate archive path aliases cannot overwrite an extracted APK`() {
        val bytes = archive(
            "apps/com.example.one/base.apk" to "com.example.one:original",
            "apps/com.example.one/./base.apk" to "com.example.one:unexpected"
        )
        val root = temporary.newFolder()
        assertTrue(runCatching { manager.extractArchive(ByteArrayInputStream(bytes), root) }.isFailure)
        assertEquals("com.example.one:original", File(root, "apps/com.example.one/base.apk").readText())
    }

    @Test fun `inspection rejects missing malformed and unsupported manifest`() {
        listOf(
            archive("unrelated.json" to "{}"),
            archive("manifest.json" to "{"),
            archive("manifest.json" to """{"formatVersion":2,"apps":[]}"""),
            archive("manifest.json" to """{"formatVersion":1}""")
        ).forEach { bytes ->
            assertTrue(runCatching { manager.inspectBackup(ByteArrayInputStream(bytes)) }.isFailure)
        }
    }
}
