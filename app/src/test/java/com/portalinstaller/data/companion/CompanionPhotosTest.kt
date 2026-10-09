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

    @Test fun defaultBrowseFindsRootAndLegacyPhotosWithoutChangingTheUploadDestination() = runTest {
        val calls = mutableListOf<CompanionRequest>()
        val http = CompanionTransport { request ->
            calls += request
            when {
                request.url.contains("/photos?") -> if (request.url.toHttpUrl().queryParameter("recursive") == "1")
                    CompanionResponse(200, """{"ok":true,"recursive":true,"path":"/sdcard","files":[
                        {"name":"home.jpg","path":"/sdcard/home.jpg","size":4,"modified":1},
                        {"name":"family.jpg","path":"/sdcard/Pictures/PortalInstaller/family.jpg","size":8,"modified":2}
                    ],"folders":[{"path":"/sdcard","count":1},{"path":"/sdcard/Pictures/PortalInstaller","count":1}]}""")
                    else CompanionResponse(200, """{"ok":true,"files":[]}""")
                else -> CompanionResponse(200, """{"ok":true,"deviceId":"a","capabilities":{"photos":true,"photoLibrary":true},"settings":{"domains":[]},"sources":{},"np":{}}""")
            }
        }
        val store = Store()
        val repo = CompanionRepository(CompanionClient(http), store, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repo.ready.join(); repo.selectDevice("a").join()
        repo.listPhotos().join()

        assertEquals(listOf("/sdcard/home.jpg", "/sdcard/Pictures/PortalInstaller/family.jpg"),
            repo.state.value.photos.map { it.path })
        assertEquals("/sdcard/Pictures/Immortal", repo.state.value.photoFolder)
        assertEquals("/sdcard/Pictures/Immortal", store.saved.single().device.photoFolder)
        assertFalse(calls.any { it.method == "POST" && it.url.endsWith("/sources") })
    }

    @Test fun legacyPortalKeepsFolderBrowsingRatherThanClaimingAnIncompleteAllPhotosView() = runTest {
        val calls = mutableListOf<CompanionRequest>()
        val http = CompanionTransport { request ->
            calls += request
            if (request.url.contains("/photos?")) CompanionResponse(200, """{"ok":true,"files":[]}""")
            else CompanionResponse(200, """{"ok":true,"deviceId":"a","capabilities":{"photos":true},"settings":{"domains":[]},"sources":{},"np":{}}""")
        }
        val repo = CompanionRepository(CompanionClient(http), Store(), backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repo.ready.join(); repo.selectDevice("a").join(); repo.listPhotos().join()
        val listing = calls.single { it.url.contains("/photos?") }.url.toHttpUrl()
        assertEquals("/sdcard/Pictures/Immortal", listing.queryParameter("path"))
        assertNull(listing.queryParameter("recursive"))
    }

    @Test fun discoveryFailurePreservesPreviouslyShownCollection() = runTest {
        var fail = false
        val http = CompanionTransport { request ->
            if (request.url.contains("/photos?")) {
                if (fail) CompanionResponse(503, """{"ok":false,"error":"busy"}""")
                else CompanionResponse(200, """{"ok":true,"recursive":true,"files":[{"name":"family.jpg","path":"/sdcard/Pictures/PortalInstaller/family.jpg","size":8,"modified":2}]}""")
            } else CompanionResponse(200, """{"ok":true,"deviceId":"a","capabilities":{"photos":true,"photoLibrary":true},"settings":{"domains":[]},"sources":{},"np":{}}""")
        }
        val repo = CompanionRepository(CompanionClient(http), Store(), backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repo.ready.join(); repo.selectDevice("a").join(); repo.listPhotos().join()
        fail = true
        repo.listPhotos().join()
        assertEquals("/sdcard/Pictures/PortalInstaller/family.jpg", repo.state.value.photos.single().path)
        assertNotNull(repo.state.value.error)
        repo.clearError()
        assertNotNull(repo.state.value.photoListingError)
        fail = false
        repo.listPhotos().join()
        assertNull(repo.state.value.photoListingError)
    }

    @Test fun chosenFolderAndAllPhotosViewsSurviveReconnectIndependentlyOfUploadFolder() = runTest {
        val store = Store()
        val calls = mutableListOf<CompanionRequest>()
        val http = CompanionTransport { request ->
            calls += request
            if (request.url.contains("/photos?")) CompanionResponse(200, """{"ok":true,"recursive":true,"files":[]}""")
            else CompanionResponse(200, """{"ok":true,"deviceId":"a","capabilities":{"photos":true,"photoLibrary":true},"settings":{"domains":[]},"sources":{},"np":{}}""")
        }
        val repo = CompanionRepository(CompanionClient(http), store, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repo.ready.join(); repo.selectDevice("a").join()
        repo.listPhotos("/sdcard/Pictures/PortalInstaller").join()
        repo.refreshStatus().join()
        repo.disconnect(); repo.selectDevice("a").join()
        repo.listPhotos().join()
        assertFalse(repo.state.value.photoBrowseAll)
        assertEquals("/sdcard/Pictures/PortalInstaller", calls.last { it.url.contains("/photos?") }
            .url.toHttpUrl().queryParameter("path"))
        assertNull(calls.last { it.url.contains("/photos?") }.url.toHttpUrl().queryParameter("recursive"))

        repo.listAllPhotos().join()
        repo.refreshStatus().join()
        repo.disconnect(); repo.selectDevice("a").join(); repo.listPhotos().join()
        assertTrue(repo.state.value.photoBrowseAll)
        assertEquals("/sdcard", calls.last { it.url.contains("/photos?") }.url.toHttpUrl().queryParameter("path"))
        assertEquals("/sdcard/Pictures/PortalInstaller", repo.state.value.photoFolder)
        assertTrue(store.saved.single().device.photoBrowseAll)
    }

    @Test fun partialDiscoveryKeepsItsWarningAndDiscoveredFolders() = runTest {
        val http = CompanionTransport { request ->
            if (request.url.contains("/photos?")) CompanionResponse(200, """{"ok":true,"recursive":true,"truncated":true,
                "files":[{"name":"family.jpg","path":"/sdcard/Pictures/PortalInstaller/family.jpg","size":8,"modified":2}],
                "folders":[{"path":"/sdcard/Pictures/PortalInstaller","count":1},{"path":"/etc","count":1},{"path":"/sdcard/Empty","count":0}]}""")
            else CompanionResponse(200, """{"ok":true,"deviceId":"a","capabilities":{"photos":true,"photoLibrary":true},"settings":{"domains":[]},"sources":{},"np":{}}""")
        }
        val repo = CompanionRepository(CompanionClient(http), Store(), backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repo.ready.join(); repo.selectDevice("a").join(); repo.listPhotos().join()
        assertTrue(repo.state.value.photoListingLoaded)
        assertTrue(repo.state.value.photoListingTruncated)
        assertEquals(listOf(CompanionPhotoFolder("/sdcard/Pictures/PortalInstaller", 1)), repo.state.value.photoFolders)
    }

    @Test fun oldServerIgnoringRecursiveFlagCannotBePresentedAsAllPhotos() = runTest {
        val http = CompanionTransport { request ->
            if (request.url.contains("/photos?")) CompanionResponse(200, """{"ok":true,"files":[]}""")
            else CompanionResponse(200, """{"ok":true,"deviceId":"a","capabilities":{"photos":true,"photoLibrary":true},"settings":{"domains":[]},"sources":{},"np":{}}""")
        }
        val repo = CompanionRepository(CompanionClient(http), Store(), backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repo.ready.join(); repo.selectDevice("a").join(); repo.listPhotos().join()
        assertFalse(repo.state.value.photoListingLoaded)
        assertNotNull(repo.state.value.error)
    }

    @Test fun lateAutomaticScanCannotUndoANewerFolderSelection() = runTest {
        val pending = CompletableDeferred<CompanionResponse>()
        val store = Store()
        val http = CompanionTransport { request ->
            when {
                request.url.toHttpUrl().queryParameter("recursive") == "1" -> pending.await()
                request.url.contains("/photos?") -> CompanionResponse(200, """{"ok":true,"files":[{"name":"selected.jpg","path":"/sdcard/Pictures/Selected/selected.jpg","size":8,"modified":2}]}""")
                else -> CompanionResponse(200, """{"ok":true,"deviceId":"a","capabilities":{"photos":true,"photoLibrary":true},"settings":{"domains":[]},"sources":{},"np":{}}""")
            }
        }
        val repo = CompanionRepository(CompanionClient(http), store, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repo.ready.join(); repo.selectDevice("a").join()
        val old = repo.listPhotos()
        runCurrent()
        repo.listPhotos("/sdcard/Pictures/Selected").join()
        pending.complete(CompanionResponse(200, """{"ok":true,"recursive":true,"files":[]}"""))
        old.join()
        assertFalse(repo.state.value.photoBrowseAll)
        assertEquals("/sdcard/Pictures/Selected", repo.state.value.photoFolder)
        assertEquals("selected.jpg", repo.state.value.photos.single().name)
        assertFalse(store.saved.single().device.photoBrowseAll)
        assertEquals("/sdcard/Pictures/Selected", store.saved.single().device.photoFolder)
    }

    @Test fun discoveryPreservesTwoFoldersWhoseNamesDifferByTrailingWhitespace() = runTest {
        val calls = mutableListOf<CompanionRequest>()
        val http = CompanionTransport { request ->
            calls += request
            if (request.url.contains("/photos?")) CompanionResponse(200, """{"ok":true,"recursive":true,"files":[],
                "folders":[{"path":"/sdcard/Album","count":1},{"path":"/sdcard/Album ","count":2}]}""")
            else CompanionResponse(200, """{"ok":true,"deviceId":"a","capabilities":{"photos":true,"photoLibrary":true},"settings":{"domains":[]},"sources":{},"np":{}}""")
        }
        val store = Store()
        val repo = CompanionRepository(CompanionClient(http), store, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repo.ready.join(); repo.selectDevice("a").join(); repo.listPhotos().join()
        assertEquals(listOf("/sdcard/Album", "/sdcard/Album "), repo.state.value.photoFolders.map { it.path })
        repo.listPhotos("/sdcard/Album ").join()
        assertEquals("/sdcard/Album ", calls.last { it.url.contains("/photos?") }.url.toHttpUrl().queryParameter("path"))
        assertEquals("/sdcard/Album ", store.saved.single().device.photoFolder)
    }

    @Test fun capabilityUpgradeCannotRelabelAnAlreadyLoadedFolderAsAllPhotos() = runTest {
        var upgraded = false
        val http = CompanionTransport { request ->
            if (request.url.contains("/photos?")) CompanionResponse(200,
                if (upgraded) """{"ok":true,"recursive":true,"files":[]}""" else """{"ok":true,"files":[]}""")
            else CompanionResponse(200, """{"ok":true,"deviceId":"a","capabilities":{"photos":true,"photoLibrary":$upgraded},"settings":{"domains":[]},"sources":{},"np":{}}""")
        }
        val repo = CompanionRepository(CompanionClient(http), Store(), backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repo.ready.join(); repo.selectDevice("a").join(); repo.listPhotos().join()
        assertFalse(repo.state.value.photoListingAll)
        upgraded = true
        repo.refreshStatus().join()
        assertTrue(repo.state.value.supports("photoLibrary"))
        assertFalse(repo.state.value.photoListingAll)
        repo.listPhotos().join()
        assertTrue(repo.state.value.photoListingAll)
    }

    @Test fun uploadCompletionRefreshesTheCurrentViewRatherThanUndoingACollectionChoice() = runTest {
        val uploadResponse = CompletableDeferred<CompanionResponse>()
        val calls = mutableListOf<CompanionRequest>()
        val http = CompanionTransport { request ->
            calls += request
            when {
                request.upload != null -> uploadResponse.await()
                request.url.contains("/photos?") -> CompanionResponse(200, """{"ok":true,"files":[]}""")
                else -> CompanionResponse(200, """{"ok":true,"deviceId":"a","capabilities":{"photos":true,"photoLibrary":true},"settings":{"domains":[]},"sources":{},"np":{}}""")
            }
        }
        val repo = CompanionRepository(CompanionClient(http), Store(), backgroundScope, UnconfinedTestDispatcher(testScheduler))
        repo.ready.join(); repo.selectDevice("a").join()
        val upload = repo.uploadPhotos(listOf(PhotoUploadSource("fixture.jpg") {
            CompanionUpload("fixture.jpg", "image/jpeg", 1) { ByteArrayInputStream(byteArrayOf(1)) }
        }))
        runCurrent()
        repo.listPhotos("/sdcard/Pictures/Selected").join()
        uploadResponse.complete(CompanionResponse(200, """{"ok":true,"bytes":1}"""))
        upload.join()
        assertEquals("/sdcard/Pictures/Selected", repo.state.value.photoFolder)
        assertFalse(repo.state.value.photoBrowseAll)
        assertEquals("/sdcard/Pictures/Selected", calls.last { it.url.contains("/photos?") }.url.toHttpUrl().queryParameter("path"))
        assertEquals("/sdcard/Pictures/Immortal", calls.single { it.upload != null }.url.toHttpUrl().queryParameter("path"))
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
