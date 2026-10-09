/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.portalinstaller.data.companion.CompanionClient
import com.portalinstaller.data.companion.FileCompanionSessionStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in, metadata-only test. Existing pairings remain inside the phone process. */
@RunWith(AndroidJUnit4::class)
class CompanionPhotoLibraryDeviceTest {
    @Test fun bothRoomLibrariesIncludeExistingFoldersAndStayResponsiveUnderRepeatedReads() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("livePortal") == "1")
        val sessions = FileCompanionSessionStore.create(
            InstrumentationRegistry.getInstrumentation().targetContext).load()
        val rooms = sessions.filter { it.device.name in setOf("Family Area", "Living Area") }
        assertEquals("Both named Portals must be paired before fleet validation.", 2, rooms.size)
        val client = CompanionClient()
        for (session in rooms) {
            val info = client.get(session, "companion")
            assertTrue(info.getJSONObject("capabilities").getBoolean("photoLibrary"))
            val originalSources = client.get(session, "sources").toString()
            val samples = mutableListOf<Long>()
            repeat(20) {
                val started = SystemClock.elapsedRealtime()
                val listing = client.photos(session, "/sdcard", recursive = true)
                samples += SystemClock.elapsedRealtime() - started
                assertTrue(listing.getBoolean("recursive"))
                assertFalse("Device library scan must finish without truncation.", listing.getBoolean("truncated"))
                val files = listing.getJSONArray("files")
                val paths = (0 until files.length()).map { files.getJSONObject(it).getString("path") }
                assertTrue("Existing collection is missing.", paths.count {
                    it.startsWith("/sdcard/Pictures/PortalInstaller/") } >= 9)
                assertTrue("Root-level photos are missing.", paths.count { it.count { c -> c == '/' } == 2 } >= 3)
                assertEquals(paths.size, paths.distinct().size)
                assertFalse(paths.any { it.startsWith("/sdcard/Android/") })
                assertTrue("Discovery request stalled for more than 3 seconds.", samples.last() < 3_000)
                delay(250)
            }
            assertEquals("Browsing modified the slideshow source.", originalSources, client.get(session, "sources").toString())
            val sorted = samples.sorted()
            Log.i("CompanionPhotoLibrary", "${session.device.name}: samples=${samples.size} medianMs=${sorted[sorted.size / 2]} p95Ms=${sorted[18]} maxMs=${sorted.last()}")
        }
    }
}
