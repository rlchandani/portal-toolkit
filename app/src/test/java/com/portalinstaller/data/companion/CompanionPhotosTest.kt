/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.companion

import java.io.ByteArrayInputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompanionPhotosTest {
    private class Store : CompanionSessionStore {
        var saved = listOf(CompanionSession(CompanionDevice("a", "Kitchen", "http://a:8723"), "private"))
        override suspend fun load() = saved
        override suspend fun save(sessions: List<CompanionSession>) { saved = sessions }
    }
    @Test fun rejectsUnsafeBasenamesAndUnsupportedImageTypes() {
        for (name in listOf("../a.jpg", "a/b.png", "a\\b.webp", ".hidden.jpg", "a.txt", "a.jpg\u0000")) {
            assertTrue(name, runCatching { CompanionPhotos.safeName(name) }.isFailure)
        }
        assertEquals("My photo.JPG", CompanionPhotos.safeName("My photo.JPG"))
        assertTrue(runCatching { CompanionPhotos.folder("/sdcard/Pictures/../DCIM") }.isFailure)
    }

    @Test fun sharedStorageAliasesUsePortalWirePaths() = runTest {
        val calls = mutableListOf<CompanionRequest>()
        val client = CompanionClient(CompanionTransport {
            calls += it
            if (it.binary) CompanionResponse(200, "", byteArrayOf(1))
            else CompanionResponse(200, """{"ok":true,"files":[]}""")
        })
        val session = Store().saved.single()
        client.photos(session, "/storage/emulated/0/Pictures")
        client.photoBytes(session, "/storage/emulated/0/Pictures/Test.png", true)
        assertEquals("/sdcard/Pictures", calls[0].url.toHttpUrl().queryParameter("path"))
        assertEquals("/sdcard/Pictures/Test.png", calls[1].url.toHttpUrl().queryParameter("path"))
    }

    @Test fun photoRequestsKeepParentDirectoryWhitespaceDistinctFromTrimmedFolderInput() = runTest {
        val calls = mutableListOf<CompanionRequest>()
        val client = CompanionClient(CompanionTransport {
            calls += it
            if (it.binary) CompanionResponse(200, "", byteArrayOf(1))
            else CompanionResponse(200, """{"ok":true,"files":[]}""")
        })
        val session = Store().saved.single()
        for (root in listOf("/sdcard", "/storage/emulated/0")) {
            calls.clear()
            client.photos(session, "  $root/Album  ")
            client.photoBytes(session, "$root/Album /x.jpg")
            client.photoBytes(session, "$root/Album/x.jpg")
            client.deletePhoto(session, "$root/Album /x.jpg")
            client.deletePhoto(session, "$root/Album/x.jpg")
            assertEquals(listOf(
                "/sdcard/Album",
                "/sdcard/Album /x.jpg",
                "/sdcard/Album/x.jpg",
                "/sdcard/Album /x.jpg",
                "/sdcard/Album/x.jpg",
            ), calls.map { it.url.toHttpUrl().queryParameter("path") })
        }
    }

    @Test fun photoPathsPreserveCharactersBeyondTheStorageAlias() {
        assertEquals("/sdcard/Album //Family / x & 1.jpg", CompanionPhotos.photoPath("/sdcard/Album //Family / x & 1.jpg"))
        assertEquals("/sdcard/Album //Family / x & 1.jpg", CompanionPhotos.photoPath("/storage/emulated/0/Album //Family / x & 1.jpg"))
        for (path in listOf(
            " /sdcard/Album/x.jpg",
            "/storage/emulated/01/Album/x.jpg",
            "/sdcard/Album/../x.jpg",
            "/storage/emulated/0/Album/./x.jpg",
            "/sdcard/Album\\Other/x.jpg",
            "/sdcard/Album\u0000/x.jpg",
        )) {
            assertTrue(path, runCatching { CompanionPhotos.photoPath(path) }.isFailure)
        }
    }

    @Test fun sharedStorageAliasCannotEscapeOrMatchAnotherUser() {
        assertEquals("/sdcard", CompanionPhotos.folder("/storage/emulated/0/"))
        for (path in listOf("/storage/emulated/01/Pictures", "/storage/emulated/0/../1", "/storage/emulated/0/Pictures/./Hidden")) {
            assertTrue(path, runCatching { CompanionPhotos.folder(path) }.isFailure)
        }
    }

    @Test fun uploadStreamsEachItemKeepsFailuresAndRefreshesListWithoutActivating() = runTest {
        val calls = mutableListOf<CompanionRequest>()
        val store = Store()
        val http = CompanionTransport { request ->
            calls += request
            when {
                request.upload != null -> {
                    assertEquals("private", request.sessionToken)
                    assertEquals("POST", request.method)
                    assertNull(request.body)
                    val bytes = request.upload!!.open().use { it.readBytes() }
                    request.onUploadProgress?.invoke(bytes.size.toLong())
                    if (request.url.contains("bad.png")) CompanionResponse(409, """{"ok":false,"error":"exists"}""")
                    else CompanionResponse(200, """{"ok":true,"path":"/sdcard/Pictures/Immortal/good.jpg","bytes":4}""")
                }
                request.url.contains("/photos?") -> CompanionResponse(200, """{"ok":true,"path":"/sdcard/Pictures/Immortal","files":[{"name":"good.jpg","path":"/sdcard/Pictures/Immortal/good.jpg","size":4,"modified":7}],"freeBytes":100}""")
                else -> CompanionResponse(200, """{"ok":true,"deviceId":"a","capabilities":{},"settings":{"domains":[]},"apps":[],"sources":{},"np":{}}""")
            }
        }
        val repo = CompanionRepository(CompanionClient(http), store, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repo.ready.join(); repo.selectDevice("a").join()
        repo.uploadPhotos(listOf(
            PhotoUploadSource("good.jpg") { CompanionUpload("good.jpg", "image/jpeg", 4) { ByteArrayInputStream(byteArrayOf(1,2,3,4)) } },
            PhotoUploadSource("bad.png") { CompanionUpload("bad.png", "image/png", 4) { ByteArrayInputStream(byteArrayOf(1,2,3,4)) } },
        )).join()
        assertEquals(2, repo.state.value.photoTransfer!!.total)
        assertEquals(1, repo.state.value.photoTransfer!!.completed)
        assertEquals("bad.png", repo.state.value.photoTransfer!!.failures.single().name)
        assertFalse(repo.state.value.photoTransfer!!.running)
        assertEquals("good.jpg", repo.state.value.photos.single().name)
        assertFalse(calls.any { it.method == "POST" && it.url.endsWith("/sources") })
    }

    @Test fun photoFolderPersistsByDeviceAndDeleteUsesEncodedQuery() = runTest {
        val store = Store()
        val calls = mutableListOf<CompanionRequest>()
        val http = CompanionTransport { request ->
            calls += request
            if (request.url.contains("/photos?")) CompanionResponse(200, """{"ok":true,"path":"/sdcard/Pictures/Family","files":[]}""")
            else CompanionResponse(200, """{"ok":true,"deviceId":"a","capabilities":{},"settings":{"domains":[]},"apps":[],"sources":{},"np":{}}""")
        }
        val repo = CompanionRepository(CompanionClient(http), store, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repo.ready.join(); repo.selectDevice("a").join()
        repo.listPhotos("/sdcard/Pictures/Family").join()
        assertEquals("/sdcard/Pictures/Family", store.saved.single().device.photoFolder)
        repo.deletePhoto("/sdcard/Pictures/Family/photo & 1.jpg").join()
        val deletion = calls.single { it.method == "DELETE" }
        assertTrue(deletion.url.contains("photo%20%26%201.jpg"))
        assertFalse(deletion.url.contains("private"))
    }

    @Test fun switchingDeviceDuringUploadPreventsFollowingItemsAndLateProgress() = runTest {
        val store = Store()
        store.saved += CompanionSession(CompanionDevice("b", "Other", "http://b:8723"), "other")
        val pending = CompletableDeferred<CompanionResponse>()
        val calls = mutableListOf<CompanionRequest>()
        val http = CompanionTransport { request ->
            calls += request
            if (request.upload != null) pending.await()
            else CompanionResponse(200, """{"ok":true,"deviceId":"${if (request.url.contains("//b:")) "b" else "a"}","capabilities":{},"settings":{"domains":[]},"apps":[],"sources":{},"np":{}}""")
        }
        val repo = CompanionRepository(CompanionClient(http), store, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repo.ready.join(); repo.selectDevice("a").join()
        val input = PhotoUploadSource("a.jpg") { CompanionUpload("a.jpg", "image/jpeg", 1) { ByteArrayInputStream(byteArrayOf(1)) } }
        val upload = repo.uploadPhotos(listOf(input, input))
        runCurrent()
        repo.selectDevice("b").join()
        calls.single { it.upload != null }.onUploadProgress!!.invoke(1)
        pending.complete(CompanionResponse(200, """{"ok":true,"path":"/sdcard/Pictures/Immortal/a.jpg","bytes":1}"""))
        upload.join()
        assertEquals(1, calls.count { it.upload != null })
        assertEquals("b", repo.state.value.activeDeviceId)
        assertNull(repo.state.value.photoTransfer)
    }

    @Test fun suspendPhotoBytesDiscardResponseAfterDeviceSwitch() = runTest {
        val store = Store()
        store.saved += CompanionSession(CompanionDevice("b", "Other", "http://b:8723"), "other")
        val pending = CompletableDeferred<CompanionResponse>()
        val http = CompanionTransport { request ->
            if (request.binary) pending.await()
            else CompanionResponse(200, """{"ok":true,"deviceId":"${if (request.url.contains("//b:")) "b" else "a"}","capabilities":{},"settings":{"domains":[]},"apps":[],"sources":{},"np":{}}""")
        }
        val repo = CompanionRepository(CompanionClient(http), store, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repo.ready.join(); repo.selectDevice("a").join()
        var bytes: ByteArray? = byteArrayOf(9)
        val load = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            bytes = repo.photoBytes("/sdcard/Pictures/Immortal/a.jpg")
        }
        repo.selectDevice("b").join()
        pending.complete(CompanionResponse(200, "", byteArrayOf(1)))
        load.join()
        assertNull(bytes)
    }

    @Test fun newCollectionDoesNotFailBeforeItsFirstUpload() = runTest {
        val http = CompanionTransport { request ->
            if (request.url.contains("/photos?")) CompanionResponse(404, """{"ok":false,"error":"folder_not_found"}""")
            else CompanionResponse(200, """{"ok":true,"deviceId":"a","capabilities":{},"settings":{"domains":[]},"apps":[],"sources":{},"np":{}}""")
        }
        val repo = CompanionRepository(CompanionClient(http), Store(), backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repo.ready.join(); repo.selectDevice("a").join()
        repo.listPhotos("/sdcard/Pictures/New-Collection").join()
        assertEquals(CompanionConnection.CONNECTED, repo.state.value.connection)
        assertNull(repo.state.value.error)
        assertTrue(repo.state.value.photos.isEmpty())
        assertEquals("/sdcard/Pictures/New-Collection", repo.state.value.photoFolder)
    }

    @Test fun photoPermissionFailureDoesNotInvalidatePairing() = runTest {
        val http = CompanionTransport { request ->
            if (request.url.contains("/photos?")) CompanionResponse(403, """{"ok":false,"error":"not_readable"}""")
            else CompanionResponse(200, """{"ok":true,"deviceId":"a","capabilities":{},"settings":{"domains":[]},"apps":[],"sources":{},"np":{}}""")
        }
        val repo = CompanionRepository(CompanionClient(http), Store(), backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repo.ready.join(); repo.selectDevice("a").join()
        repo.listPhotos("/sdcard/Pictures/Family").join()
        assertEquals(CompanionConnection.CONNECTED, repo.state.value.connection)
        assertEquals("not_readable", repo.state.value.error?.code)
    }
}
