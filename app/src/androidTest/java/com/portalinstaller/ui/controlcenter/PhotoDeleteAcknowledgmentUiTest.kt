/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.controlcenter

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.portalinstaller.data.companion.CompanionClient
import com.portalinstaller.data.companion.CompanionConnection
import com.portalinstaller.data.companion.CompanionDevice
import com.portalinstaller.data.companion.CompanionRepository
import com.portalinstaller.data.companion.CompanionRequest
import com.portalinstaller.data.companion.CompanionResponse
import com.portalinstaller.data.companion.CompanionSession
import com.portalinstaller.data.companion.CompanionSessionStore
import com.portalinstaller.data.companion.CompanionTransport
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Rendered PhotoLibraryPanel -> repository -> client regression tests with an in-memory
 * transport. Repository error text is rendered beside the actual panel; this is not a
 * whole-app error-presentation test. No sockets, saved pairings, or user media are used.
 */
@RunWith(AndroidJUnit4::class)
class PhotoDeleteAcknowledgmentUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val visible = mutableStateOf(true)
    private var contentSet = false
    private lateinit var repositoryJob: Job
    private lateinit var repository: CompanionRepository
    private lateinit var server: FixtureServer
    private var deletion: Job? = null

    @Before fun awake() {
        compose.activityRule.scenario.onActivity {
            it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    @After fun closeFixture() {
        if (!::repository.isInitialized) return
        compose.activityRule.scenario.onActivity { visible.value = false }
        if (contentSet) compose.waitForIdle()
        compose.activityRule.scenario.onActivity { repository.close() }
        runBlocking { withTimeout(5_000) { repositoryJob.cancelAndJoin() } }
    }

    // Losing the successful refresh, repeating DELETE, or rejecting a literal ack fails.
    @Test fun literalTrueAndExactPathRefreshOnceAndClearTheRenderedPhoto() {
        showFixture(CompanionResponse(200, """{"ok":true,"path":"$PHOTO_PATH"}"""))

        confirmRenderedDelete()

        compose.onNodeWithText("0 photos").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("No photos found").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(PHOTO_NAME).assertDoesNotExist()
        compose.onNodeWithText(REVIEW_WARNING).assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(repository.state.value.photos.isEmpty())
            assertNull(repository.state.value.error)
            assertEquals(CompanionConnection.CONNECTED, repository.state.value.connection)
        }
        assertPhotoRequests(refreshed = true)
    }

    // Accepting ok:true without matching the exact deleted path would refresh away the tile.
    @Test fun wrongPathAcknowledgmentWarnsAndRetainsPhotoWithoutAutomaticRetry() {
        showFixture(CompanionResponse(200,
            """{"ok":true,"path":"/sdcard/Pictures/Fixture/other.png"}"""))

        confirmRenderedDelete()

        assertUnconfirmedDeletion()
    }

    // JSONObject.optBoolean coercion must not turn a string into deletion confirmation.
    @Test fun stringTrueAcknowledgmentWarnsAndRetainsPhotoWithoutAutomaticRetry() {
        showFixture(CompanionResponse(200, """{"ok":"true","path":"$PHOTO_PATH"}"""))

        confirmRenderedDelete()

        assertUnconfirmedDeletion()
    }

    // A delete-specific guard must preserve the server's explicit authentication failure.
    @Test fun explicit401RemainsUnauthorizedAndRetainsTheRenderedPhoto() {
        showFixture(CompanionResponse(401, """{"ok":false,"error":"unauthorized"}"""))

        confirmRenderedDelete()

        compose.onNodeWithText("Pair this phone with the Portal again.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(REVIEW_WARNING).assertDoesNotExist()
        assertPhotoRetained()
        compose.runOnIdle {
            val state = repository.state.value
            assertEquals("unauthorized", state.error?.code)
            assertEquals(401, state.error?.status)
            assertEquals(CompanionConnection.UNAUTHORIZED, state.connection)
        }
        assertPhotoRequests(refreshed = false)
    }

    private fun assertUnconfirmedDeletion() {
        compose.onNodeWithText(REVIEW_WARNING).performScrollTo().assertIsDisplayed()
        assertPhotoRetained()
        compose.runOnIdle {
            assertEquals("delete_unconfirmed", repository.state.value.error?.code)
            assertEquals(REVIEW_WARNING, repository.state.value.error?.message)
            assertEquals(CompanionConnection.CONNECTED, repository.state.value.connection)
        }
        assertPhotoRequests(refreshed = false)
    }

    private fun assertPhotoRetained() {
        compose.onNodeWithText("1 photo").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(PHOTO_NAME).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("No photos found").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(PHOTO_PATH), repository.state.value.photos.map { it.path })
            assertFalse(repository.state.value.busy)
        }
    }

    private fun confirmRenderedDelete() {
        compose.onNodeWithContentDescription(PHOTO_NAME).performScrollTo()
            .assertIsDisplayed().performClick()
        clickDialog("Delete photo")
        compose.onNodeWithText("Delete this photo from the Portal?").assertIsDisplayed()
        // Opening either dialog is non-destructive; only the actual confirmation sends.
        assertEquals(0, server.requests().count { it.method == "DELETE" })
        clickDialog("Delete")
        compose.waitUntil(5_000) { deletion?.isCompleted == true }
        compose.waitForIdle()
        compose.onNodeWithText("Delete this photo from the Portal?").assertDoesNotExist()
        compose.runOnIdle {
            assertFalse("Delete fixture job was cancelled", deletion!!.isCancelled)
        }
        // Let the rendered result settle without pressing Refresh or retrying the deletion.
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun clickDialog(text: String) {
        val button = compose.onNode(
            hasText(text, substring = false) and hasAnyAncestor(isDialog()))
        // The dialog semantics can exist before Android finishes placing its window.
        compose.waitUntil(5_000) {
            runCatching { button.assertIsDisplayed(); true }.getOrDefault(false)
        }
        button.performClick()
    }

    private fun assertPhotoRequests(refreshed: Boolean) {
        val requests = server.requests()
        val photoRequests = requests.filter {
            it.url.toHttpUrl().encodedPath in setOf("/remote/photo", "/remote/photos")
        }
        assertEquals(
            if (refreshed) listOf("GET", "DELETE", "GET") else listOf("GET", "DELETE"),
            photoRequests.map { it.method })
        val delete = requests.single { it.method == "DELETE" }
        assertEquals("/remote/photo", delete.url.toHttpUrl().encodedPath)
        assertEquals(PHOTO_PATH, delete.url.toHttpUrl().queryParameter("path"))
        assertEquals(setOf("path"), delete.url.toHttpUrl().queryParameterNames)
        assertNull(delete.body)
        assertEquals(if (refreshed) 2 else 1, photoRequests.count { it.method == "GET" })
    }

    private fun awaitJob(start: () -> Job) {
        lateinit var job: Job
        compose.runOnIdle { job = start() }
        compose.waitUntil(5_000) { job.isCompleted }
        compose.runOnIdle { assertFalse("Fixture job was cancelled", job.isCancelled) }
    }

    private fun showFixture(deleteResponse: CompanionResponse) {
        server = FixtureServer(deleteResponse)
        compose.activityRule.scenario.onActivity {
            repositoryJob = SupervisorJob()
            repository = CompanionRepository(CompanionClient(server), MemoryStore(),
                CoroutineScope(repositoryJob + Dispatchers.Main.immediate),
                preloadManagement = false)
        }
        val thumbnail: suspend (String) -> ByteArray? = { path ->
            check(path == PHOTO_PATH)
            server.png
        }
        compose.setContent {
            MaterialTheme {
                if (visible.value) {
                    val state by repository.state.collectAsState()
                    Column(Modifier.width(360.dp).fillMaxHeight()
                        .verticalScroll(rememberScrollState())) {
                        state.error?.let { Text(it.message) }
                        PhotoLibraryPanel(
                            deviceId = state.activeDeviceId ?: DEVICE_ID,
                            files = JSONArray().apply { state.photos.forEach { put(it.raw) } },
                            folder = state.photoFolder,
                            working = state.busy || !state.connected,
                            uploadDetail = null,
                            onImport = {},
                            onRefresh = { repository.listPhotos() },
                            onChangeFolder = {},
                            onActivateFolder = {},
                            onDelete = { deletion = repository.deletePhoto(it) },
                            loadThumbnail = thumbnail,
                            listingLoaded = state.photoListingLoaded,
                            listingError = state.photoListingError,
                            showImport = false,
                        )
                    }
                }
            }
        }
        contentSet = true
        compose.waitUntil(5_000) { repository.ready.isCompleted }
        awaitJob { repository.selectDevice(DEVICE_ID) }
        awaitJob { repository.listPhotos() }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasContentDescription(PHOTO_NAME))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle {
            assertTrue(repository.state.value.connected)
            assertNull(repository.state.value.error)
            assertEquals(listOf(PHOTO_PATH), repository.state.value.photos.map { it.path })
        }
        assertEquals(1, server.requests().count { it.url.toHttpUrl().encodedPath == "/remote/photos" })
    }

    /** Every persistence operation stays in memory, including listing folder metadata. */
    private class MemoryStore : CompanionSessionStore {
        private var sessions = listOf(CompanionSession(
            CompanionDevice(DEVICE_ID, "Fixture Portal", ENDPOINT,
                photoFolder = PHOTO_FOLDER, photoBrowseAll = false), TOKEN))
        override suspend fun load() = sessions
        override suspend fun save(sessions: List<CompanionSession>) { this.sessions = sessions.toList() }
        override suspend fun loadSelectedIds() = setOf(DEVICE_ID)
        override suspend fun saveSelectedIds(ids: Set<String>) {
            check(ids == setOf(DEVICE_ID))
        }
    }

    private class FixtureServer(private val deleteResponse: CompanionResponse) : CompanionTransport {
        private val lock = Any()
        private val calls = mutableListOf<CompanionRequest>()
        private var photoPresent = true
        val png = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).let { bitmap ->
            try {
                bitmap.eraseColor(0xff176abb.toInt())
                ByteArrayOutputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    it.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        }
        fun requests() = synchronized(lock) { calls.toList() }

        override suspend fun execute(request: CompanionRequest): CompanionResponse = synchronized(lock) {
            calls += request
            val url = request.url.toHttpUrl()
            check(url.scheme == "http" && url.host == "photo-fixture.invalid" && url.port == 8723)
            check(request.sessionToken == TOKEN && request.body == null &&
                request.upload == null && !request.binary)
            when {
                request.method == "GET" && url.encodedPath == "/remote/companion" && url.querySize == 0 ->
                    success(JSONObject().put("deviceId", DEVICE_ID).put("name", "Fixture Portal")
                        .put("protocolVersion", 1).put("capabilities", JSONObject().put("photos", true)))
                request.method == "GET" && url.encodedPath == "/remote/nowplaying" && url.querySize == 0 ->
                    success(JSONObject().put("np", JSONObject().put("active", false)))
                request.method == "GET" && url.encodedPath == "/remote/photos" -> {
                    check(url.queryParameterNames == setOf("path") &&
                        url.queryParameter("path") == PHOTO_FOLDER)
                    val files = JSONArray()
                    if (photoPresent) files.put(JSONObject().put("name", PHOTO_NAME)
                        .put("path", PHOTO_PATH).put("size", png.size).put("modified", 1))
                    success(JSONObject().put("path", PHOTO_FOLDER).put("files", files))
                }
                request.method == "DELETE" && url.encodedPath == "/remote/photo" -> {
                    check(url.queryParameterNames == setOf("path") &&
                        url.queryParameter("path") == PHOTO_PATH)
                    // A subsequent listing is empty for every 2xx response. An unconfirmed
                    // ack must retain the repository's photo instead of refreshing it away.
                    if (deleteResponse.status in 200..299) photoPresent = false
                    deleteResponse
                }
                else -> error("Unsupported fixture request: ${request.method} ${url.encodedPath}")
            }
        }

        private fun success(json: JSONObject) =
            CompanionResponse(200, json.put("ok", true).toString())
    }

    private companion object {
        const val DEVICE_ID = "photo-fixture"
        const val ENDPOINT = "http://photo-fixture.invalid:8723"
        const val TOKEN = "synthetic-photo-fixture-token"
        const val PHOTO_FOLDER = "/sdcard/Pictures/Fixture"
        const val PHOTO_NAME = "Fixture photo.png"
        const val PHOTO_PATH = "/sdcard/Pictures/Fixture/fixture.png"
        const val REVIEW_WARNING = "The Portal did not confirm this deletion. Refresh and review your photos before retrying."
    }
}
