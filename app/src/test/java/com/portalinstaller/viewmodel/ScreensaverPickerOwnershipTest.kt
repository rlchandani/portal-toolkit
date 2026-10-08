/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.viewmodel

import com.portalinstaller.data.ConnectionHolder
import com.portalinstaller.data.photos.AuthOutcome
import com.portalinstaller.data.photos.HttpResult
import com.portalinstaller.data.photos.PhotosPickerClient
import com.portalinstaller.data.photos.PickerHttp
import dadb.Dadb
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy

private fun syntheticDevice(): Dadb =
    Proxy.newProxyInstance(Dadb::class.java.classLoader, arrayOf(Dadb::class.java)) { proxy, method, args ->
        when (method.name) {
            "close" -> null
            "equals" -> proxy === args?.get(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "SyntheticPortal"
            else -> error("This lifecycle test must not perform device operations")
        }
    } as Dadb

class ScreensaverPickerOwnershipTest {
    private val holder = ConnectionHolder().apply { set(syntheticDevice(), "origin") }
    private val owner = ScreensaverPickerOwnership(holder)

    @Test fun `request captures its original connection and folder`() {
        val origin = holder.requireSession()
        val request = owner.begin(origin, "/sdcard/origin")!!

        assertSame(origin, request.origin)
        assertEquals("/sdcard/origin", request.folder)
        assertNull(owner.begin(origin, "/sdcard/changed"))
    }

    @Test fun `authorization completion cannot be rebound after transport replacement`() {
        val request = owner.begin(holder.requireSession(), "/sdcard/origin")!!
        holder.set(syntheticDevice(), "replacement")

        assertFalse(owner.creatingSession(request))
        assertFalse(owner.awaitAuthorization(request))
        assertFalse(owner.pickerReady(request))
    }

    @Test fun `reconnecting the same transport invalidates the original request`() {
        val origin = holder.requireSession()
        val request = owner.begin(origin, "/sdcard/origin")!!
        holder.clear()
        holder.set(origin.dadb, "reconnected")

        assertFalse(owner.creatingSession(request))
        assertFalse(owner.isCurrent(request))
    }

    @Test fun `late authorization callback is drained without claiming a newer request`() {
        val old = owner.begin(holder.requireSession(), "/sdcard/old")!!
        assertTrue(owner.awaitAuthorization(old))
        assertTrue(owner.authorizationShown(old))
        owner.finish(old)

        // The ActivityResult callback carries no request ID. Keep a tombstone until it returns.
        assertNull(owner.begin(holder.requireSession(), "/sdcard/new"))
        assertNull(owner.takeAuthorization())
        val next = owner.begin(holder.requireSession(), "/sdcard/new")!!
        assertNull(owner.takeAuthorization())
        assertTrue(owner.isCurrent(next))
    }

    @Test fun `late picker resume is drained without importing into a newer request`() {
        val old = owner.begin(holder.requireSession(), "/sdcard/old")!!
        assertTrue(owner.creatingSession(old))
        assertTrue(owner.pickerReady(old))
        assertTrue(owner.pickerShown(old))
        owner.finish(old)

        assertNull(owner.begin(holder.requireSession(), "/sdcard/new"))
        assertNull(owner.takePicker())
        val next = owner.begin(holder.requireSession(), "/sdcard/new")!!
        assertNull(owner.takePicker())
        assertTrue(owner.isCurrent(next))
    }

    @Test fun `undelivered stale events neither launch UI nor block recovery`() {
        val old = owner.begin(holder.requireSession(), "/sdcard/old")!!
        assertTrue(owner.awaitAuthorization(old))
        owner.finish(old)
        val next = owner.begin(holder.requireSession(), "/sdcard/new")!!

        assertFalse(owner.authorizationShown(old))
        assertFalse(owner.pickerShown(old))
        assertTrue(owner.isCurrent(next))
    }

    @Test fun `old import finally cannot clear a newer pending picker`() {
        val old = owner.begin(holder.requireSession(), "/sdcard/old")!!
        assertTrue(owner.creatingSession(old))
        assertTrue(owner.pickerReady(old))
        assertTrue(owner.pickerShown(old))
        assertSame(old, owner.takePicker())
        owner.finish(old)
        val next = owner.begin(holder.requireSession(), "/sdcard/new")!!
        assertTrue(owner.creatingSession(next))
        assertTrue(owner.pickerReady(next))
        assertTrue(owner.pickerShown(next))

        assertFalse(owner.finish(old))
        assertSame(next, owner.takePicker())
        assertNull(owner.takePicker())
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ScreensaverPickerFlowTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private class Http : PickerHttp {
        var posts = 0
        var onPost: (Int) -> Unit = {}
        val deleted = mutableListOf<String>()
        val deletion = CompletableDeferred<Unit>()
        val downloaded = mutableListOf<File>()
        override fun post(url: String, token: String, jsonBody: String): HttpResult {
            val id = ++posts
            onPost(id)
            return HttpResult(200, """{"id":"s$id","pickerUri":"https://picker.invalid/s$id"}""")
        }
        override fun get(url: String, token: String): HttpResult =
            if (url.contains("/sessions/")) HttpResult(200, """{"id":"s","mediaItemsSet":true}""")
            else HttpResult(200, """{"mediaItems":[{"id":"p","type":"PHOTO","mediaFile":{"filename":"one.jpg","baseUrl":"https://photos.invalid/p"}}]}""")
        override fun delete(url: String, token: String): HttpResult {
            deleted += url.substringAfterLast('/')
            deletion.complete(Unit)
            return HttpResult(204, "")
        }
        override fun downloadTo(url: String, token: String, dest: File) {
            downloaded += dest
            dest.writeText("image")
        }
    }

    @Test fun `import lists the captured folder before authorization and preserves its names`() = runTest {
        val holder = ConnectionHolder().apply { set(syntheticDevice(), "origin") }
        val origin = holder.requireSession()
        val http = Http()
        val listing = CompletableDeferred<Set<String>>()
        val targets = mutableListOf<Pair<Dadb, String>>()
        val flow = ScreensaverPickerFlow(holder, this, PhotosPickerClient(http, StandardTestDispatcher(testScheduler))) { session, folder ->
            targets += session.dadb to folder
            listing.await()
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.events.collect {} }
        var authorized = false
        // The displayed contents may still be those of A. Import requests B directly.
        flow.start(origin, "/sdcard/B", authorize = { authorized = true; AuthOutcome.Token("synthetic") })
        testScheduler.runCurrent()
        assertFalse(authorized)
        assertEquals(listOf(origin.dadb to "/sdcard/B"), targets)
        listing.complete(setOf("one.jpg"))
        testScheduler.runCurrent()
        val completed = CompletableDeferred<UploadSummaryState.Done>()
        flow.onPickerReturned(temporaryFolder.root, upload = { _, _, _ -> error("Existing photo must be preserved") },
            onComplete = { done, _, folder ->
                assertEquals("/sdcard/B", folder)
                completed.complete(done)
            })

        assertEquals(UploadSummaryState.Done(0, 1, 0), completed.await())
        testScheduler.runCurrent()
        assertTrue(authorized)
        assertTrue(http.downloaded.isEmpty())
        assertEquals(listOf("s1"), http.deleted)
        assertFalse(flow.isBusy)
    }

    @Test fun `destination listing failure stops authorization and allows a fresh retry`() = runTest {
        val holder = ConnectionHolder().apply { set(syntheticDevice(), "origin") }
        val http = Http()
        var unavailable = true
        var listings = 0
        val flow = ScreensaverPickerFlow(holder, this, PhotosPickerClient(http, StandardTestDispatcher(testScheduler))) { _, _ ->
            listings++
            if (unavailable) throw IOException("permission denied")
            emptySet()
        }
        var authorized = false
        val errors = mutableListOf<String>()
        flow.start(holder.requireSession(), "/sdcard/B",
            authorize = { authorized = true; AuthOutcome.Token("synthetic") }, onFailure = { errors += it })
        testScheduler.runCurrent()

        assertEquals(listOf("permission denied"), errors)
        assertFalse(authorized)
        assertEquals(0, http.posts)
        assertFalse(flow.isBusy)
        unavailable = false
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.events.collect {} }
        assertTrue(flow.start(holder.requireSession(), "/sdcard/B", authorize = { AuthOutcome.Token("synthetic") }))
        testScheduler.runCurrent()
        val completed = CompletableDeferred<Unit>()
        flow.onPickerReturned(temporaryFolder.root, upload = { _, _, _ -> },
            onComplete = { _, _, _ -> completed.complete(Unit) })
        completed.await()
        testScheduler.runCurrent()
        assertEquals(2, listings)
        assertEquals(listOf("s1"), http.deleted)
        assertFalse(flow.isBusy)
    }

    @Test fun `late destination listing after connection replacement cannot authorize`() = runTest {
        val holder = ConnectionHolder().apply { set(syntheticDevice(), "origin") }
        val http = Http()
        val listing = CompletableDeferred<Set<String>>()
        val flow = ScreensaverPickerFlow(holder, this, PhotosPickerClient(http, StandardTestDispatcher(testScheduler))) { _, _ ->
            withContext(NonCancellable) { listing.await() }
        }
        var authorized = false
        flow.start(holder.requireSession(), "/sdcard/B",
            authorize = { authorized = true; AuthOutcome.Token("synthetic") })
        testScheduler.runCurrent()
        holder.set(syntheticDevice(), "replacement")
        listing.complete(emptySet())
        testScheduler.runCurrent()

        assertFalse(authorized)
        assertEquals(0, http.posts)
        assertFalse(flow.isBusy)
    }

    @Test fun `authorization job is cancelled by connection loss without a UI disconnect hook`() = runTest {
        val holder = ConnectionHolder().apply { set(syntheticDevice(), "origin") }
        val http = Http()
        val flow = ScreensaverPickerFlow(holder, this, PhotosPickerClient(http, StandardTestDispatcher(testScheduler))) { _, _ -> emptySet() }
        var stopped = false
        flow.start(holder.requireSession(), "/sdcard/origin", authorize = {
            try { awaitCancellation() } finally { stopped = true }
        })
        testScheduler.runCurrent()

        holder.clear()
        testScheduler.runCurrent()

        assertTrue(stopped)
        assertFalse(flow.isBusy)
        assertEquals(0, http.posts)
    }

    @Test fun `late authorization result cannot create a picker after disconnect`() = runTest {
        val holder = ConnectionHolder().apply { set(syntheticDevice(), "origin") }
        val http = Http()
        val flow = ScreensaverPickerFlow(holder, this, PhotosPickerClient(http, StandardTestDispatcher(testScheduler))) { _, _ -> emptySet() }
        val token = CompletableDeferred<AuthOutcome>()
        val events = mutableListOf<PickerEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.events.collect { events += it } }
        flow.start(holder.requireSession(), "/sdcard/origin", authorize = {
            withContext(NonCancellable) { token.await() }
        })
        testScheduler.runCurrent()
        holder.clear()
        flow.cancel()
        token.complete(AuthOutcome.Token("synthetic"))
        testScheduler.runCurrent()

        assertEquals(0, http.posts)
        assertTrue(events.isEmpty())
        assertFalse(flow.isBusy)
    }

    @Test fun `picker return uploads only to the captured connection and folder`() = runTest {
        val holder = ConnectionHolder().apply { set(syntheticDevice(), "origin") }
        val origin = holder.requireSession()
        val http = Http()
        val flow = ScreensaverPickerFlow(holder, this, PhotosPickerClient(http, StandardTestDispatcher(testScheduler))) { _, _ -> emptySet() }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.events.collect {} }
        flow.start(origin, "/sdcard/original", authorize = { AuthOutcome.Token("synthetic") })
        testScheduler.runCurrent()
        val targets = mutableListOf<Pair<Dadb, String>>()
        val completed = CompletableDeferred<UploadSummaryState.Done>()

        assertTrue(flow.onPickerReturned(temporaryFolder.root,
            upload = { session, folder, _ -> targets += session.dadb to folder },
            onComplete = { done, _, _ -> completed.complete(done) }))
        assertEquals(UploadSummaryState.Done(1, 0, 0), completed.await())
        testScheduler.runCurrent()

        assertEquals(listOf(origin.dadb to "/sdcard/original"), targets)
        assertEquals(listOf("s1"), http.deleted)
        assertFalse(flow.isBusy)
        assertTrue(temporaryFolder.root.listFiles()!!.isEmpty())
    }

    @Test fun `stale OAuth callback is ignored before reading its intent result`() = runTest {
        val holder = ConnectionHolder().apply { set(syntheticDevice(), "origin") }
        val flow = ScreensaverPickerFlow(holder, this, PhotosPickerClient(Http(), StandardTestDispatcher(testScheduler))) { _, _ -> emptySet() }
        var read = false

        assertFalse(flow.onAuthResolved { read = true; AuthOutcome.Token("synthetic") })

        assertFalse(read)
    }

    @Test fun `session created during replacement is deleted and cannot overwrite recovery`() = runTest {
        val holder = ConnectionHolder().apply { set(syntheticDevice(), "origin") }
        val http = Http()
        val flow = ScreensaverPickerFlow(holder, this, PhotosPickerClient(http, StandardTestDispatcher(testScheduler))) { _, _ -> emptySet() }
        val opened = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            flow.events.collect { if (it is PickerEvent.OpenPicker) opened += it.uri }
        }
        http.onPost = { count ->
            if (count == 1) {
                holder.set(syntheticDevice(), "replacement")
                flow.cancel()
                assertTrue(flow.start(holder.requireSession(), "/sdcard/recovery",
                    authorize = { AuthOutcome.Token("synthetic") }))
            }
        }
        flow.start(holder.requireSession(), "/sdcard/old", authorize = { AuthOutcome.Token("synthetic") })
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("s1"), http.deleted)
        assertEquals(listOf("https://picker.invalid/s2"), opened)
        assertTrue(flow.isBusy)
        val folders = mutableListOf<String>()
        val completed = CompletableDeferred<Unit>()
        flow.onPickerReturned(temporaryFolder.root, upload = { _, folder, _ -> folders += folder },
            onComplete = { _, _, _ -> completed.complete(Unit) })
        completed.await()
        testScheduler.runCurrent()
        assertEquals(listOf("/sdcard/recovery"), folders)
        assertEquals(setOf("s1", "s2"), http.deleted.toSet())
    }

    @Test fun `replacement during import prevents later uploads and stale completion`() = runTest {
        val holder = ConnectionHolder().apply { set(syntheticDevice(), "origin") }
        val http = Http()
        val flow = ScreensaverPickerFlow(holder, this, PhotosPickerClient(http, StandardTestDispatcher(testScheduler))) { _, _ -> emptySet() }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.events.collect {} }
        flow.start(holder.requireSession(), "/sdcard/origin", authorize = { AuthOutcome.Token("synthetic") })
        testScheduler.runCurrent()
        val started = CompletableDeferred<Unit>()
        var published = false
        flow.onPickerReturned(temporaryFolder.root, upload = { _, _, _ ->
            started.complete(Unit)
            awaitCancellation()
        }, onComplete = { _, _, _ -> published = true })
        started.await()
        holder.set(syntheticDevice(), "replacement")
        http.deletion.await()
        testScheduler.runCurrent()

        assertFalse(published)
        assertFalse(flow.isBusy)
        assertEquals(listOf("s1"), http.deleted)
        assertTrue(temporaryFolder.root.listFiles()!!.isEmpty())
    }

    @Test fun `old import cleanup cannot erase the replacement picker or its target`() = runTest {
        val holder = ConnectionHolder().apply { set(syntheticDevice(), "origin") }
        val http = Http()
        val flow = ScreensaverPickerFlow(holder, this, PhotosPickerClient(http, StandardTestDispatcher(testScheduler))) { _, _ -> emptySet() }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.events.collect {} }
        flow.start(holder.requireSession(), "/sdcard/old", authorize = { AuthOutcome.Token("synthetic") })
        testScheduler.runCurrent()
        val uploading = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var oldPublished = false
        flow.onPickerReturned(temporaryFolder.root, upload = { _, _, _ ->
            uploading.complete(Unit)
            withContext(NonCancellable) { release.await() }
        }, onComplete = { _, _, _ -> oldPublished = true })
        uploading.await()
        flow.cancel()
        holder.set(syntheticDevice(), "replacement")
        val replacement = holder.requireSession()
        assertTrue(flow.start(replacement, "/sdcard/new", authorize = { AuthOutcome.Token("synthetic") }))
        testScheduler.runCurrent()
        release.complete(Unit)
        http.deletion.await()
        testScheduler.runCurrent()

        assertFalse(oldPublished)
        assertTrue(flow.isBusy)
        assertEquals(listOf("s1"), http.deleted)
        val completed = CompletableDeferred<Unit>()
        val destinations = mutableListOf<Pair<Dadb, String>>()
        assertTrue(flow.onPickerReturned(temporaryFolder.root,
            upload = { session, folder, _ -> destinations += session.dadb to folder },
            onComplete = { _, _, _ -> completed.complete(Unit) }))
        completed.await()
        testScheduler.runCurrent()

        assertEquals(listOf(replacement.dadb to "/sdcard/new"), destinations)
        assertEquals(setOf("s1", "s2"), http.deleted.toSet())
        assertFalse(flow.isBusy)
        assertTrue(temporaryFolder.root.listFiles()!!.isEmpty())
    }

    @Test fun `picker event emitted without a collector survives recreation and import completes`() = runTest {
        val holder = ConnectionHolder().apply { set(syntheticDevice(), "origin") }
        val http = Http()
        val flow = ScreensaverPickerFlow(holder, this, PhotosPickerClient(http, StandardTestDispatcher(testScheduler))) { _, _ -> emptySet() }
        try {
            flow.start(holder.requireSession(), "/sdcard/origin", authorize = { AuthOutcome.Token("synthetic") })
            testScheduler.runCurrent()
            assertEquals(1, http.posts)
            val opened = mutableListOf<String>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                flow.events.collect { if (it is PickerEvent.OpenPicker) opened += it.uri }
            }
            testScheduler.runCurrent()
            assertEquals(listOf("https://picker.invalid/s1"), opened)
            val completed = CompletableDeferred<UploadSummaryState.Done>()
            assertTrue(flow.onPickerReturned(temporaryFolder.root, upload = { _, _, _ -> },
                onComplete = { done, _, _ -> completed.complete(done) }))
            assertEquals(UploadSummaryState.Done(1, 0, 0), completed.await())
            testScheduler.runCurrent()
            assertFalse(flow.isBusy)
            assertEquals(listOf("s1"), http.deleted)
        } finally {
            flow.cancel()
        }
    }

    @Test fun `retained stale picker event is dropped while replacement event is delivered`() = runTest {
        val holder = ConnectionHolder().apply { set(syntheticDevice(), "origin") }
        val http = Http()
        val flow = ScreensaverPickerFlow(holder, this, PhotosPickerClient(http, StandardTestDispatcher(testScheduler))) { _, _ -> emptySet() }
        try {
            flow.start(holder.requireSession(), "/sdcard/old", authorize = { AuthOutcome.Token("synthetic") })
            testScheduler.runCurrent()
            flow.cancel()
            testScheduler.runCurrent()
            holder.set(syntheticDevice(), "replacement")
            flow.start(holder.requireSession(), "/sdcard/new", authorize = { AuthOutcome.Token("synthetic") })
            testScheduler.runCurrent()
            val opened = mutableListOf<String>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                flow.events.collect { if (it is PickerEvent.OpenPicker) opened += it.uri }
            }
            testScheduler.runCurrent()

            assertEquals(listOf("https://picker.invalid/s2"), opened)
            assertEquals(listOf("s1"), http.deleted)
            val completed = CompletableDeferred<Unit>()
            flow.onPickerReturned(temporaryFolder.root, upload = { _, _, _ -> },
                onComplete = { _, _, _ -> completed.complete(Unit) })
            completed.await()
            testScheduler.runCurrent()
            assertEquals(setOf("s1", "s2"), http.deleted.toSet())
            assertTrue(temporaryFolder.root.listFiles()!!.isEmpty())
        } finally {
            flow.cancel()
        }
    }
}
