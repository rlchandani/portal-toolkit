/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.viewmodel

import com.portalinstaller.data.ConnectionHolder
import com.portalinstaller.data.files.RemoteFile
import com.portalinstaller.data.screensaver.ImageFolder
import dadb.Dadb
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
class ScreensaverListingsTest {
    private fun device(): Dadb =
        Proxy.newProxyInstance(Dadb::class.java.classLoader, arrayOf(Dadb::class.java)) { proxy, method, args ->
            when (method.name) {
                "close" -> null
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                else -> error("Listing adapters must use the injected data source")
            }
        } as Dadb

    private fun listing(folder: String, size: Long = 100) = ScreensaverFolderListing(
        listOf(RemoteFile("photo.jpg", "$folder/photo.jpg", false, false, size, "", "-rw-r--r--")),
        size, size * 10,
    )

    @Test fun `failed same-folder refresh retains the last successful photos and storage`() = runTest {
        val holder = ConnectionHolder().apply { set(device(), "origin") }
        val selected = setOf("/sdcard/photos/1.jpg")
        val state = MutableStateFlow(ScreensaverState(folder = "/sdcard/photos", selection = selected))
        val previous = ScreensaverFolderListing(
            (1..9).map { listing("/sdcard/photos").contents.single().copy(name = "$it.jpg", path = "/sdcard/photos/$it.jpg") },
            26_000_000L, 14_900_000_000L,
        )
        var unavailable = false
        val listings = ScreensaverListings(holder, this, state, "/sdcard/default", discover = { emptyList() },
            list = { _, _ ->
                if (unavailable) throw IOException("synthetic read failure with private details")
                previous
            })
        listings.refresh(holder.requireSession())
        testScheduler.runCurrent()
        assertEquals(selected, state.value.selection)
        unavailable = true
        listings.refresh(holder.requireSession())
        testScheduler.runCurrent()

        assertTrue(state.value.listingFailed)
        assertFalse(state.value.isLoading)
        assertEquals(previous.contents, state.value.contents)
        assertEquals(previous.folderBytes, state.value.folderBytes)
        assertEquals(previous.deviceFreeBytes, state.value.deviceFreeBytes)
        assertTrue(state.value.selection.isEmpty())
    }

    @Test fun `failed first read of a new folder is failure rather than an empty success`() = runTest {
        val holder = ConnectionHolder().apply { set(device(), "origin") }
        val state = MutableStateFlow(ScreensaverState(folder = "/sdcard/A", contents = listing("/sdcard/A").contents))
        val listings = ScreensaverListings(holder, this, state, "/sdcard/default", discover = { emptyList() },
            list = { _, _ -> throw IOException("cannot read new folder") })
        listings.chooseFolder("/sdcard/B")
        listings.refresh(holder.requireSession())
        testScheduler.runCurrent()

        assertEquals("/sdcard/B", state.value.folder)
        assertTrue(state.value.contents.isEmpty())
        assertTrue(state.value.listingFailed)
        assertFalse(state.value.isLoading)
    }

    @Test fun `retry clears the failure while loading and success replaces last-known contents`() = runTest {
        val holder = ConnectionHolder().apply { set(device(), "origin") }
        val state = MutableStateFlow(ScreensaverState(folder = "/sdcard/A"))
        val retried = CompletableDeferred<ScreensaverFolderListing>()
        var attempts = 0
        val listings = ScreensaverListings(holder, this, state, "/sdcard/default", discover = { emptyList() },
            list = { _, _ ->
                if (++attempts == 1) throw IOException("temporarily unavailable")
                retried.await()
            })
        listings.refresh(holder.requireSession())
        testScheduler.runCurrent()
        val failed = state.value.listingFailed
        listings.refresh(holder.requireSession())
        val failureDuringRetry = state.value.listingFailed
        val loadingDuringRetry = state.value.isLoading
        testScheduler.runCurrent()
        retried.complete(listing("/sdcard/A", 500))
        testScheduler.runCurrent()

        assertTrue(failed)
        assertFalse(failureDuringRetry)
        assertTrue(loadingDuringRetry)
        assertFalse(state.value.listingFailed)
        assertFalse(state.value.isLoading)
        assertEquals(listing("/sdcard/A", 500).contents, state.value.contents)
        assertEquals(500L, state.value.folderBytes)
    }

