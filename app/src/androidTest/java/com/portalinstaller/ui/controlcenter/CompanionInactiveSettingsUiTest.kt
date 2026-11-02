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
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import kotlinx.coroutines.CompletableDeferred
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
 * Actual Compose -> ViewModel -> repository/client/group integration with an in-memory
 * server. No saved pairings, sockets, Portal/Pixel acceptance or performance evidence.
 */
@RunWith(AndroidJUnit4::class)
class CompanionInactiveSettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val advanced = mutableStateOf(false)
    private val visible = mutableStateOf(true)
    private val viewModels = ViewModelStore()
    private var contentSet = false
    private lateinit var repositoryJob: Job
    private lateinit var repository: CompanionRepository
    private lateinit var vm: ControlCenterViewModel
    private lateinit var server: FixtureServer

    @Before fun awake() {
        compose.activityRule.scenario.onActivity {
            it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    @After fun closeFixture() {
        if (!::repository.isInitialized) return
        // Remove the state collector, clear the VM's owned scope, and join repository
        // children (including suspended fixture POSTs) before the Activity rule closes.
        compose.activityRule.scenario.onActivity { visible.value = false }
        if (contentSet) compose.waitForIdle()
        compose.activityRule.scenario.onActivity {
            viewModels.clear()
            repository.close()
        }
        runBlocking { withTimeout(5_000) { repositoryJob.cancelAndJoin() } }
    }

    // Reverting group preflight to the ordinary display schema must fail B's outcome.
    @Test fun visibleShuffleConfirmsCapturedRoomsWithoutEnablingDisabledDestination() {
        val postsReleased = CompletableDeferred<Unit>()
        showFixture(viewed = "a", targets = setOf("a", "b"), postGate = postsReleased)
        toggle("shuffle").performScrollTo().assertIsOff().performClick()

        compose.waitUntil(5_000) {
            server.posts().size == 2 ||
                vm.state.value.groupOperations.any { op ->
                    op.items.any { it.status == GroupWorkStatus.FAILED }
                }
        }
        compose.runOnIdle {
            assertFalse("Disabled B must pass child-setting preflight",
                vm.state.value.groupOperations.single().items
                    .any { it.status == GroupWorkStatus.FAILED })
            assertEquals("Both captured rooms must reach their pending POST", 2, server.posts().size)
        }
        // Both sends are suspended: later selection cannot redirect the captured work.
        awaitJob { vm.selectTargets(setOf("a")) }
        compose.runOnIdle {
            assertEquals(setOf("a"), vm.state.value.selectedTargetIds)
            postsReleased.complete(Unit)
        }
        awaitConfirmation()
        compose.waitUntil(5_000) {
            vm.state.value.settings.singleOrNull()?.controls
                ?.singleOrNull { it.key == "shuffle" }?.value == true
        }

        compose.onNodeWithText("A · Screensaver: Confirmed")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("B · Screensaver: Confirmed")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("2 confirmed · 0 failed · 0 cancelled")
            .performScrollTo().assertIsDisplayed()
        toggle("shuffle").performScrollTo().assertIsOn()
        compose.runOnIdle {
            val operation = vm.state.value.groupOperations.single()
            assertEquals(setOf("a", "b"), operation.items.map { it.targetId }.toSet())
            assertTrue(operation.items.all { it.detail == "Confirmed by Portal." })
            assertTrue(server.room("a").shuffle)
            assertTrue(server.room("b").shuffle)
            assertTrue(server.room("a").enabled)
            assertFalse(server.room("b").enabled)
            assertEquals(45, server.room("b").interval)
            assertNull(vm.state.value.error)
        }
        assertPreflightsAndPosts(setOf("a", "b"), "shuffle", true)
    }

    // A display read accidentally using inactive metadata would expose children here.
    @Test fun disabledViewedSchemaStaysMasterOnlyUntilTheRenderedMasterEnablesIt() {
        showFixture(viewed = "b", targets = setOf("b"))
        for (showAdvanced in listOf(false, true)) {
            compose.runOnIdle { advanced.value = showAdvanced }
            toggle("enabled").performScrollTo().assertIsOff()
            compose.onNodeWithTag("registry-shuffle-row").assertDoesNotExist()
            compose.onNodeWithTag("registry-intervalSec-row").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(listOf("enabled"), vm.state.value.settings.single()
                    .controls.map { it.key })
                assertTrue(vm.state.value.groupOperations.isEmpty())
            }
        }
        assertTrue(server.posts().isEmpty())

        toggle("enabled").performClick()
        awaitConfirmation()
        compose.waitUntil(5_000) {
            vm.state.value.settings.singleOrNull()?.controls?.any { it.key == "shuffle" } == true
        }
        toggle("enabled").performScrollTo().assertIsOn()
        toggle("shuffle").performScrollTo().assertIsOff()
        intervalField().performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(45, vm.state.value.settings.single().controls
                .single { it.key == "intervalSec" }.value)
            assertTrue(server.room("b").enabled)
            assertFalse(server.room("b").shuffle)
            assertTrue(server.room("a").enabled)
            assertFalse(server.room("a").shuffle)
            assertNull(vm.state.value.error)
        }
        assertPreflightsAndPosts(setOf("b"), "enabled", true)
        assertTrue(server.requests().filter {
            it.method == "GET" && it.url.toHttpUrl().querySize == 0 &&
                it.url.toHttpUrl().encodedPath == "/remote/settings"
        }.all { it.url.toHttpUrl().host == "b.invalid" })
    }

    // Removing the actual numeric editor's range guard would admit an operation.
    @Test fun invalidIntervalDraftCannotSendOrCreateAnOperation() {
        showFixture(viewed = "a", targets = setOf("a", "b"))
        for (invalid in listOf("4", "601")) {
            intervalField().performScrollTo().performTextReplacement(invalid)
            intervalSave().performScrollTo().assertIsNotEnabled()
                .performTouchInput { click() }
            compose.runOnIdle {
                assertTrue(vm.state.value.groupOperations.isEmpty())
                assertTrue(server.posts().isEmpty())
                assertEquals(45, server.room("a").interval)
                assertEquals(45, server.room("b").interval)
                assertNull(vm.state.value.error)
            }
        }
        for (boundary in listOf("5", "600")) {
            intervalField().performScrollTo().performTextReplacement(boundary)
            intervalSave().performScrollTo().assertIsEnabled()
        }
        compose.runOnIdle {
            assertTrue(vm.state.value.groupOperations.isEmpty())
            assertTrue(server.posts().isEmpty())
        }
    }

    private fun toggle(key: String) = compose.onNode(
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch) and
            hasAnyAncestor(hasTestTag("registry-$key-row")))

    private fun intervalField() = compose.onNode(
        hasSetTextAction() and hasAnyAncestor(hasTestTag("registry-intervalSec-row")))

    private fun intervalSave() = compose.onNode(
        hasText("Save") and hasAnyAncestor(hasTestTag("registry-intervalSec-row")))

    private fun awaitJob(start: () -> Job) {
        lateinit var job: Job
        compose.runOnIdle { job = start() }
        compose.waitUntil(5_000) { job.isCompleted }
        compose.runOnIdle { assertFalse("Fixture job was cancelled", job.isCancelled) }
    }

    private fun awaitConfirmation() {
        compose.waitUntil(5_000) {
            vm.state.value.groupOperations.singleOrNull()?.let { op ->
                !op.running && op.items.all { it.status == GroupWorkStatus.SUCCEEDED }
            } == true
        }
        compose.waitForIdle()
    }

    private fun showFixture(viewed: String, targets: Set<String>,
        postGate: CompletableDeferred<Unit>? = null) {
        server = FixtureServer(postGate)
        compose.activityRule.scenario.onActivity {
            repositoryJob = SupervisorJob()
            repository = CompanionRepository(CompanionClient(server), MemoryStore(),
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
                            { vm.retryGroupOperation(it) }, { vm.cancelGroupOperation(it) },
                            { vm.dismissGroupOperation(it) })
                        state.schema?.let { schema ->
                            RegistrySettingsPanel(schema,
                                working = state.busy || state.selectedTargets.isEmpty(),
                                query = "", advanced = advanced.value,
                                onlyDomain = "screensaver",
                                onApply = { domain, values -> vm.writeSettings(domain, values) },
                                activePortalName = state.deviceName,
                                activeWorking = state.busy || !state.connected,
                                onActiveApply = { domain, values ->
                                    vm.writeActiveSettings(domain, values)
                                })
                        }
                    }
                }
            }
        }
        contentSet = true
        compose.waitUntil(5_000) { repository.ready.isCompleted }
        awaitJob { vm.selectTargets(targets) }
        awaitJob { vm.selectDevice(viewed) }
        awaitJob { vm.refreshSettings() }
        compose.runOnIdle {
            assertTrue(vm.state.value.connected)
            assertNull(vm.state.value.error)
            assertEquals(targets, vm.state.value.selectedTargetIds)
        }
        compose.waitForIdle()
    }

    private fun assertPreflightsAndPosts(ids: Set<String>, key: String, value: Boolean) {
        val calls = server.requests()
        val preflights = calls.filter {
            it.method == "GET" && it.url.toHttpUrl().encodedPath == "/remote/settings" &&
                it.url.toHttpUrl().queryParameter("includeInactive") == "1"
        }
        assertEquals(ids.size, preflights.size)
        assertEquals(ids.map { "$it.invalid" }.toSet(),
            preflights.map { it.url.toHttpUrl().host }.toSet())
        preflights.forEach {
            assertEquals("screensaver", it.url.toHttpUrl().queryParameter("domain"))
            assertEquals(setOf("domain", "includeInactive"), it.url.toHttpUrl().queryParameterNames)
        }
        assertEquals(ids.size, server.posts().size)
        server.posts().forEach { post ->
            val body = JSONObject(post.body!!)
            assertEquals("screensaver", body.getString("domain"))
            assertEquals(setOf(key), body.getJSONObject("values").keys().asSequence().toSet())
            assertEquals(value, body.getJSONObject("values").getBoolean(key))
            val host = post.url.toHttpUrl().host
            assertTrue("Every room must preflight before its POST", calls.indexOf(
                preflights.single { it.url.toHttpUrl().host == host }) < calls.indexOf(post))
        }
        assertEquals(emptyList<String>(), server.rejections())
    }

    private class MemoryStore : CompanionSessionStore {
        @Volatile private var selected = setOf("a", "b")
        override suspend fun load() = listOf("a", "b").map { id ->
            CompanionSession(CompanionDevice(id, id.uppercase(), "http://$id.invalid:8723"),
                "fixture-token-$id")
        }
        override suspend fun save(sessions: List<CompanionSession>) {
            error("This fixture must not persist or replace pairing records")
        }
        override suspend fun loadSelectedIds() = selected
        override suspend fun saveSelectedIds(ids: Set<String>) { selected = ids.toSet() }
    }

    private data class Room(var enabled: Boolean, var shuffle: Boolean = false,
        var interval: Int = 45)

    /** Only identity, passive media and the exact settings contract are supported. */
    private class FixtureServer(private val postGate: CompletableDeferred<Unit>?) : CompanionTransport {
        private val lock = Any()
        private val rooms = mapOf("a" to Room(true), "b" to Room(false))
        private val calls = mutableListOf<CompanionRequest>()
        private val rejected = mutableListOf<String>()
        fun requests() = synchronized(lock) { calls.toList() }
        fun posts() = requests().filter { it.method == "POST" }
        fun room(id: String) = synchronized(lock) { rooms.getValue(id).copy() }
        fun rejections() = synchronized(lock) { rejected.toList() }

        override suspend fun execute(request: CompanionRequest): CompanionResponse {
            val url = request.url.toHttpUrl()
            synchronized(lock) { calls += request }
            if (request.method == "POST" && url.encodedPath == "/remote/settings") {
                postGate?.await()
            }
            return synchronized(lock) {
                val id = when (url.host) {
                    "a.invalid" -> "a"
                    "b.invalid" -> "b"
                    else -> return@synchronized reject("unknown_host")
                }
                if (url.scheme != "http" || url.port != 8723 ||
                    request.sessionToken != "fixture-token-$id" ||
                    request.upload != null || request.binary) {
                    return@synchronized reject("unsupported_transport")
                }
                val room = rooms.getValue(id)
                when {
                    request.method == "GET" && url.querySize == 0 &&
                        request.body == null && url.encodedPath == "/remote/companion" ->
                        success(JSONObject().put("deviceId", id).put("name", id.uppercase())
                            .put("protocolVersion", 1).put("capabilities", JSONObject()))
                    request.method == "GET" && url.querySize == 0 &&
                        request.body == null && url.encodedPath == "/remote/nowplaying" ->
                        success(JSONObject().put("np", JSONObject().put("active", false)))
                    request.method == "GET" && request.body == null &&
                        url.encodedPath == "/remote/settings" -> {
                        val inactive = url.queryParameterNames == setOf("domain", "includeInactive") &&
                            url.queryParameter("domain") == "screensaver" &&
                            url.queryParameter("includeInactive") == "1"
                        if (url.querySize != 0 && !inactive) reject("unsupported_settings_query")
                        else success(JSONObject().put("settings", JSONObject().put("domains",
                            JSONArray().put(domain(room, inactive)))))
                    }
                    request.method == "POST" && url.querySize == 0 &&
                        url.encodedPath == "/remote/settings" -> apply(room, request.body)
                    else -> reject("unsupported_request")
                }
            }
        }

        private fun apply(room: Room, raw: String?): CompanionResponse {
            val body = runCatching { JSONObject(raw ?: "") }.getOrNull()
                ?: return reject("invalid_body")
            val values = body.optJSONObject("values") ?: return reject("missing_values")
            val keys = values.keys().asSequence().toSet()
            if (body.keys().asSequence().toSet() != setOf("domain", "values") ||
                body.optString("domain") != "screensaver" || keys.isEmpty() ||
                keys.any { it !in setOf("enabled", "shuffle", "intervalSec") }) {
                return reject("unsupported_patch")
            }
            // Validate the whole patch before mutating any fixture state.
            for (key in keys) {
                val value = values.get(key)
                if (key == "intervalSec") {
                    if (value !is Number || value.toDouble() != value.toInt().toDouble() ||
                        value.toInt() !in 5..600) return reject("invalid_interval")
                } else if (value !is Boolean) return reject("invalid_boolean")
            }
            if ("enabled" in keys) room.enabled = values.getBoolean("enabled")
            if ("shuffle" in keys) room.shuffle = values.getBoolean("shuffle")
            if ("intervalSec" in keys) room.interval = values.getInt("intervalSec")
            return success(JSONObject().put("applied", JSONArray(keys.toList()))
                .put("domain", domain(room, false)))
        }

        private fun domain(room: Room, inactive: Boolean): JSONObject {
            val controls = JSONArray().put(JSONObject().put("key", "enabled")
                .put("title", "Screensaver enabled").put("type", "bool").put("value", room.enabled))
            if (room.enabled || inactive) {
                controls.put(JSONObject().put("key", "shuffle").put("title", "Shuffle")
                    .put("type", "bool").put("value", room.shuffle))
                controls.put(JSONObject().put("key", "intervalSec").put("title", "Photo interval")
                    .put("type", "int").put("value", room.interval).put("min", 5)
                    .put("max", 600).put("step", 5).put("asText", true))
            }
            return JSONObject().put("id", "screensaver").put("title", "Screensaver")
                .put("controls", controls)
        }

        private fun success(body: JSONObject) =
            CompanionResponse(200, body.put("ok", true).toString())
        private fun reject(reason: String): CompanionResponse {
            rejected += reason
            return CompanionResponse(400, JSONObject().put("ok", false)
                .put("error", reason).toString())
        }
    }
}
