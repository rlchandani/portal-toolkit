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
import com.portalinstaller.data.companion.CompanionSession
import com.portalinstaller.data.companion.CompanionUpload
import com.portalinstaller.data.companion.FileCompanionSessionStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in live integration through the app's own client. Credentials never leave
 * its process; logs contain only test outcomes. Run on an already-paired phone:
 * adb shell am instrument -w -e livePortal 1 com.portalinstaller.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class CompanionPairedDeviceTest {
    private lateinit var session: CompanionSession
    private val client = CompanionClient()

    @Before fun selectPairedPortal() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("livePortal") == "1")
        val sessions = FileCompanionSessionStore.create(instrumentation.targetContext).load()
        assumeTrue("Pair exactly one test Portal in the companion first.", sessions.size == 1)
        session = sessions.single()
    }

    @Test fun identityRegistryCatalogAndJarvisAreAvailable() = runBlocking {
        val info = client.get(session, "companion")
        assertEquals(1, info.getInt("protocolVersion"))
        assertTrue(info.getString("deviceId").isNotBlank())
        val capabilities = info.getJSONObject("capabilities")
        for (key in listOf("layout", "snapshot", "operations", "scenes", "photos", "jarvis"))
            assertTrue("Capability unavailable: $key", capabilities.optBoolean(key))
        val settings = client.get(session, "settings").getJSONObject("settings").getJSONArray("domains")
        assertTrue(settings.length() >= 10)
        assertTrue(client.get(session, "catalog").getJSONArray("apps").length() > 0)
        val jarvis = client.get(session, "jarvis").getJSONObject("jarvis")
        assertTrue(jarvis.getBoolean("supported"))
        assertTrue(jarvis.getBoolean("microphoneGranted"))
        assertEquals("gemini-3.8-live", jarvis.getString("model"))
        val configured = client.post(session, "jarvis", JSONObject().put("model", jarvis.getString("model")))
        assertTrue(configured.getBoolean("ok"))
        assertEquals(jarvis.getString("model"), client.get(session, "jarvis").getJSONObject("jarvis").getString("model"))
    }

    @Test fun nativeLayoutRoundTripsAndRejectsStaleSave() = runBlocking {
        val original = client.get(session, "layout")
        val layout = original.getJSONObject("layout")
        client.post(session, "layout", JSONObject().put("layout", layout).put("expectedRevision", original.getString("revision")))
        val after = client.get(session, "layout")
        assertEquals(layout.toString(), after.getJSONObject("layout").toString())
        val error = runCatching {
            client.post(session, "layout", JSONObject().put("layout", layout).put("expectedRevision", "0".repeat(64)))
        }.exceptionOrNull()
        assertTrue(error is CompanionException)
        assertEquals("stale_revision", (error as CompanionException).error.code)
    }

    @Test fun snapshotRestoresCurrentStateAndRejectsUnknownFields() = runBlocking {
        val baseline = client.get(session, "snapshot").getJSONObject("snapshot")
        client.post(session, "snapshot", JSONObject().put("snapshot", baseline).put("expectedRevision", baseline.getString("revision")))
        val after = client.get(session, "snapshot").getJSONObject("snapshot")
        assertEquals(baseline.getJSONObject("domains").toString(), after.getJSONObject("domains").toString())
        assertEquals(baseline.getJSONObject("layout").toString(), after.getJSONObject("layout").toString())
        val invalid = JSONObject(baseline.toString()).put("unknown", true)
        assertTrue(runCatching { client.post(session, "snapshot", JSONObject().put("snapshot", invalid)) }.isFailure)
    }

    @Test fun imagesUploadBrowseReadAndDeleteWithoutReplacingExistingFiles() = runBlocking {
        val folder = "/sdcard/Pictures/Immortal-Companion-Test"
        val name = "companion-${UUID.randomUUID()}.png"
        val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff176abb.toInt()) }
        val bytes = try { ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it); it.toByteArray() } }
            finally { bitmap.recycle() }
        val upload = CompanionUpload(name, "image/png", bytes.size.toLong()) { ByteArrayInputStream(bytes) }
        val uploaded = client.uploadPhoto(session, folder, upload) {}
        val path = uploaded.getString("path")
        try {
            assertTrue(client.photos(session, folder).getJSONArray("files").length() > 0)
            assertTrue(client.photoBytes(session, path, true).isNotEmpty())
            assertArrayEquals(bytes, client.photoBytes(session, path, false))
            assertTrue(runCatching { client.uploadPhoto(session, folder, upload) {} }.isFailure)
            assertArrayEquals(bytes, client.photoBytes(session, path, false))
            val originalSource = client.get(session, "sources").getJSONObject("sources")
            // Only a current local source can be restored without touching other providers.
            if (originalSource.optString("source") in setOf("folder", "local")) {
                val originalFolder = originalSource.getString("folderPath")
                try {
                    client.post(session, "sources", JSONObject().put("folderPath", folder))
                    val active = client.get(session, "sources").getJSONObject("sources")
                    assertEquals(folder, active.getString("folderPath"))
                    assertEquals("folder", active.getString("source"))
                } finally {
                    client.post(session, "sources", JSONObject().put("folderPath", originalFolder))
                    assertEquals(originalFolder,
                        client.get(session, "sources").getJSONObject("sources").getString("folderPath"))
                    Log.i("CompanionValidation", "photo_source_activation=verified_and_restored")
                }
            }
        } finally { client.deletePhoto(session, path) }
        val files = client.photos(session, folder).getJSONArray("files")
        assertFalse((0 until files.length()).any { files.getJSONObject(it).getString("name") == name })
    }

    @Test fun sceneRunsAndRetainsItsResult() = runBlocking {
        val original = client.get(session, "scenes").getJSONArray("scenes")
        val id = UUID.randomUUID().toString()
        val scene = JSONObject().put("id", id).put("name", "Companion device validation")
            .put("steps", JSONArray().put(JSONObject().put("type", "wait").put("ms", 50))
                .put(JSONObject().put("type", "key").put("action", "home")))
        val draft = JSONArray(original.toString()).put(scene)
        client.post(session, "scenes", JSONObject().put("scenes", draft))
        try {
            val operation = client.post(session, "scene", JSONObject().put("id", id)).getJSONObject("operation")
            val operationId = operation.getString("id")
            var completed: JSONObject? = null
            for (attempt in 0 until 40) {
                delay(250)
                val operations = client.get(session, "operations").getJSONArray("operations")
                completed = (0 until operations.length()).map { operations.getJSONObject(it) }
                    .firstOrNull { it.getString("id") == operationId && it.getString("state") != "running" }
                if (completed != null) break
            }
            assertNotNull("Scene did not finish within ten seconds.", completed)
            assertEquals("succeeded", completed!!.getString("state"))
        } finally { client.post(session, "scenes", JSONObject().put("scenes", original)) }
    }

    @Test fun companionStatusRemainsResponsiveAcrossRepeatedRequests() = runBlocking {
        val durations = (0 until 20).map {
            val start = SystemClock.elapsedRealtime()
            assertTrue(client.get(session, "companion").getBoolean("ok"))
            SystemClock.elapsedRealtime() - start
        }.sorted()
        val median = durations[durations.size / 2]
        val p95 = durations[(durations.size * 0.95).toInt() - 1]
        // Only timing summaries are logged. The paired credential never leaves this process.
        Log.i("CompanionValidation", "status_requests=20 median_ms=$median p95_ms=$p95 max_ms=${durations.last()}")
        assertTrue("Status requests exceeded the five-second response budget.", p95 < 5_000)
    }
}