    @Test fun `choosing another folder clears the previous read failure and stale data`() = runTest {
        val holder = ConnectionHolder().apply { set(device(), "origin") }
        val state = MutableStateFlow(ScreensaverState(
            folder = "/sdcard/A", contents = listing("/sdcard/A").contents,
            folderBytes = 100L, deviceFreeBytes = 1_000L, listingFailed = true,
        ))
        val listings = ScreensaverListings(holder, this, state, "/sdcard/default", discover = { emptyList() },
            list = { _, folder -> listing(folder) })

        listings.chooseFolder("/sdcard/B")

        assertFalse(state.value.listingFailed)
        assertFalse(state.value.isLoading)
        assertTrue(state.value.contents.isEmpty())
        assertEquals(0L, state.value.folderBytes)
        assertEquals(0L, state.value.deviceFreeBytes)
    }

    @Test fun `slow A listing cannot populate B after folder selection`() = runTest {
        val holder = ConnectionHolder().apply { set(device(), "origin") }
        val origin = holder.requireSession()
        val state = MutableStateFlow(ScreensaverState(folder = "/sdcard/A", contents = listing("/sdcard/A").contents))
        val delayed = CompletableDeferred<ScreensaverFolderListing>()
        val targets = mutableListOf<String>()
        val listings = ScreensaverListings(holder, this, state, "/sdcard/default", discover = { emptyList() },
            list = { session, folder ->
                assertSame(origin, session)
                targets += folder
                if (folder == "/sdcard/A") withContext(NonCancellable) { delayed.await() } else listing(folder, 200)
            })
        listings.refresh(origin)
        testScheduler.runCurrent()
        listings.chooseFolder("/sdcard/B")
        val contentsWhileWaiting = state.value.contents
        listings.refresh(origin)
        testScheduler.runCurrent()
        delayed.complete(listing("/sdcard/A"))
        testScheduler.runCurrent()

        assertTrue(contentsWhileWaiting.isEmpty())
        assertEquals(listOf("/sdcard/A", "/sdcard/B"), targets)
        assertEquals("/sdcard/B", state.value.folder)
        assertEquals(listing("/sdcard/B", 200).contents, state.value.contents)
        assertEquals(200L, state.value.folderBytes)
        assertEquals(2_000L, state.value.deviceFreeBytes)
        assertFalse(state.value.isLoading)
    }

    @Test fun `older refresh of the same folder cannot replace latest contents or metrics`() = runTest {
        val holder = ConnectionHolder().apply { set(device(), "origin") }
        val state = MutableStateFlow(ScreensaverState(folder = "/sdcard/A"))
        val delayed = CompletableDeferred<ScreensaverFolderListing>()
        var requests = 0
        val listings = ScreensaverListings(holder, this, state, "/sdcard/default", discover = { emptyList() },
            list = { _, folder ->
                if (++requests == 1) withContext(NonCancellable) { delayed.await() } else listing(folder, 300)
            })
        listings.refresh(holder.requireSession())
        testScheduler.runCurrent()
        listings.refresh(holder.requireSession())
        testScheduler.runCurrent()
        delayed.complete(listing("/sdcard/A", 100))
        testScheduler.runCurrent()

        assertEquals(listing("/sdcard/A", 300).contents, state.value.contents)
        assertEquals(300L, state.value.folderBytes)
        assertFalse(state.value.isLoading)
    }

