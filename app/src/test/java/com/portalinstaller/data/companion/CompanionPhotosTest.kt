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
}
