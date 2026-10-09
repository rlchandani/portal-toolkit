/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.portalinstaller.data.companion.CompanionClient
import com.portalinstaller.data.companion.CompanionException
import com.portalinstaller.data.companion.CompanionParser
import com.portalinstaller.data.companion.CompanionPhotos
import com.portalinstaller.data.companion.CompanionRepository
import com.portalinstaller.data.companion.CompanionSession
import com.portalinstaller.data.companion.CompanionSessionStore
import com.portalinstaller.data.companion.CompanionUpload
import com.portalinstaller.data.companion.FileCompanionSessionStore
import com.portalinstaller.data.companion.GroupWorkStatus
import com.portalinstaller.data.companion.PhotoUploadSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real coordinator test. Credentials and user photos never leave the phone. */
@RunWith(AndroidJUnit4::class)
class CompanionMultiPortalDeviceTest {
    private suspend fun rooms(): List<CompanionSession> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("livePortal") == "1")
        val saved = FileCompanionSessionStore.create(
            InstrumentationRegistry.getInstrumentation().targetContext).load()
        val rooms = saved.filter { it.device.name in setOf("Family Area", "Living Area") }
        assertEquals("Both room pairings are required.", 2, rooms.size)
        return rooms
    }

    private fun repository(rooms: List<CompanionSession>, client: CompanionClient) =
        CompanionRepository(client, object : CompanionSessionStore {
            override suspend fun load() = rooms
            override suspend fun save(sessions: List<CompanionSession>) {
                // This validation must not overwrite the user's pairings or selection.
            }
        }, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), preloadManagement = false)

    private suspend fun temperatureUnit(client: CompanionClient, session: CompanionSession): String =
        CompanionParser.settings(client.get(session, "settings"))
            .single { it.id == "immortal" }.controls.single { it.key == "weatherUnit" }.value as String

    @Test fun selectedSettingsFanOutAndRestoreEachRoomsOriginalValue(): Unit = runBlocking {
        val rooms = rooms()
        val client = CompanionClient()
        val originals = rooms.associate { it.device.id to temperatureUnit(client, it) }
        val sources = rooms.associate { it.device.id to client.get(it, "sources").toString() }
        val repo = repository(rooms, client)
        try {
            repo.ready.join()
            assertEquals(rooms.map { it.device.id }.toSet(), repo.state.value.selectedTargetIds)
            val value = if (originals.getValue(rooms.first().device.id) == "c") "f" else "c"
            val started = SystemClock.elapsedRealtime()
            val pending = repo.writeSelectedSettings("immortal", JSONObject().put("weatherUnit", value))
            // Changing selection cannot redirect a captured operation.
            repo.selectTargets(setOf(rooms.first().device.id)).join()
            pending.join()
            val operation = repo.state.value.groupOperations.single()
            assertEquals(rooms.map { it.device.id }.toSet(), operation.items.map { it.targetId }.toSet())
            assertTrue("Each room must acknowledge the requested key.",
                operation.items.all { it.status == GroupWorkStatus.SUCCEEDED })
            for (room in rooms) assertEquals(value, temperatureUnit(client, room))
            Log.i("CompanionMultiPortal", "settings: rooms=2 confirmed=2 elapsedMs=${SystemClock.elapsedRealtime() - started}")
        } finally {
            repo.close()
            val failures = mutableListOf<String>()
            for (room in rooms) try {
                val restored = client.post(room, "settings", JSONObject().put("domain", "immortal")
                    .put("values", JSONObject().put("weatherUnit", originals.getValue(room.device.id))))
                assertTrue((0 until restored.getJSONArray("applied").length()).any {
                    restored.getJSONArray("applied").getString(it) == "weatherUnit" })
                assertEquals(originals.getValue(room.device.id), temperatureUnit(client, room))
                assertEquals("Group editing changed a slideshow source.", sources.getValue(room.device.id),
                    client.get(room, "sources").toString())
            } catch (_: Throwable) { failures += room.device.name }
            assertTrue("Could not restore rooms: ${failures.joinToString()}", failures.isEmpty())
        }
    }

    @Test fun capturedUploadsReachBothOwnFoldersWithFreshStreamsAndOwnedCleanup(): Unit = runBlocking {
        val rooms = rooms()
        val client = CompanionClient()
        val sources = rooms.associate { it.device.id to client.get(it, "sources").toString() }
        val name = "companion-group-${UUID.randomUUID()}.png"
        val bitmap = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(0xff176abb.toInt()) }
        val bytes = try {
            ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
        } finally { bitmap.recycle() }
        val opened = AtomicInteger()
        val disposed = AtomicInteger()
        val source = PhotoUploadSource(name) {
            CompanionUpload(name, "image/png", bytes.size.toLong(), dispose = { disposed.incrementAndGet(); Unit }) {
                opened.incrementAndGet()
                ByteArrayInputStream(bytes)
            }
        }
        val repo = repository(rooms, client)
        val paths = rooms.associate { it.device.id to "${CompanionPhotos.exactFolder(it.device.photoFolder)}/$name" }
        try {
            repo.ready.join()
            val captured = repo.captureSelectedTargets()
            repo.selectTargets(setOf(rooms.first().device.id)).join()
            val started = SystemClock.elapsedRealtime()
            repo.uploadSelectedPhotos(listOf(source), captured).join()
            val operation = repo.state.value.groupOperations.single()
            assertEquals(2, operation.items.size)
            assertTrue("Every captured target must confirm its complete image.",
                operation.items.all { it.status == GroupWorkStatus.SUCCEEDED })
            assertEquals(2, opened.get())
            assertEquals(2, disposed.get())
            for (room in rooms) {
                val files = client.photosExact(room, room.device.photoFolder).getJSONArray("files")
                val found = (0 until files.length()).map { files.getJSONObject(it) }
                    .singleOrNull { it.getString("path") == paths.getValue(room.device.id) }
                assertNotNull("Generated image missing from own import folder.", found)
                assertEquals(bytes.size.toLong(), found!!.getLong("size"))
                assertEquals("Import activated a different slideshow source.", sources.getValue(room.device.id),
                    client.get(room, "sources").toString())
            }
            Log.i("CompanionMultiPortal", "uploads: rooms=2 confirmed=2 freshStreams=2 elapsedMs=${SystemClock.elapsedRealtime() - started}")
        } finally {
            repo.close()
            val failures = mutableListOf<String>()
            for (room in rooms) try {
                // Delete only this test's UUID filename, never an existing household image.
                val files = client.photosExact(room, room.device.photoFolder).getJSONArray("files")
                if ((0 until files.length()).any { files.getJSONObject(it).getString("path") == paths.getValue(room.device.id) })
                    client.deletePhoto(room, paths.getValue(room.device.id))
                val after = client.photosExact(room, room.device.photoFolder).getJSONArray("files")
                assertFalse((0 until after.length()).any { after.getJSONObject(it).getString("path") == paths.getValue(room.device.id) })
                assertEquals(sources.getValue(room.device.id), client.get(room, "sources").toString())
            } catch (_: Throwable) { failures += room.device.name }
            assertTrue("Generated image cleanup failed in: ${failures.joinToString()}", failures.isEmpty())
        }
    }

    @Test fun bothSceneListsReturnStableRevisionsAndRejectStaleReplacement(): Unit = runBlocking {
        val client = CompanionClient()
        for (room in rooms()) {
            assertTrue(client.get(room, "companion").getJSONObject("capabilities").getBoolean("sceneRevisions"))
            val before = client.get(room, "scenes")
            val revision = before.getString("revision")
            assertTrue(revision.matches(Regex("[0-9a-f]{64}")))
            assertEquals(revision, client.get(room, "scenes").getString("revision"))
            val rejected = runCatching {
                client.post(room, "scenes", JSONObject()
                    .put("scenes", before.getJSONArray("scenes")).put("expectedRevision", "0".repeat(64)))
            }.exceptionOrNull()
            assertTrue("A stale list must fail before replacing scenes or alarms.", rejected is CompanionException)
            assertEquals("stale_revision", (rejected as CompanionException).error.code)
            assertEquals(409, rejected.error.status)
            val after = client.get(room, "scenes")
            assertEquals(revision, after.getString("revision"))
            assertEquals(before.getJSONArray("scenes").toString(), after.getJSONArray("scenes").toString())
        }
        Log.i("CompanionMultiPortal", "scene_revisions: rooms=2 stable=2 staleRejected=2 metadataUnchanged=true")
    }
}
