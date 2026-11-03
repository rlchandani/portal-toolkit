/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import dadb.AdbShellResponse
import dadb.Dadb
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupDeclaredFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    private val packageName = "com.example.generated"
    private val base = "0_base.apk"
    private val split = "1_split.apk"

    private fun fixture(
        declared: List<String>,
        archived: Map<String, ByteArray>,
        expectedInstalled: Int,
        expectedFailures: List<String>,
        expectedInstallFiles: List<String> = emptyList(),
        metadataMatches: Boolean = true,
        followingCompleteApp: Boolean = false
    ) = runBlocking {
        val cache = temporary.newFolder("cache")
        val sentinel = File(cache, "unrelated").apply { writeText("retain") }
        val followingPackage = "com.example.following"
        val apps = JSONArray().put(
            JSONObject()
                .put("packageName", packageName)
                .put("files", JSONArray(declared))
        )
        if (followingCompleteApp) {
            apps.put(
                JSONObject()
                    .put("packageName", followingPackage)
                    .put("files", JSONArray(listOf(base)))
            )
        }
        val manifest = JSONObject()
            .put("formatVersion", PortalBackupManager.FORMAT_VERSION)
            .put("settings", JSONObject())
            .put("apps", apps)
        val archiveBytes = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                archived.forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry("apps/$packageName/$name"))
                    zip.write(content)
                    zip.closeEntry()
                }
                if (followingCompleteApp) {
                    zip.putNextEntry(ZipEntry("apps/$followingPackage/$base"))
                    zip.write(byteArrayOf(9))
                    zip.closeEntry()
                }
            }
        }.toByteArray()
        val input = object : ByteArrayInputStream(archiveBytes) {
            var closed = false
            override fun close() {
                closed = true
                super.close()
            }
        }
        val context = Mockito.mock(Context::class.java)
        val resolver = Mockito.mock(ContentResolver::class.java)
        val source = Mockito.mock(Uri::class.java)
        val packages = Mockito.mock(PackageManager::class.java)
        val info = Mockito.mock(PackageInfo::class.java).apply {
            this.packageName = if (metadataMatches) {
                this@BackupDeclaredFilesTest.packageName
            } else {
                "com.example.other"
            }
        }
        val dadb = Mockito.mock(Dadb::class.java)
        val followingInfo = Mockito.mock(PackageInfo::class.java).apply {
            this.packageName = followingPackage
        }
        Mockito.`when`(context.cacheDir).thenReturn(cache)
        Mockito.`when`(context.contentResolver).thenReturn(resolver)
        Mockito.`when`(context.packageManager).thenReturn(packages)
        Mockito.`when`(resolver.openInputStream(source)).thenReturn(input)
        // The mockable Android jar reports the legacy SDK branch in this host gate.
        check(Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            "Fixture requires the legacy host metadata branch"
        }
        Mockito.`when`(
            packages.getPackageArchiveInfo(ArgumentMatchers.anyString(), ArgumentMatchers.eq(0))
        ).thenAnswer { call ->
            if ((call.arguments[0] as String).contains("/apps/$followingPackage/")) followingInfo else info
        }
        Mockito.`when`(dadb.shell(ArgumentMatchers.anyString()))
            .thenReturn(AdbShellResponse("Success\n", "", 0))

        val installedNames = mutableListOf<String>()
        val installedBytes = mutableListOf<List<Byte>>()
        val installedPackages = mutableListOf<String>()
        Mockito.doAnswer { call ->
            val file = call.arguments[0] as File
            installedNames.add(file.name)
            installedBytes.add(file.readBytes().toList())
            installedPackages.add(file.parentFile!!.name)
            null
        }.`when`(dadb).push(
            ArgumentMatchers.any(File::class.java) ?: File(cache, "unused-matcher"),
            ArgumentMatchers.anyString(),
            ArgumentMatchers.anyInt(),
            ArgumentMatchers.anyLong()
        )
        Mockito.doAnswer { call ->
            @Suppress("UNCHECKED_CAST")
            val files = call.arguments[0] as List<File>
            files.forEach { file ->
                installedNames.add(file.name)
                installedBytes.add(file.readBytes().toList())
                installedPackages.add(file.parentFile!!.name)
            }
            null
        }.`when`(dadb).installMultiple(
            ArgumentMatchers.anyList(),
            ArgumentMatchers.eq("-r") ?: "-r"
        )
        val progress = mutableListOf<Triple<Int, Int, String>>()
        try {
            val result = PortalBackupManager().restoreBackup(context, dadb, source) { current, total, name ->
                progress.add(Triple(current, total, name))
            }
            assertTrue(
                "summary=$result; installFiles=$installedNames",
                result.installedCount == expectedInstalled &&
                    result.failedPackages == expectedFailures &&
                    installedNames == expectedInstallFiles
            )
            assertEquals(0, result.restoredSettingCount)
            assertEquals(null, result.stoppedEarlyReason)
            val expectedProgress = if (followingCompleteApp) {
                listOf(Triple(1, 2, packageName), Triple(2, 2, followingPackage), Triple(2, 2, ""))
            } else {
                listOf(Triple(1, 1, packageName), Triple(1, 1, ""))
            }
            assertEquals(expectedProgress, progress)
            val expectedBytes = if (followingCompleteApp) {
                listOf(listOf<Byte>(9))
            } else {
                expectedInstallFiles.map { archived.getValue(it).toList() }
            }
            assertEquals(expectedBytes, installedBytes)
            assertEquals(
                expectedInstallFiles.map { if (followingCompleteApp) followingPackage else packageName },
                installedPackages
            )
            if (expectedInstallFiles.isEmpty()) {
                Mockito.verifyNoInteractions(dadb)
            } else if (expectedInstallFiles.size == 1) {
                val commands = Mockito.mockingDetails(dadb).invocations
                    .filter { it.method.name == "shell" }
                    .map { it.arguments[0] as String }
                assertEquals(2, commands.size)
                assertTrue(commands[0].startsWith("pm install -r "))
                assertTrue(commands[1].startsWith("rm -f "))
            } else {
                Mockito.verify(dadb).installMultiple(
                    ArgumentMatchers.anyList(),
                    ArgumentMatchers.eq("-r") ?: "-r"
                )
            }
        } finally {
            assertTrue("Archive input was not closed", input.closed)
            assertEquals(listOf("unrelated"), cache.listFiles().orEmpty().map { it.name }.sorted())
            assertEquals("retain", sentinel.readText())
        }
    }

    @Test fun `missing declared split fails the app without installing the base`() {
        fixture(listOf(base, split), mapOf(base to byteArrayOf(1, 2)), 0, listOf(packageName))
    }

    @Test fun `empty declared split fails the app without installing the base`() {
        fixture(
            listOf(base, split),
            mapOf(base to byteArrayOf(1, 2), split to byteArrayOf()),
            0,
            listOf(packageName)
        )
    }

    @Test fun `missing declared base fails the app without installing the split`() {
        fixture(listOf(base, split), mapOf(split to byteArrayOf(3, 4)), 0, listOf(packageName))
    }

    @Test fun `empty declared base fails the app without installing the split`() {
        fixture(
            listOf(base, split),
            mapOf(base to byteArrayOf(), split to byteArrayOf(3, 4)),
            0,
            listOf(packageName)
        )
    }

    @Test fun `complete split restore retains declared order and actual bytes`() {
        fixture(
            listOf(base, split),
            mapOf(base to byteArrayOf(1, 2), split to byteArrayOf(3, 4)),
            1,
            emptyList(),
            listOf(base, split)
        )
    }

    @Test fun `complete single APK retains push install and remote cleanup`() {
        fixture(listOf(base), mapOf(base to byteArrayOf(1, 2)), 1, emptyList(), listOf(base))
    }

    @Test fun `all declared files absent retains a failed package and owned cleanup`() {
        fixture(listOf(base, split), emptyMap(), 0, listOf(packageName))
    }

    @Test fun `complete archive with mismatched package retains failure before transport`() {
        fixture(
            listOf(base, split),
            mapOf(base to byteArrayOf(1, 2), split to byteArrayOf(3, 4)),
            0,
            listOf(packageName),
            metadataMatches = false
        )
    }

    @Test fun `incomplete app fails independently while the following complete app installs`() {
        fixture(
            listOf(base, split),
            mapOf(base to byteArrayOf(1, 2)),
            1,
            listOf(packageName),
            listOf(base),
            followingCompleteApp = true
        )
    }
}
