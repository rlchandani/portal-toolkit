/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.controlcenter

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.portalinstaller.data.companion.CompanionClient
import com.portalinstaller.data.companion.CompanionDevice
import com.portalinstaller.data.companion.CompanionRepository
import com.portalinstaller.data.companion.CompanionRequest
import com.portalinstaller.data.companion.CompanionResponse
import com.portalinstaller.data.companion.CompanionSession
import com.portalinstaller.data.companion.CompanionSessionStore
import com.portalinstaller.data.companion.CompanionTransport
import com.portalinstaller.data.companion.GroupWorkStatus
import com.portalinstaller.viewmodel.ControlCenterViewModel
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
 * Rendered registry switch -> ViewModel -> repository/client -> GroupProgressPanel.
 * Memory-only sessions and transport; no sockets, saved preferences or live Portal evidence.
 * Requires settings_unconfirmed to retain review wording and exclude failed-only retry.
 */
@RunWith(AndroidJUnit4::class)
class CompanionSettingsAcknowledgmentUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val visible = mutableStateOf(true)
    private val viewModels = ViewModelStore()
    private var contentSet = false
    private lateinit var repositoryJob: Job
    private lateinit var repository: CompanionRepository
    private lateinit var vm: ControlCenterViewModel
    private lateinit var server: FixtureServer
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
        compose.activityRule.scenario.onActivity {
            viewModels.clear()
            repository.close()
        }
        runBlocking { withTimeout(5_000) { repositoryJob.cancelAndJoin() } }
    }

    @Test fun matchingSettingsAcknowledgmentsRenderConfirmed() {
        showFixture(linkedMapOf(A to Reply.CONFIRMED, B to Reply.CONFIRMED))
        changeShuffle()

        displayed("Room A · Screensaver: Confirmed")
        displayed("Room B · Screensaver: Confirmed")
        displayed("2 confirmed · 0 failed · 0 cancelled")
        compose.onNodeWithText("Retry failed only").assertDoesNotExist()
        compose.runOnIdle {
            val operation = vm.state.value.groupOperations.single()
            assertTrue(operation.items.all { it.status == GroupWorkStatus.SUCCEEDED })
            assertTrue(operation.items.all { it.detail == "Confirmed by Portal." })
            assertFalse(operation.running)
            assertFalse(operation.retryable)
            assertNull(vm.state.value.error)
        }
        assertPosts(mapOf(A to 1, B to 1))
    }

    @Test fun wrongAndMissingDomainRepliesRenderUnconfirmedWithoutResend() {
        showFixture(linkedMapOf(A to Reply.CONFIRMED, B to Reply.WRONG_DOMAIN,
            C to Reply.MISSING_DOMAIN))
        changeShuffle()

        displayed("Room A · Screensaver: Confirmed")
        displayed("Room B · Screensaver: Unconfirmed")
        displayed("Room C · Screensaver: Unconfirmed")
        displayed("1 confirmed · 0 failed · 0 cancelled · 2 unconfirmed")
        compose.onNodeWithText("Retry failed only").assertDoesNotExist()
        val operationId = vm.state.value.groupOperations.single().id
        val review = hasText("review", substring = true, ignoreCase = true) and
            hasAnyAncestor(hasTestTag("group-operation-$operationId"))
        compose.waitUntil(5_000) {
            compose.onAllNodes(review).fetchSemanticsNodes().size >= 2
        }
        for (index in 0..1) compose.onAllNodes(review)[index]
            .performScrollTo().assertIsDisplayed()
        val capture = compose.onRoot().captureToImage().asAndroidBitmap()
        InstrumentationRegistry.getInstrumentation().targetContext.filesDir
            .resolve("settings-unconfirmed.png").outputStream().use {
                assertTrue(capture.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        compose.runOnIdle {
            val operation = vm.state.value.groupOperations.single()
            assertEquals(mapOf(A to GroupWorkStatus.SUCCEEDED,
                B to GroupWorkStatus.UNCONFIRMED, C to GroupWorkStatus.UNCONFIRMED),
                operation.items.associate { it.targetId to it.status })
            assertTrue(operation.items.filter { it.targetId != A }.all {
                it.detail?.contains("review", ignoreCase = true) == true
            })
            assertTrue(server.shuffle(B))
            assertTrue(server.shuffle(C))
            assertFalse(operation.running)
            assertFalse(operation.retryable)
            assertNull(vm.state.value.error)
        }
        assertPosts(mapOf(A to 1, B to 1, C to 1))

        // Passive refresh, recomposition and even failed-only retry cannot resend uncertainty.
        awaitJob { vm.refreshSettings() }
        awaitJob { vm.retryGroupOperation(operationId) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertPosts(mapOf(A to 1, B to 1, C to 1))
        displayed("1 confirmed · 0 failed · 0 cancelled · 2 unconfirmed")
        compose.onNodeWithText("Retry failed only").assertDoesNotExist()
    }

    @Test fun literalFalseRejectionRendersFailedAndRetriesOnlyRejectedRoom() {
        showFixture(linkedMapOf(A to Reply.CONFIRMED, B to Reply.REJECTED))
        changeShuffle()

        displayed("Room A · Screensaver: Confirmed")
        displayed("Room B · Screensaver: Failed")
        displayed("1 confirmed · 1 failed · 0 cancelled")
        displayed("Retry failed only")
        compose.onNodeWithText("Unconfirmed", substring = true).assertDoesNotExist()
        compose.runOnIdle {
            val operation = vm.state.value.groupOperations.single()
            assertEquals(mapOf(A to GroupWorkStatus.SUCCEEDED, B to GroupWorkStatus.FAILED),
                operation.items.associate { it.targetId to it.status })
            assertTrue(operation.retryable)
            assertFalse(server.shuffle(B))
        }
        assertPosts(mapOf(A to 1, B to 1))

        // The rendered retry retains B even when the current destination selection is A.
        awaitJob { vm.selectTargets(setOf(A)) }
        server.reply(B, Reply.CONFIRMED)
        compose.onNodeWithText("Retry failed only").performScrollTo().performClick()
        awaitWork()
        displayed("2 confirmed · 0 failed · 0 cancelled")
        displayed("Room B · Screensaver: Confirmed")
        compose.onNodeWithText("Retry failed only").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(vm.state.value.groupOperations.single().items.all {
                it.status == GroupWorkStatus.SUCCEEDED
            })
            assertTrue(server.shuffle(B))
        }
        assertPosts(mapOf(A to 1, B to 2))
    }

    private fun changeShuffle() {
        val toggle = compose.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch) and
                hasAnyAncestor(hasTestTag("registry-shuffle-row")))
        toggle.performScrollTo().assertIsOff().performClick()
        awaitWork()
    }

    private fun awaitWork() {
        compose.waitUntil(5_000) { work?.isCompleted == true }
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse("Fixture job was cancelled", work!!.isCancelled)
            assertFalse(vm.state.value.groupOperations.single().running)
        }
    }

    private fun awaitJob(start: () -> Job) {
        lateinit var job: Job
        compose.runOnIdle { job = start() }
        compose.waitUntil(5_000) { job.isCompleted }
        compose.runOnIdle { assertFalse("Fixture job was cancelled", job.isCancelled) }
    }

    private fun displayed(text: String) {
        compose.waitUntil(5_000) {
            runCatching {
                compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
                true
            }.getOrDefault(false)
        }
    }

    private fun assertPosts(expected: Map<String, Int>) {
        val posts = server.posts()
        assertEquals(expected, posts.groupingBy { it.url.toHttpUrl().host.substringBefore('.') }
            .eachCount())
        posts.forEach {
            val body = JSONObject(it.body!!)
            assertEquals(setOf("domain", "values"), body.keys().asSequence().toSet())
            assertEquals("screensaver", body.getString("domain"))
            val values = body.getJSONObject("values")
            assertEquals(setOf("shuffle"), values.keys().asSequence().toSet())
            assertEquals(true, values.get("shuffle"))
        }
        assertEquals(emptyList<String>(), server.rejections())
    }

    private fun showFixture(replies: Map<String, Reply>) {
        server = FixtureServer(replies)
        compose.activityRule.scenario.onActivity {
            repositoryJob = SupervisorJob()
            repository = CompanionRepository(CompanionClient(server), MemoryStore(replies.keys),
                CoroutineScope(repositoryJob + Dispatchers.Main.immediate),
                preloadManagement = false)
            vm = ViewModelProvider(viewModels, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass == ControlCenterViewModel::class.java)
                    return ControlCenterViewModel(repository) as T
                }
            })[ControlCenterViewModel::class.java]
        }
        compose.setContent {
            MaterialTheme {
                if (visible.value) {
                    val state by vm.state.collectAsState()
                    Column(Modifier.width(360.dp).fillMaxHeight()
                        .verticalScroll(rememberScrollState())) {
                        GroupProgressPanel(state.groupOperations,
                            { work = vm.retryGroupOperation(it) },
                            { vm.cancelGroupOperation(it) }, { vm.dismissGroupOperation(it) })
                        state.schema?.let { schema ->
                            RegistrySettingsPanel(schema,
                                working = state.busy || state.selectedTargets.isEmpty(),
                                query = "", advanced = false, onlyDomain = "screensaver",
                                onApply = { domain, values -> work = vm.writeSettings(domain, values) },
                                activePortalName = state.deviceName,
                                activeWorking = state.busy || !state.connected,
                                onActiveApply = { domain, values ->
                                    work = vm.writeActiveSettings(domain, values)
                                })
                        }
                    }
                }
            }
        }
        contentSet = true
        compose.waitUntil(5_000) { repository.ready.isCompleted }
        awaitJob { vm.selectDevice(A) }
        awaitJob { vm.refreshSettings() }
        compose.runOnIdle {
            assertTrue(vm.state.value.connected)
            assertEquals(replies.keys, vm.state.value.selectedTargetIds)
            assertNull(vm.state.value.error)
        }
        compose.waitForIdle()
    }

    private enum class Reply { CONFIRMED, WRONG_DOMAIN, MISSING_DOMAIN, REJECTED }

    private class MemoryStore(ids: Set<String>) : CompanionSessionStore {
        private val sessions = ids.map { id ->
            CompanionSession(CompanionDevice(id, "Room ${id.uppercase()}",
                "http://$id.invalid:8723"), "fixture-token-$id")
        }
        private var selected = ids.toSet()
        override suspend fun load() = sessions.toList()
        override suspend fun save(sessions: List<CompanionSession>) {
            error("This fixture must not replace pairing records")
        }
        override suspend fun loadSelectedIds() = selected.toSet()
        override suspend fun saveSelectedIds(ids: Set<String>) { selected = ids.toSet() }
    }

    private class FixtureServer(initialReplies: Map<String, Reply>) : CompanionTransport {
        private val lock = Any()
        private val replies = initialReplies.toMutableMap()
        private val values = initialReplies.keys.associateWith { false }.toMutableMap()
        private val calls = mutableListOf<CompanionRequest>()
        private val rejected = mutableListOf<String>()
        fun posts() = synchronized(lock) { calls.filter { it.method == "POST" } }
        fun rejections() = synchronized(lock) { rejected.toList() }
        fun shuffle(id: String) = synchronized(lock) { values.getValue(id) }
        fun reply(id: String, value: Reply) = synchronized(lock) { replies[id] = value }

        override suspend fun execute(request: CompanionRequest): CompanionResponse =
            synchronized(lock) {
                calls += request
                val url = request.url.toHttpUrl()
                val id = url.host.substringBefore('.')
                if (id !in replies || url.host != "$id.invalid" || url.scheme != "http" ||
                    url.port != 8723 || request.sessionToken != "fixture-token-$id" ||
                    request.binary || request.upload != null) return@synchronized reject("transport")
                when {
                    request.method == "GET" && request.body == null &&
                        url.querySize == 0 && url.encodedPath == "/remote/companion" ->
                        success(JSONObject().put("deviceId", id).put("name", "Room ${id.uppercase()}")
                            .put("protocolVersion", 1)
                            .put("capabilities", JSONObject().put("settings", true)))
                    request.method == "GET" && request.body == null &&
                        url.querySize == 0 && url.encodedPath == "/remote/nowplaying" ->
                        success(JSONObject().put("np", JSONObject().put("active", false)))
                    request.method == "GET" && request.body == null &&
                        url.encodedPath == "/remote/settings" -> {
                        val preflight = url.queryParameterNames == setOf("domain", "includeInactive") &&
                            url.queryParameter("domain") == "screensaver" &&
                            url.queryParameter("includeInactive") == "1"
                        if (url.querySize != 0 && !preflight) reject("settings_query")
                        else success(JSONObject().put("settings", JSONObject().put("domains",
                            JSONArray().put(domain(id)))))
                    }
                    request.method == "POST" && url.querySize == 0 &&
                        url.encodedPath == "/remote/settings" -> apply(id, request.body)
                    else -> reject("request")
                }
            }

        private fun apply(id: String, raw: String?): CompanionResponse {
            val body = runCatching { JSONObject(raw ?: "") }.getOrNull()
                ?: return reject("body")
            val patch = body.optJSONObject("values") ?: return reject("values")
            if (body.keys().asSequence().toSet() != setOf("domain", "values") ||
                body.opt("domain") != "screensaver" ||
                patch.keys().asSequence().toSet() != setOf("shuffle") ||
                patch.opt("shuffle") !is Boolean) return reject("patch")
            if (replies.getValue(id) == Reply.REJECTED) {
                return CompanionResponse(200, JSONObject().put("ok", false)
                    .put("error", "validation_failed").put("applied", JSONArray())
                    .put("domain", domain(id)).toString())
            }
            // Positive malformed replies may follow an actual write; refreshing is not a resend.
            values[id] = patch.getBoolean("shuffle")
            val acknowledgment = JSONObject().put("applied", JSONArray().put("shuffle"))
            when (replies.getValue(id)) {
                Reply.CONFIRMED -> acknowledgment.put("domain", domain(id))
                Reply.WRONG_DOMAIN -> acknowledgment.put("domain", JSONObject()
                    .put("id", "home_dashboard").put("title", "Home dashboard")
                    .put("controls", JSONArray().put(JSONObject().put("key", "url")
                        .put("title", "Dashboard address").put("type", "string")
                        .put("value", "https://dashboard.invalid/"))))
                Reply.MISSING_DOMAIN -> Unit
                Reply.REJECTED -> error("Handled before applying")
            }
            return success(acknowledgment)
        }

        private fun domain(id: String) = JSONObject().put("id", "screensaver")
            .put("title", "Screensaver").put("controls", JSONArray()
                .put(JSONObject().put("key", "enabled").put("title", "Screensaver")
                    .put("type", "bool").put("value", true))
                .put(JSONObject().put("key", "shuffle").put("title", "Shuffle")
                    .put("type", "bool").put("value", values.getValue(id))))

        private fun success(body: JSONObject) = CompanionResponse(200, body.put("ok", true).toString())
        private fun reject(reason: String): CompanionResponse {
            rejected += reason
            return CompanionResponse(400, JSONObject().put("ok", false)
                .put("error", "fixture_$reason").toString())
        }
    }

    private companion object {
        const val A = "a"
        const val B = "b"
        const val C = "c"
    }
}