    @Test fun `late discovery cannot restore an old folder or its suggestions after choosing B`() = runTest {
        val holder = ConnectionHolder().apply { set(device(), "origin") }
        val state = MutableStateFlow(ScreensaverState())
        val delayed = CompletableDeferred<List<ImageFolder>>()
        val targets = mutableListOf<String>()
        val listings = ScreensaverListings(holder, this, state, "/sdcard/default",
            discover = { withContext(NonCancellable) { delayed.await() } },
            list = { _, folder -> targets += folder; listing(folder) })
        listings.loadFolders(holder.requireSession())
        testScheduler.runCurrent()
        listings.chooseFolder("/sdcard/B")
        listings.refresh(holder.requireSession())
        testScheduler.runCurrent()
        delayed.complete(listOf(ImageFolder("/sdcard/A", 20)))
        testScheduler.runCurrent()

        assertEquals(listOf("/sdcard/B"), targets)
        assertEquals("/sdcard/B", state.value.folder)
        assertTrue(state.value.suggestions.isEmpty())
        assertEquals(listing("/sdcard/B").contents, state.value.contents)
    }

    @Test fun `late old-device listing cannot overwrite the replacement device`() = runTest {
        val holder = ConnectionHolder().apply { set(device(), "origin") }
        val origin = holder.requireSession()
        val state = MutableStateFlow(ScreensaverState(folder = "/sdcard/A"))
        val delayed = CompletableDeferred<ScreensaverFolderListing>()
        val listings = ScreensaverListings(holder, this, state, "/sdcard/default", discover = { emptyList() },
            list = { session, folder ->
                if (session == origin) withContext(NonCancellable) { delayed.await() } else listing(folder, 400)
            })
        listings.refresh(origin)
        testScheduler.runCurrent()
        holder.clear()
        listings.cancel()
        state.value = ScreensaverState(folder = "/sdcard/A")
        // Reusing the transport after reconnect must still invalidate its old generation.
        holder.set(origin.dadb, "reconnected")
        listings.refresh(holder.requireSession())
        testScheduler.runCurrent()
        delayed.complete(listing("/sdcard/A", 100))
        testScheduler.runCurrent()

        assertEquals(400L, state.value.folderBytes)
        assertEquals(listing("/sdcard/A", 400).contents, state.value.contents)
        assertFalse(state.value.isLoading)
    }

    @Test fun `late failed A request cannot clear a successful B listing`() = runTest {
        val holder = ConnectionHolder().apply { set(device(), "origin") }
        val state = MutableStateFlow(ScreensaverState(folder = "/sdcard/A"))
        val delayed = CompletableDeferred<Unit>()
        val listings = ScreensaverListings(holder, this, state, "/sdcard/default", discover = { emptyList() },
            list = { _, folder ->
                if (folder == "/sdcard/A") {
                    withContext(NonCancellable) { delayed.await() }
                    throw IOException("old listing failed")
                }
                listing(folder)
            })
        listings.refresh(holder.requireSession())
        testScheduler.runCurrent()
        listings.chooseFolder("/sdcard/B")
        listings.refresh(holder.requireSession())
        testScheduler.runCurrent()
        delayed.complete(Unit)
        testScheduler.runCurrent()

        assertEquals(listing("/sdcard/B").contents, state.value.contents)
        assertFalse(state.value.isLoading)
        assertFalse(state.value.listingFailed)
    }

    @Test fun `latest folder discovery lists its selected folder on the same origin`() = runTest {
        val holder = ConnectionHolder().apply { set(device(), "origin") }
        val origin = holder.requireSession()
        val state = MutableStateFlow(ScreensaverState())
        val suggestions = listOf(ImageFolder("/sdcard/photos", 5))
        val listings = ScreensaverListings(holder, this, state, "/sdcard/default",
            discover = { session -> assertSame(origin, session); suggestions },
            list = { session, folder -> assertSame(origin, session); listing(folder) })
        listings.loadFolders(origin)
        testScheduler.runCurrent()

        assertEquals("/sdcard/photos", state.value.folder)
        assertEquals(suggestions, state.value.suggestions)
        assertEquals(listing("/sdcard/photos").contents, state.value.contents)
        assertFalse(state.value.isLoading)
    }
}
