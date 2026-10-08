/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.viewmodel

import com.portalinstaller.data.files.PortalFileManager
import com.portalinstaller.data.photos.HttpResult
import com.portalinstaller.data.photos.PhotosPickerClient
import com.portalinstaller.data.photos.PickerException
import com.portalinstaller.data.photos.PickerHttp
import dadb.AdbShellResponse
import dadb.Dadb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.security.MessageDigest

@OptIn(ExperimentalCoroutinesApi::class)
class ScreensaverPickerImportTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private class Http(var names: List<String> = listOf("one.jpg")) : PickerHttp {
        var ready = true
        var sessionCode = 200
        var pageCode = 200
        var pollInterval = "1s"
        var timeout = "300s"
        var deleteFails = false
        val downloads = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        val requests = mutableListOf<String>()
        var download: (String, File) -> Unit = { name, dest -> dest.writeText("bytes:$name") }

        override fun get(url: String, token: String): HttpResult {
            assertEquals("synthetic-token", token)
            requests += url
            return if (url.toHttpUrl().pathSegments.last() == "mediaItems") {
                HttpResult(pageCode, JSONObject().put("mediaItems", JSONArray(names.mapIndexed { index, name ->
                    JSONObject().put("id", "p$index").put("type", "PHOTO").put("mediaFile",
                        JSONObject().put("filename", name).put("mimeType", "image/jpeg").put("baseUrl", "https://photos.invalid/p$index"))
                })).toString())
            } else {
                HttpResult(sessionCode, """{"id":"session","mediaItemsSet":$ready,"pollingConfig":{"pollInterval":"$pollInterval","timeoutIn":"$timeout"}}""")
            }
        }
        override fun post(url: String, token: String, jsonBody: String): HttpResult = error("No OAuth or session creation in these tests")
        override fun delete(url: String, token: String): HttpResult {
            deleted += url.toHttpUrl().pathSegments.last()
            if (deleteFails) throw IOException("cleanup offline")
            return HttpResult(204, "")
        }
        override fun downloadTo(url: String, token: String, dest: File) {
            val index = url.substringAfterLast("/p").substringBefore("=d").toInt()
            val name = names[index]
            downloads += name
            download(name, dest)
        }
    }

    private suspend fun TestScope.import(
        http: Http,
        existing: Set<String> = emptySet(),
        cache: File = temporaryFolder.root,
        waitMillis: Long = 90_000,
        upload: suspend (File) -> Unit = {},
    ): UploadSummaryState.Done = importPickerPhotos(
        client = PhotosPickerClient(http, StandardTestDispatcher(testScheduler)),
        token = "synthetic-token",
        sessionId = "session",
        cacheDir = cache,
        existingNames = existing,
        waitMillis = waitMillis,
        upload = upload,
    )

    /** Fake ADB transport; the actual PortalFileManager verifies and publishes uploads. */
    private class Device {
        val files = mutableMapOf<String, ByteArray>()
        var failName: String? = null
        var createBeforePublish: String? = null
        var retainStageWithoutDestination = false
        val dadb = Proxy.newProxyInstance(Dadb::class.java.classLoader, arrayOf(Dadb::class.java)) { _, method, args ->
            when (method.name) {
                "shell" -> {
                    val command = args!![0] as String
                    val paths = Regex("'([^']*)'").findAll(command).map { it.groupValues[1] }.toList()
                    val path = paths.firstOrNull().orEmpty()
                    when {
                        command.startsWith("readlink") -> AdbShellResponse(path.replace("/sdcard", "/storage/emulated/0"), "", 0)
                        command.startsWith("stat") -> AdbShellResponse(files[path]?.size?.toString().orEmpty(), "", 0)
                        command.startsWith("sha256sum") -> AdbShellResponse(files[path]?.let {
                            MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { b -> "%02x".format(b) }
                        }.orEmpty(), "", 0)
                        command.contains("mv ") -> {
                            val operands = command.substringAfter("mv -n ", command.substringAfter("mv -f "))
                            val (source, dest) = Regex("'([^']*)'").findAll(operands).map { it.groupValues[1] }.take(2).toList()
                            if (dest == createBeforePublish) files[dest] = "concurrent original".toByteArray()
                            // Parent verified this zero-exit/retained-stage behavior on Portal API 29.
                            if (!(command.contains("mv -n ") && dest in files) && !retainStageWithoutDestination) {
                                files[dest] = files.remove(source) ?: error("No staged upload")
                            }
                            AdbShellResponse("", "", 0)
                        }
                        command.startsWith("if test -e ") -> {
                            val (source, dest) = paths.distinct().take(2)
                            val status = when {
                                source in files && dest in files -> 73
                                source in files || dest !in files -> 1
                                else -> 0
                            }
                            AdbShellResponse("", "", status)
                        }
                        command.startsWith("rm ") -> { files.remove(path); AdbShellResponse("", "", 0) }
                        else -> error("Unexpected shell command")
                    }
                }
                "push" -> {
                    val buffer = okio.Buffer().apply { writeAll(args!![0] as okio.Source) }
                    val path = args!![1] as String
                    files[path] = buffer.readByteArray()
                    if (failName != null && path.substringAfterLast('/').startsWith(".$failName.")) {
                        throw IOException("link lost during push")
                    }
                    null
                }
                "toString" -> "SyntheticPortal"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> args?.get(0) === this
                else -> error("Unexpected ADB operation: ${method.name}")
            }
        } as Dadb
        suspend fun upload(file: File) { PortalFileManager().push(dadb, file, "/sdcard", false) }
        suspend fun uploadNoReplace(file: File) {
            PortalFileManager().push(dadb, file, "/sdcard", false, replaceExisting = false)
        }
    }

    @Test fun `destination created after listing is skipped and its original bytes survive`() = runTest {
        val http = Http(listOf("one.jpg", "one.jpg", "last.jpg"))
        val device = Device().apply { createBeforePublish = "/sdcard/one.jpg" }

        assertEquals(UploadSummaryState.Done(1, 2, 0), import(http, upload = device::uploadNoReplace))

        assertEquals("concurrent original", device.files["/sdcard/one.jpg"]?.decodeToString())
        assertEquals("bytes:last.jpg", device.files["/sdcard/last.jpg"]?.decodeToString())
        assertEquals(setOf("/sdcard/one.jpg", "/sdcard/last.jpg"), device.files.keys)
        assertEquals(listOf("one.jpg", "last.jpg"), http.downloads)
        assertEquals(listOf("session"), http.deleted)
        assertTrue(temporaryFolder.root.listFiles()!!.isEmpty())
    }

    @Test fun `unconfirmed publication is failed rather than uploaded or skipped`() = runTest {
        val http = Http()
        val device = Device().apply { retainStageWithoutDestination = true }

        assertEquals(UploadSummaryState.Done(0, 0, 1), import(http, upload = device::uploadNoReplace))

        assertTrue(device.files.isEmpty())
        assertEquals(listOf("session"), http.deleted)
        assertTrue(temporaryFolder.root.listFiles()!!.isEmpty())
    }

    @Test fun `partial upload retains successful files and preserves failed destination`() = runTest {
        val http = Http(listOf("good.jpg", "bad.jpg", "last.jpg"))
        val device = Device().apply {
            files["/sdcard/bad.jpg"] = "original".toByteArray()
            failName = "bad.jpg"
        }

        assertEquals(UploadSummaryState.Done(2, 0, 1), import(http, upload = device::upload))

        assertEquals("bytes:good.jpg", device.files["/sdcard/good.jpg"]?.decodeToString())
        assertEquals("original", device.files["/sdcard/bad.jpg"]?.decodeToString())
        assertEquals("bytes:last.jpg", device.files["/sdcard/last.jpg"]?.decodeToString())
        assertEquals(setOf("/sdcard/good.jpg", "/sdcard/bad.jpg", "/sdcard/last.jpg"), device.files.keys)
        assertEquals(listOf("session"), http.deleted)
        assertTrue(temporaryFolder.root.listFiles()!!.isEmpty())
    }

    @Test fun `existing and newly imported filename collisions preserve the first photo`() = runTest {
        val http = Http(listOf("existing.jpg", "same.jpg", "same.jpg"))
        http.download = { _, dest -> dest.writeText("selection:${http.downloads.size}") }
        val device = Device().apply { files["/sdcard/existing.jpg"] = "original".toByteArray() }

        assertEquals(UploadSummaryState.Done(1, 2, 0), import(http, setOf("existing.jpg"), upload = device::upload))

        assertEquals("original", device.files["/sdcard/existing.jpg"]?.decodeToString())
        assertEquals("selection:1", device.files["/sdcard/same.jpg"]?.decodeToString())
        assertEquals(listOf("same.jpg"), http.downloads)
    }

    @Test fun `later same-name selection can recover after first download fails`() = runTest {
        val http = Http(listOf("same.jpg", "same.jpg"))
        http.download = { _, dest ->
            if (http.downloads.size == 1) throw IOException("first download failed")
            dest.writeText("second")
        }
        val device = Device()

        assertEquals(UploadSummaryState.Done(1, 0, 1), import(http, upload = device::upload))
        assertEquals("second", device.files["/sdcard/same.jpg"]?.decodeToString())
    }

    @Test fun `failed download removes partial cache and continues to the next photo`() = runTest {
        val http = Http(listOf("bad.jpg", "good.jpg"))
        http.download = { name, dest ->
            dest.writeText("partial")
            if (name == "bad.jpg") throw IOException("download interrupted")
        }
        val uploaded = mutableListOf<String>()

        assertEquals(UploadSummaryState.Done(1, 0, 1), import(http, upload = { uploaded += it.name }))
        assertEquals(listOf("good.jpg"), uploaded)
        assertTrue("Failed download left private cache data", temporaryFolder.root.listFiles()!!.isEmpty())
    }

    @Test fun `private import staging preserves unrelated cache files with the same name`() = runTest {
        val cached = temporaryFolder.newFile("one.jpg").apply { writeText("unrelated cache") }
        val http = Http()
        var bytes: String? = null

        assertEquals(UploadSummaryState.Done(1, 0, 0), import(http, upload = { bytes = it.readText() }))

        assertEquals("bytes:one.jpg", bytes)
        assertEquals("unrelated cache", cached.readText())
        assertEquals(listOf(cached), temporaryFolder.root.listFiles()!!.toList())
    }

    @Test fun `invalid filename cannot write outside private staging and valid photos continue`() = runTest {
        val cache = temporaryFolder.newFolder("cache")
        val preserved = temporaryFolder.newFile("owner.jpg").apply { writeText("owner") }
        val http = Http(listOf("../owner.jpg", "good.jpg"))
        val uploaded = mutableListOf<String>()

        assertEquals(UploadSummaryState.Done(1, 0, 1), import(http, cache = cache, upload = { uploaded += it.name }))

        assertEquals("owner", preserved.readText())
        assertEquals(listOf("good.jpg"), http.downloads)
        assertEquals(listOf("good.jpg"), uploaded)
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test fun `expired session fails before downloading and cleans up`() = runTest {
        for (code in listOf(401, 404, 410)) {
            val http = Http().apply { sessionCode = code }

            val error = runCatching { import(http) }.exceptionOrNull()

            assertTrue(error is PickerException)
            assertEquals(code, (error as PickerException).code)
            assertTrue(http.downloads.isEmpty())
            assertEquals(listOf("session"), http.deleted)
        }
    }

    @Test fun `expired download retains earlier upload and stops requesting unusable URLs`() = runTest {
        val http = Http(listOf("first.jpg", "expired.jpg", "remaining.jpg"))
        http.download = { name, dest ->
            if (name != "first.jpg") throw PickerException(401, "expired")
            dest.writeText("first")
        }
        val uploaded = mutableListOf<String>()

        assertEquals(UploadSummaryState.Done(1, 0, 2), import(http, upload = { uploaded += it.name }))

        assertEquals(listOf("first.jpg"), uploaded)
        assertEquals(listOf("first.jpg", "expired.jpg"), http.downloads)
        assertEquals(listOf("session"), http.deleted)
    }

    @Test fun `cancellation during upload propagates and no later photo is downloaded`() = runTest {
        val http = Http(listOf("one.jpg", "two.jpg"))

        val error = runCatching { import(http, upload = { throw CancellationException("cancelled") }) }.exceptionOrNull()

        assertTrue(error is CancellationException)
        assertEquals(listOf("one.jpg"), http.downloads)
        assertEquals(listOf("session"), http.deleted)
        assertTrue(temporaryFolder.root.listFiles()!!.isEmpty())
    }

    @Test fun `cancelled job cleans up session and staged photo before recovery`() = runTest {
        val http = Http(listOf("one.jpg", "two.jpg"))
        val started = CompletableDeferred<Unit>()
        val job = launch {
            import(http, upload = { started.complete(Unit); awaitCancellation() })
        }
        started.await()
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(listOf("one.jpg"), http.downloads)
        assertEquals(listOf("session"), http.deleted)
        assertTrue(temporaryFolder.root.listFiles()!!.isEmpty())
        val next = Http()
        assertEquals(UploadSummaryState.Done(1, 0, 0), import(next))
        assertEquals(listOf("session"), next.deleted)
    }

    @Test fun `picker timeout does not download and cleans up session`() = runTest {
        val http = Http().apply { ready = false }

        assertEquals(UploadSummaryState.Done(0, 0, 0), import(http, waitMillis = 2_500))

        assertTrue(http.downloads.isEmpty())
        assertEquals(listOf("session"), http.deleted)
        assertEquals(2_500L, testScheduler.currentTime)
    }

    @Test fun `session timeout bounds polling even when return wait is longer`() = runTest {
        val http = Http().apply { ready = false; timeout = "2s"; pollInterval = "0.5s" }

        assertEquals(UploadSummaryState.Done(0, 0, 0), import(http))

        assertEquals(2_000L, testScheduler.currentTime)
        assertEquals(5, http.requests.size)
        assertEquals(listOf("session"), http.deleted)
    }

    @Test fun `cleanup failure does not erase a completed upload`() = runTest {
        val http = Http().apply { deleteFails = true }
        val uploaded = mutableListOf<String>()

        assertEquals(UploadSummaryState.Done(1, 0, 0), import(http, upload = { uploaded += it.name }))
        assertEquals(listOf("one.jpg"), uploaded)
        assertEquals(listOf("session"), http.deleted)
    }

    @Test fun `retry after listing failure performs a fresh import`() = runTest {
        val http = Http().apply { pageCode = 503 }

        assertTrue(runCatching { import(http) }.exceptionOrNull() is PickerException)
        assertTrue(http.downloads.isEmpty())
        http.pageCode = 200
        assertEquals(UploadSummaryState.Done(1, 0, 0), import(http))
        assertEquals(listOf("one.jpg"), http.downloads)
        assertEquals(listOf("session", "session"), http.deleted)
    }

    @Test fun `cancellation during download removes partial cache and prevents upload`() = runTest {
        val http = Http(listOf("one.jpg", "two.jpg"))
        http.download = { _, dest -> dest.writeText("partial"); throw CancellationException("cancelled download") }
        val uploaded = mutableListOf<String>()

        assertTrue(runCatching { import(http, upload = { uploaded += it.name }) }.exceptionOrNull() is CancellationException)

        assertTrue(uploaded.isEmpty())
        assertEquals(listOf("one.jpg"), http.downloads)
        assertEquals(listOf("session"), http.deleted)
        assertTrue(temporaryFolder.root.listFiles()!!.isEmpty())
    }

    @Test fun `cancellation while polling deletes session without listing photos`() = runTest {
        val http = Http().apply { ready = false }
        val job = launch { import(http) }
        testScheduler.runCurrent()
        assertEquals(1, http.requests.size)
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(1, http.requests.size)
        assertTrue(http.downloads.isEmpty())
        assertEquals(listOf("session"), http.deleted)
    }

    @Test fun `empty downloaded file fails individually and valid photos still upload`() = runTest {
        val http = Http(listOf("empty.jpg", "good.jpg"))
        http.download = { name, dest -> dest.writeText(if (name == "empty.jpg") "" else "image") }
        val uploaded = mutableListOf<String>()

        assertEquals(UploadSummaryState.Done(1, 0, 1), import(http, upload = { uploaded += it.name }))
        assertEquals(listOf("good.jpg"), uploaded)
        assertTrue(temporaryFolder.root.listFiles()!!.isEmpty())
    }

    @Test fun `empty selection completes without staging or uploading and deletes session`() = runTest {
        val http = Http(emptyList())

        assertEquals(UploadSummaryState.Done(0, 0, 0), import(http, upload = { error("Unexpected upload") }))

        assertTrue(http.downloads.isEmpty())
        assertEquals(listOf("session"), http.deleted)
        assertTrue(temporaryFolder.root.listFiles()!!.isEmpty())
    }
}
