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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.portalinstaller.data.companion.CompanionClient
import com.portalinstaller.data.companion.CompanionDevice
import com.portalinstaller.data.companion.CompanionRepository
import com.portalinstaller.data.companion.CompanionRequest
import com.portalinstaller.data.companion.CompanionResponse
import com.portalinstaller.data.companion.CompanionSession
import com.portalinstaller.data.companion.CompanionSessionStore
import com.portalinstaller.data.companion.CompanionTransport
import com.portalinstaller.data.companion.CompanionUpload
import com.portalinstaller.data.companion.GroupWorkStatus
import com.portalinstaller.data.companion.PhotoUploadSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual GroupProgressPanel -> repository -> client; generated media and memory-only sessions. */
@RunWith(AndroidJUnit4::class)
class PhotoUploadAcknowledgmentUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val visible = mutableStateOf(true)
    private var contentSet = false
    private lateinit var repositoryJob: Job
    private lateinit var repository: CompanionRepository
    private lateinit var server: FixtureServer
    private lateinit var source: FixtureSource
    private var work: Job? = null

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

    @Test fun exactAcknowledgmentForAAndWrongPathForBRenderUnconfirmedWithoutRetry() {
        showFixture(Reply.WRONG_PATH)
        sendFixture()

        assertUnconfirmed()
        assertAttempts(listOf(A, B))
    }

    @Test fun explicit409RendersFailedAndRetryButtonResendsOnlyB() {
        showFixture(Reply.CONFLICT)
        sendFixture()

        displayed("1 confirmed · 1 failed · 0 cancelled")
        displayed("Room A · $PHOTO_NAME: Confirmed", substring = true)
        displayed("Room B · $PHOTO_NAME: Failed", substring = true)
        compose.onNodeWithText(REVIEW_WARNING).assertDoesNotExist()
        compose.onNodeWithText("unconfirmed", substring = true).assertDoesNotExist()
        compose.runOnIdle {
            val operation = repository.state.value.groupOperations.single()
            assertEquals(listOf(GroupWorkStatus.SUCCEEDED, GroupWorkStatus.FAILED),
                operation.items.map { it.status })
            assertTrue(operation.retryable)
        }
        assertAttempts(listOf(A, B))

        // The retry ledger owns captured destinations even after current selection changes.
        awaitJob { repository.selectTargets(setOf(A)) }
        server.bReply = Reply.CONFIRMED
        displayed("Retry failed only")
        compose.onNodeWithText("Retry failed only").performClick()
        awaitWork()

        displayed("2 confirmed · 0 failed · 0 cancelled")
        displayed("Room B · $PHOTO_NAME: Confirmed", substring = true)
        compose.onNodeWithText("Retry failed only").assertDoesNotExist()
        compose.onNodeWithText("unconfirmed", substring = true).assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(repository.state.value.groupOperations.single().items.all {
                it.status == GroupWorkStatus.SUCCEEDED
            })
        }
        assertAttempts(listOf(A, B, B))
    }

    @Test fun unreadablePositiveReplyRendersUnconfirmedAndClosesEveryPreparedStream() {
        showFixture(Reply.UNREADABLE)
        sendFixture()

        assertUnconfirmed()
        assertAttempts(listOf(A, B))
        // Settling the rendered result cannot start a second upload or preparation.
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertAttempts(listOf(A, B))
        compose.onNodeWithText("Retry failed only").assertDoesNotExist()
    }

    private fun assertUnconfirmed() {
        displayed("1 confirmed · 0 failed · 0 cancelled · 1 unconfirmed")
        displayed("Room A · $PHOTO_NAME: Confirmed", substring = true)
        displayed("Room B · $PHOTO_NAME: Unconfirmed", substring = true)
        displayed(REVIEW_WARNING)
        compose.onNodeWithText("Retry failed only").assertDoesNotExist()
        compose.runOnIdle {
            val operation = repository.state.value.groupOperations.single()
            assertEquals(listOf(GroupWorkStatus.SUCCEEDED, GroupWorkStatus.UNCONFIRMED),
                operation.items.map { it.status })
            assertEquals(REVIEW_WARNING, operation.items.single { it.targetId == B }.detail)
            assertFalse(operation.running)
            assertFalse(operation.retryable)
            assertNull(repository.state.value.error)
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun displayed(text: String, substring: Boolean = false) {
        val node = compose.onNodeWithText(text, substring = substring)
        // Semantics may appear before Android places the isolated activity's window.
        compose.waitUntil(5_000) {
            runCatching { node.performScrollTo().assertIsDisplayed(); true }.getOrDefault(false)
        }
    }

    private fun sendFixture() {
        displayed("Send fixture")
        assertTrue(server.requests().isEmpty())
        compose.onNodeWithText("Send fixture").performClick()
        awaitWork()
    }

    private fun awaitWork() {
        compose.waitUntil(5_000) { work?.isCompleted == true }
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse("Fixture job was cancelled", work!!.isCancelled)
            assertFalse(repository.state.value.groupOperations.single().running)
        }
    }

    private fun awaitJob(start: () -> Job) {
        lateinit var job: Job
        compose.runOnIdle { job = start() }
        compose.waitUntil(5_000) { job.isCompleted }
        compose.runOnIdle { assertFalse("Fixture job was cancelled", job.isCancelled) }
    }

    private fun showFixture(reply: Reply) {
        source = FixtureSource()
        server = FixtureServer(reply, source)
        compose.activityRule.scenario.onActivity {
            repositoryJob = SupervisorJob()
            repository = CompanionRepository(CompanionClient(server), MemoryStore(),
                CoroutineScope(repositoryJob + Dispatchers.Main.immediate),
                preloadManagement = false)
        }
        compose.waitUntil(5_000) { repository.ready.isCompleted }
        val targets = repository.captureSelectedTargets()
        compose.runOnIdle {
            assertEquals(setOf(A, B), repository.state.value.selectedTargetIds)
            assertNull(repository.state.value.error)
        }
        compose.setContent {
            MaterialTheme {
                if (visible.value) {
                    val state by repository.state.collectAsState()
                    Column(Modifier.width(360.dp).fillMaxHeight()
                        .verticalScroll(rememberScrollState())) {
                        Button(onClick = {
                            work = repository.uploadSelectedPhotos(listOf(source.source), targets)
                        }, enabled = state.groupOperations.isEmpty()) { Text("Send fixture") }
                        GroupProgressPanel(
                            operations = state.groupOperations,
                            onRetry = { work = repository.retryGroupOperation(it) },
                            onCancel = { repository.cancelGroupOperation(it) },
                            onDismiss = { repository.dismissGroupOperation(it) },
                        )
                    }
                }
            }
        }
        contentSet = true
        displayed("Send fixture")
    }

    private fun assertAttempts(expectedRooms: List<String>) {
        val requests = server.requests()
        val uploads = requests.filter { it.method == "POST" }
        assertEquals(expectedRooms.sorted(), uploads.map { it.url.toHttpUrl().host }.sorted())
        assertEquals(expectedRooms.size, requests.count {
            it.method == "GET" && it.url.toHttpUrl().encodedPath == "/remote/companion"
        })
        assertEquals(expectedRooms.size * 2, requests.size)
        source.assertResources(expectedRooms)
    }

    private enum class Reply { WRONG_PATH, CONFLICT, UNREADABLE, CONFIRMED }

    private class MemoryStore : CompanionSessionStore {
        private var sessions = listOf(session(A), session(B))
        private var selected = setOf(A, B)
        override suspend fun load() = sessions.toList()
        override suspend fun save(sessions: List<CompanionSession>) {
            this.sessions = sessions.toList()
        }
        override suspend fun loadSelectedIds() = selected.toSet()
        override suspend fun saveSelectedIds(ids: Set<String>) {
            check(ids.all { it in setOf(A, B) })
            selected = ids.toSet()
        }
    }

    /** Each preparation owns a distinct upload, stream, and disposal callback. */
    private class FixtureSource {
        val png = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).let { bitmap ->
            try {
                bitmap.eraseColor(0xff176abb.toInt())
                ByteArrayOutputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    it.toByteArray()
                }
            } finally { bitmap.recycle() }
        }
        private class Attempt {
            lateinit var upload: CompanionUpload
            var room: String? = null
            val opened = AtomicInteger()
            val closed = AtomicInteger()
            val disposed = AtomicInteger()
        }
        private val lock = Any()
        private val attempts = mutableListOf<Attempt>()
        val source = PhotoUploadSource(PHOTO_NAME) {
            val attempt = Attempt()
            attempt.upload = CompanionUpload(PHOTO_NAME, "image/png", png.size.toLong(),
                dispose = { check(attempt.disposed.incrementAndGet() == 1) },
                open = {
                    check(attempt.opened.incrementAndGet() == 1)
                    object : ByteArrayInputStream(png) {
                        override fun close() {
                            check(attempt.closed.incrementAndGet() == 1)
                            super.close()
                        }
                    }
                })
            synchronized(lock) { attempts += attempt }
            attempt.upload
        }
        fun assign(upload: CompanionUpload, room: String) = synchronized(lock) {
            val attempt = attempts.single { it.upload === upload }
            check(attempt.room == null)
            attempt.room = room
        }
        fun assertResources(expectedRooms: List<String>) = synchronized(lock) {
            assertEquals(expectedRooms.sorted(), attempts.map { it.room!! }.sorted())
            attempts.forEach {
                assertEquals(1, it.opened.get())
                assertEquals(1, it.closed.get())
                assertEquals(1, it.disposed.get())
            }
        }
    }

    private class FixtureServer(reply: Reply, private val source: FixtureSource) : CompanionTransport {
        @Volatile var bReply = reply
        private val lock = Any()
        private val calls = mutableListOf<CompanionRequest>()
        fun requests() = synchronized(lock) { calls.toList() }
        override suspend fun execute(request: CompanionRequest): CompanionResponse = synchronized(lock) {
            val url = request.url.toHttpUrl()
            val room = url.host
            check(url.scheme == "http" && room in setOf(A, B) && url.port == 8723)
            check(request.sessionToken == token(room) && request.body == null && !request.binary)
            calls += request
            when {
                request.method == "GET" && url.encodedPath == "/remote/companion" -> {
                    check(url.querySize == 0 && request.upload == null && request.onUploadProgress == null)
                    CompanionResponse(200, JSONObject().put("ok", true).put("deviceId", room)
                        .put("protocolVersion", 1).put("capabilities", JSONObject().put("photos", true))
                        .toString())
                }
                request.method == "POST" && url.encodedPath == "/remote/photo-upload" -> {
                    check(url.queryParameterNames == setOf("path", "name"))
                    check(url.queryParameter("path") == folder(room))
                    check(url.queryParameter("name") == PHOTO_NAME)
                    val upload = checkNotNull(request.upload)
                    check(upload.name == PHOTO_NAME && upload.contentType == "image/png" &&
                        upload.length == source.png.size.toLong())
                    source.assign(upload, room)
                    upload.open().use { input ->
                        val bytes = input.readBytes()
                        assertArrayEquals(source.png, bytes)
                        checkNotNull(request.onUploadProgress).invoke(bytes.size.toLong())
                    }
                    when (if (room == A) Reply.CONFIRMED else bReply) {
                        Reply.CONFLICT -> CompanionResponse(409, """{"ok":false,"error":"conflict"}""")
                        Reply.UNREADABLE -> CompanionResponse(200, "<html>Upload received</html>")
                        Reply.WRONG_PATH -> acknowledged("${folder(room)}/other.png", upload.length)
                        Reply.CONFIRMED -> acknowledged("${folder(room)}/$PHOTO_NAME", upload.length)
                    }
                }
                else -> error("Unsupported fixture request: ${request.method} ${url.encodedPath}")
            }
        }
        private fun acknowledged(path: String, bytes: Long) =
            CompanionResponse(200, JSONObject().put("ok", true).put("path", path)
                .put("bytes", bytes).toString())
    }

    private companion object {
        const val A = "upload-a.invalid"
        const val B = "upload-b.invalid"
        const val PHOTO_NAME = "Fixture upload.png"
        const val REVIEW_WARNING = "Upload completion is unconfirmed. Refresh this Portal's photos before retrying."
        fun token(room: String) = "synthetic-upload-fixture-$room"
        fun folder(room: String) = if (room == A) "/sdcard/Pictures/Fixture A"
            else "/sdcard/Pictures/Fixture B "
        fun session(room: String) = CompanionSession(CompanionDevice(room,
            if (room == A) "Room A" else "Room B", "http://$room:8723",
            photoFolder = folder(room), photoBrowseAll = false), token(room))
    }
}
