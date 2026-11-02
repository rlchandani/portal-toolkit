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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.portalinstaller.data.companion.CompanionClient
import com.portalinstaller.data.companion.CompanionConnection
import com.portalinstaller.data.companion.CompanionDevice
import com.portalinstaller.data.companion.CompanionRepository
import com.portalinstaller.data.companion.CompanionRequest
import com.portalinstaller.data.companion.CompanionResponse
import com.portalinstaller.data.companion.CompanionSession
import com.portalinstaller.data.companion.CompanionSessionStore
import com.portalinstaller.data.companion.CompanionTransport
import com.portalinstaller.viewmodel.RemoteTextController
import java.io.File
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Rendered RemoteControlsPanel -> RemoteTextController -> real repository/client.
 * Sessions and transport stay in memory; no sockets, saved pairings or Portal input.
 * QA_FONT_SCALE accepts 1.0/1.3 or 100/130 and checks the actual system font.
 * Set the owned emulator's font_scale first: the keyboard sheet is a separate window.
 */
@RunWith(AndroidJUnit4::class)
class CompanionTextAcknowledgmentUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val visible = mutableStateOf(true)
    private val playbackRevision = mutableStateOf(0)
    private var contentSet = false
    private lateinit var ownerJob: Job
    private lateinit var repository: CompanionRepository
    private lateinit var controller: RemoteTextController
    private lateinit var server: FixtureServer

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
        runBlocking { withTimeout(5_000) { ownerJob.cancelAndJoin() } }
    }

    @Test fun ambiguousTextAcknowledgmentsKeepDraftAndKeyboardWithoutAutomaticRetry() {
        showFixture(Reply.MISSING_APPLIED)
        openKeyboard()
        val drafts = mutableListOf<String>()
        for (reply in listOf(Reply.MISSING_APPLIED, Reply.STRING_TRUE, Reply.STRING_FALSE,
            Reply.STRING_OK, Reply.MALFORMED_JSON, Reply.EMPTY_204)) {
            val draft = "Synthetic text ${reply.name}"
            server.reply(reply)
            field().performScrollTo().performTextReplacement(draft)
            drafts += draft
            sendAndAwait(drafts.size)

            assertDraftRetained(draft)
            compose.runOnIdle {
                assertEquals("text_unconfirmed", repository.state.value.error?.code)
                assertEquals(reply.response.status, repository.state.value.error?.status)
            }
            assertTextPosts(drafts)
            if (reply == Reply.MISSING_APPLIED) capture("text-unconfirmed")
            settleWithoutResend()
            assertDraftRetained(draft)
            compose.runOnIdle { assertEquals("text_unconfirmed", repository.state.value.error?.code) }
            assertTextPosts(drafts)
        }
    }

    @Test fun literalTrueTextAcknowledgmentClearsDraftAndClosesKeyboard() {
        showFixture(Reply.CONFIRMED)
        openKeyboard()
        val draft = "Synthetic confirmed text"
        field().performTextInput(draft)
        sendAndAwait(1)

        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithContentDescription("Close keyboard").assertDoesNotExist()
        compose.onNodeWithText(DRAFT_WARNING).assertDoesNotExist()
        compose.runOnIdle {
            val text = controller.state.value
            assertEquals("", text.draft)
            assertFalse(text.keyboardOpen)
            assertFalse(text.error)
            assertFalse(text.sending)
            assertNull(repository.state.value.error)
            assertEquals(true, repository.state.value.lastResult?.opt("ok"))
            assertEquals(true, repository.state.value.lastResult?.opt("applied"))
            assertEquals("set", repository.state.value.lastResult?.opt("mode"))
        }
        assertTextPosts(listOf(draft))
        settleWithoutResend()
        capture("text-confirmed")
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        openKeyboard()
        assertDraft("")
        compose.onNodeWithText("Send text").performScrollTo().assertIsNotEnabled()
        assertTextPosts(listOf(draft))
    }

    @Test fun literalFalseTextRejectionsRetainDraftAndExistingRepositoryErrors() {
        showFixture(Reply.NOT_APPLIED)
        openKeyboard()
        val drafts = mutableListOf<String>()
        for ((reply, errorCode) in listOf(Reply.NOT_APPLIED to "not_applied",
            Reply.REJECTED to "no_accessibility")) {
            val draft = "Synthetic rejected text ${reply.name}"
            server.reply(reply)
            field().performScrollTo().performTextReplacement(draft)
            drafts += draft
            sendAndAwait(drafts.size)

            assertDraftRetained(draft)
            compose.runOnIdle {
                assertEquals(errorCode, repository.state.value.error?.code)
                assertEquals(200, repository.state.value.error?.status)
                assertNull(repository.state.value.lastResult)
            }
            assertTextPosts(drafts)
            settleWithoutResend()
            assertDraftRetained(draft)
            compose.runOnIdle { assertEquals(errorCode, repository.state.value.error?.code) }
            assertTextPosts(drafts)
        }
    }

    private fun sendAndAwait(expectedPosts: Int) {
        compose.onNodeWithText("Send text").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(5_000) {
            server.posts().size >= expectedPosts && !controller.state.value.sending
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse(repository.state.value.busy)
            assertEquals(CompanionConnection.CONNECTED, repository.state.value.connection)
        }
    }

    private fun assertDraftRetained(draft: String) {
        compose.onNodeWithText(DRAFT_WARNING).performScrollTo().assertIsDisplayed()
        assertDraft(draft)
        compose.onNodeWithContentDescription("Close keyboard").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Send text").performScrollTo().assertIsEnabled()
        compose.runOnIdle {
            assertEquals(draft, controller.state.value.draft)
            assertTrue(controller.state.value.keyboardOpen)
            assertTrue(controller.state.value.error)
            assertFalse(controller.state.value.sending)
            assertNull(repository.state.value.lastResult)
        }
    }

    private fun assertDraft(draft: String) {
        field().performScrollTo().assertIsDisplayed().assert(
            SemanticsMatcher("Editable text exactly matches the draft") {
                it.config.getOrNull(SemanticsProperties.EditableText)?.text == draft
            })
    }

    private fun openKeyboard() =
        compose.onNodeWithText("Type on Portal").performScrollTo().performClick()

    private fun field() = compose.onNodeWithTag("remote-text-input")

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scale = InstrumentationRegistry.getArguments().getString("QA_FONT_SCALE") ?: "device"
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(instrumentation.targetContext.externalCacheDir, "$name-$scale.png")
                .outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun settleWithoutResend() {
        lateinit var refresh: Job
        compose.runOnIdle { refresh = repository.refreshStatus() }
        compose.waitUntil(5_000) { refresh.isCompleted }
        compose.runOnIdle {
            assertFalse("Fixture refresh was cancelled", refresh.isCancelled)
            playbackRevision.value++
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun assertTextPosts(drafts: List<String>) {
        val posts = server.posts()
        assertEquals("Exactly one POST per rendered send", drafts.size, posts.size)
        assertEquals(drafts, posts.map { request ->
            assertEquals("$ENDPOINT/remote/text", request.url)
            val body = JSONObject(request.body!!)
            assertEquals(setOf("text", "mode"), body.keys().asSequence().toSet())
            assertEquals("set", body.get("mode"))
            assertTrue(body.get("text") is String)
            body.getString("text")
        })
        assertEquals(emptyList<String>(), server.rejections())
    }

    private fun showFixture(reply: Reply) {
        server = FixtureServer(reply)
        compose.activityRule.scenario.onActivity {
            ownerJob = SupervisorJob()
            val scope = CoroutineScope(ownerJob + Dispatchers.Main.immediate)
            repository = CompanionRepository(CompanionClient(server), MemoryStore(), scope,
                preloadManagement = false)
            controller = RemoteTextController(scope) { deviceKey, text ->
                val selected = repository.state.value
                if (deviceKey != "${selected.activeDeviceId}:${selected.sessionGeneration}" ||
                    !selected.connected) false
                else repository.sendTextConfirmed(text)
            }
        }
        compose.waitUntil(5_000) { repository.ready.isCompleted }
        lateinit var selection: Job
        compose.activityRule.scenario.onActivity { selection = repository.selectDevice(DEVICE_ID) }
        compose.waitUntil(5_000) { selection.isCompleted }
        compose.activityRule.scenario.onActivity {
            assertFalse("Fixture selection was cancelled", selection.isCancelled)
            assertTrue(repository.state.value.connected)
            assertNull(repository.state.value.error)
            val selected = repository.state.value
            controller.updateSession("${selected.activeDeviceId}:${selected.sessionGeneration}", true)
        }

        val requestedScale = InstrumentationRegistry.getArguments().getString("QA_FONT_SCALE")
            ?.removeSuffix("%")?.toFloat()?.let { if (it == 100f || it == 130f) it / 100f else it }
        require(requestedScale == null || requestedScale == 1f || requestedScale == 1.3f)
        require(requestedScale == null ||
            requestedScale == compose.activity.resources.configuration.fontScale) {
            "Set the owned emulator's system font_scale before testing the keyboard sheet."
        }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides
                Density(density.density, requestedScale ?: density.fontScale)) {
                MaterialTheme {
                    if (visible.value) {
                        val selected by repository.state.collectAsState()
                        val key = "${selected.activeDeviceId}:${selected.sessionGeneration}"
                        LaunchedEffect(key, selected.connected) {
                            controller.updateSession(key, selected.connected)
                        }
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            RemoteControlsPanel(key, selected.connected,
                                onNavigate = {}, onSlideshow = {}, onMove = { _, _ -> },
                                onTap = {}, onScroll = {}, textController = controller,
                                playback = { Text("Playback fixture ${playbackRevision.value}") })
                        }
                    }
                }
            }
        }
        contentSet = true
        compose.waitForIdle()
        assertTextPosts(emptyList())
    }

    private enum class Reply(val response: CompanionResponse) {
        MISSING_APPLIED(CompanionResponse(200, """{"ok":true,"mode":"set"}""")),
        STRING_TRUE(CompanionResponse(200, """{"ok":true,"applied":"true","mode":"set"}""")),
        STRING_FALSE(CompanionResponse(200, """{"ok":true,"applied":"false","mode":"set"}""")),
        STRING_OK(CompanionResponse(200, """{"ok":"true","applied":true,"mode":"set"}""")),
        MALFORMED_JSON(CompanionResponse(200, "<html>Synthetic incomplete result</html>")),
        EMPTY_204(CompanionResponse(204, "")),
        CONFIRMED(CompanionResponse(200, """{"ok":true,"applied":true,"mode":"set"}""")),
        NOT_APPLIED(CompanionResponse(200, """{"ok":true,"applied":false,"mode":"set"}""")),
        REJECTED(CompanionResponse(200,
            """{"ok":false,"error":"no_accessibility","applied":false,"mode":"set"}""")),
    }

    private class MemoryStore : CompanionSessionStore {
        override suspend fun load() = listOf(CompanionSession(
            CompanionDevice(DEVICE_ID, DEVICE_NAME, ENDPOINT), FIXTURE_TOKEN))
        override suspend fun save(sessions: List<CompanionSession>) {
            error("This fixture must not replace pairing records")
        }
        override suspend fun loadSelectedIds() = setOf(DEVICE_ID)
        override suspend fun saveSelectedIds(ids: Set<String>) {
            error("This fixture must not change target selection")
        }
    }

    private class FixtureServer(initialReply: Reply) : CompanionTransport {
        private val lock = Any()
        private var response = initialReply.response
        private val calls = mutableListOf<CompanionRequest>()
        private val rejected = mutableListOf<String>()

        fun reply(reply: Reply) = synchronized(lock) { response = reply.response }
        fun posts() = synchronized(lock) { calls.filter { it.method == "POST" } }
        fun rejections() = synchronized(lock) { rejected.toList() }

        override suspend fun execute(request: CompanionRequest): CompanionResponse =
            synchronized(lock) {
                calls += request
                val url = request.url.toHttpUrl()
                if (url.host != "text-fixture.invalid" || url.scheme != "http" ||
                    url.port != 8723 || url.querySize != 0 || request.sessionToken != FIXTURE_TOKEN ||
                    request.binary || request.upload != null) return@synchronized reject("transport")
                when {
                    request.method == "GET" && request.body == null &&
                        url.encodedPath == "/remote/companion" ->
                        CompanionResponse(200, JSONObject().put("ok", true)
                            .put("deviceId", DEVICE_ID).put("name", DEVICE_NAME)
                            .put("protocolVersion", 1).put("capabilities", JSONObject()).toString())
                    request.method == "GET" && request.body == null &&
                        url.encodedPath == "/remote/nowplaying" ->
                        CompanionResponse(200, """{"ok":true,"np":{"active":false}}""")
                    request.method == "POST" && url.encodedPath == "/remote/text" -> response
                    else -> reject("request")
                }
            }

        private fun reject(reason: String): CompanionResponse {
            rejected += reason
            return CompanionResponse(400, JSONObject().put("ok", false)
                .put("error", "fixture_$reason").toString())
        }
    }

    private companion object {
        const val DEVICE_ID = "text-fixture"
        const val DEVICE_NAME = "Text fixture"
        const val ENDPOINT = "http://text-fixture.invalid:8723"
        const val FIXTURE_TOKEN = "synthetic-text-fixture-token"
        const val DRAFT_WARNING = "Couldn’t confirm sending. Your draft is kept."
    }
}
