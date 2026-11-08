/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.controlcenter

import android.view.View
import android.view.WindowManager
import android.widget.TimePicker
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyChild
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matcher
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

/**
 * Real panel interactions with wire fixtures and captured callbacks. No pairing, transport,
 * MainActivity, Portal hardware, accounts, or opt-in livePortal argument is involved.
 */
@RunWith(AndroidJUnit4::class)
class ManagementWorkflowUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun keepFixtureVisible() {
        // The production window's keep-awake flag does not follow the isolated fixture.
        compose.activityRule.scenario.onActivity {
            it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    @Test fun layoutResizeSavesBoundIdentityAndTheCompleteUnrelatedDocument() {
        val original = layoutFixture()
        val before = original.toString()
        val saves = mutableListOf<JSONObject>()
        fixture { LayoutEditorPanel(original, false, saves::add, {}) }

        compose.onNodeWithText("Save layout").assertIsNotEnabled()
        widgetControl("com.example.provider", "Increase width").scrollClick()
        widgetControl("com.example.provider", "Increase width").scrollClick()
        widgetControl("com.example.provider", "Increase width").assertIsNotEnabled()
        widgetControl("com.example.provider", "Decrease height").assertIsNotEnabled()
        widgetControl("com.example.provider", "Increase height").scrollClick()
        assertTrue(saves.isEmpty())
        clickText("Save layout")

        val saved = saves.single()
        assertJson(
            """{"id":42,"package":"com.example.provider","class":"ClockProvider","spanX":4,
                "spanY":2,"explicitSize":true,"kind":"appwidget","customId":"",
                "future":{"keep":true}}""",
            saved.getJSONArray("widgets").getJSONObject(0),
        )
        assertJson(original.getJSONArray("widgets").getJSONObject(1),
            saved.getJSONArray("widgets").getJSONObject(1))
        for (field in listOf("assignments", "homeOrder", "gridOrder", "gridSlots", "future", "revision"))
            assertJson(original.get(field), saved.get(field))
        assertEquals(before, original.toString())
    }

    @Test fun layoutWidgetReorderChangesOnlyWidgetPositionsAmongAppsFoldersAndHoles() {
        val original = layoutFixture()
        val saves = mutableListOf<JSONObject>()
        fixture { LayoutEditorPanel(original, false, saves::add, {}) }

        widgetButton("com.example.provider", "Move up").assertIsNotEnabled()
        widgetButton("com.example.provider", "Move down").scrollClick()
        widgetButton("Weather", "Move up").assertIsNotEnabled()
        widgetButton("com.example.provider", "Move down").assertIsNotEnabled()
        clickText("Save layout")

        val saved = saves.single()
        assertJson("""["builtin:tools","widget-tile:custom:weather:one","app:com.example.player",
            "folder:Reading","widget-tile:widget:42","future:tile"]""", saved.getJSONArray("gridOrder"))
        assertJson("""["widget-tile:custom:weather:one",null,"app:com.example.player",
            "","folder:Reading","widget-tile:widget:42","future:tile"]""", saved.getJSONArray("gridSlots"))
        val widgets = saved.getJSONArray("widgets")
        assertJson(original.getJSONArray("widgets").getJSONObject(1), widgets.getJSONObject(0))
        assertJson(original.getJSONArray("widgets").getJSONObject(0), widgets.getJSONObject(1))
        for (field in listOf("assignments", "homeOrder", "future", "revision"))
            assertJson(original.get(field), saved.get(field))
    }

    @Test fun layoutOrderGroupsMoveTheSelectedOrderWithoutRewritingOtherOrders() {
        val original = layoutFixture()
        val saves = mutableListOf<JSONObject>()
        fixture { LayoutEditorPanel(original, false, saves::add, {}) }

        clickText("Home app order")
        // The two widget rows precede the expanded order rows.
        compose.onAllNodes(hasText("Move down") and hasClickAction())[2].scrollClick()
        clickText("Home app order")
        clickText("All tile order")
        compose.onAllNodes(hasText("Move down") and hasClickAction())[2].scrollClick()
        clickText("All tile order")
        clickText("Grid positions")
        // Move the null slot one place down without collapsing it out of the grid.
        compose.onAllNodes(hasText("Move down") and hasClickAction())[3].scrollClick()
        clickText("Save layout")

        val saved = saves.single()
        assertJson("""["com.example.browser","com.example.player"]""", saved.getJSONArray("homeOrder"))
        assertJson("""["widget-tile:widget:42","builtin:tools","app:com.example.player",
            "folder:Reading","widget-tile:custom:weather:one","future:tile"]""", saved.getJSONArray("gridOrder"))
        assertJson("""["widget-tile:widget:42","app:com.example.player",null,"",
            "folder:Reading","widget-tile:custom:weather:one","future:tile"]""", saved.getJSONArray("gridSlots"))
        for (field in listOf("assignments", "widgets", "future", "revision"))
            assertJson(original.get(field), saved.get(field))
    }

    @Test fun layoutAddsDistinctNativeWidgetsIntoHolesWithoutInventingProviderBindings() {
        val original = layoutFixture()
        val saves = mutableListOf<JSONObject>()
        fixture { LayoutEditorPanel(original, false, saves::add, {}) }

        clickText("Add Hearthpane widget")
        chooseMenu("Today")
        clickText("Add Hearthpane widget")
        chooseMenu("Today")
        clickText("Save layout")

        val saved = saves.single()
        val widgets = saved.getJSONArray("widgets")
        assertEquals(4, widgets.length())
        val ids = (2..3).map { index ->
            val widget = widgets.getJSONObject(index)
            assertEquals("today", widget.getString("kind"))
            assertEquals(0, widget.getInt("id"))
            assertEquals("", widget.getString("package"))
            assertEquals("", widget.getString("class"))
            assertEquals(2, widget.getInt("spanX"))
            assertEquals(2, widget.getInt("spanY"))
            assertTrue(widget.getBoolean("explicitSize"))
            widget.getString("customId").also { assertTrue(it.startsWith("today:")) }
        }
        assertNotEquals(ids[0], ids[1])
        val slots = saved.getJSONArray("gridSlots")
        assertEquals(7, slots.length())
        assertEquals("widget-tile:custom:${ids[0]}", slots.getString(1))
        assertEquals("widget-tile:custom:${ids[1]}", slots.getString(3))
        for (index in listOf(0, 2, 4, 5, 6))
            assertJson(original.getJSONArray("gridSlots").get(index), slots.get(index))
        val order = saved.getJSONArray("gridOrder")
        assertEquals(8, order.length())
        assertEquals("widget-tile:custom:${ids[0]}", order.getString(6))
        assertEquals("widget-tile:custom:${ids[1]}", order.getString(7))
        for (index in 0..1) assertJson(original.getJSONArray("widgets").get(index), widgets.get(index))
        for (field in listOf("assignments", "homeOrder", "future", "revision"))
            assertJson(original.get(field), saved.get(field))
    }

    @Test fun layoutRemoveClearsOnlyTheRemovedBindingAndKeepsGridPositions() {
        val original = layoutFixture()
        val saves = mutableListOf<JSONObject>()
        fixture { LayoutEditorPanel(original, false, saves::add, {}) }

        widgetButton("com.example.provider", "Remove widget").scrollClick()
        clickText("Save layout")

        val saved = saves.single()
        assertEquals(1, saved.getJSONArray("widgets").length())
        assertJson(original.getJSONArray("widgets").getJSONObject(1),
            saved.getJSONArray("widgets").getJSONObject(0))
        assertJson("""["builtin:tools","app:com.example.player","folder:Reading",
            "widget-tile:custom:weather:one","future:tile"]""", saved.getJSONArray("gridOrder"))
        assertJson("""[null,null,"app:com.example.player","","folder:Reading",
            "widget-tile:custom:weather:one","future:tile"]""", saved.getJSONArray("gridSlots"))
        for (field in listOf("assignments", "homeOrder", "future", "revision"))
            assertJson(original.get(field), saved.get(field))
    }

    @Test fun layoutDiscardRestoresAllEditsAndDoesNotSaveOrPreviewThem() {
        val original = layoutFixture()
        val before = original.toString()
        val saves = mutableListOf<JSONObject>()
        var previews = 0
        val restoration = restoredFixture {
            LayoutEditorPanel(original, false, saves::add, { previews++ })
        }

        widgetControl("com.example.provider", "Increase width").scrollClick()
        widgetButton("Weather", "Remove widget").scrollClick()
        clickText("Add Hearthpane widget")
        chooseMenu("Today")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Save layout").assertIsEnabled()
        clickText("Discard changes")
        compose.onNodeWithText("Save layout").assertIsNotEnabled()
        compose.onNodeWithText("Discard changes").assertDoesNotExist()
        compose.onNodeWithText("Today").assertDoesNotExist()
        widgetButton("Weather", "Remove widget").assertIsEnabled()
        assertTrue(saves.isEmpty())
        assertEquals(0, previews)
        assertEquals(before, original.toString())

        // A later save must start from the restored document, including the removed widget.
        widgetControl("com.example.provider", "Increase height").scrollClick()
        clickText("Save layout")
        val saved = saves.single()
        assertEquals(2, saved.getJSONArray("widgets").getJSONObject(0).getInt("spanX"))
        assertEquals(2, saved.getJSONArray("widgets").getJSONObject(0).getInt("spanY"))
        assertJson(original.getJSONArray("widgets").get(1), saved.getJSONArray("widgets").get(1))
        assertJson(original.getJSONArray("gridOrder"), saved.getJSONArray("gridOrder"))
        assertJson(original.getJSONArray("gridSlots"), saved.getJSONArray("gridSlots"))
    }

    @Test fun layoutBusyAndIncompleteDocumentsCannotStartMutatingCallbacks() {
        var working by mutableStateOf(false)
        var received by mutableStateOf(layoutFixture())
        val saves = mutableListOf<JSONObject>()
        var previews = 0
        fixture { LayoutEditorPanel(received, working, saves::add, { previews++ }) }

        widgetControl("com.example.provider", "Increase width").scrollClick()
        compose.runOnIdle { working = true }
        compose.onNodeWithText("Working…").assertIsNotEnabled()
        compose.onNodeWithText("Discard changes").assertIsNotEnabled()
        compose.onNodeWithText("Preview on Portal").assertIsNotEnabled()
        compose.onNodeWithText("Add Hearthpane widget").assertIsNotEnabled()
        widgetControl("com.example.provider", "Increase height").assertIsNotEnabled()
        widgetButton("Weather", "Remove widget").assertIsNotEnabled()
        widgetButton("com.example.provider", "Move down").assertIsNotEnabled()
        compose.runOnIdle {
            working = false
            received = layoutFixture().apply { remove("gridSlots") }
        }
        compose.onNodeWithText("Save layout").assertIsNotEnabled()
        compose.onNodeWithText("Add Hearthpane widget").assertIsNotEnabled()
        widgetButton("Weather", "Remove widget").assertIsNotEnabled()
        assertTrue(saves.isEmpty())
        assertEquals(0, previews)
        clickText("Preview on Portal")
        assertEquals(1, previews)
    }

    @Test fun sceneTemplatesSaveNativeActionsAndOnlySavedScenesCanRun() {
        var received by mutableStateOf(JSONArray())
        val saves = mutableListOf<JSONArray>()
        val runs = mutableListOf<String>()
        fixture { ScenesPanel(received, false, saves::add, runs::add) }

        clickText("Evening")
        compose.onNodeWithText("Run on Portal").assertIsNotEnabled()
        clickText("Music")
        clickText("Dashboard")
        compose.onNodeWithText("Run on Portal").assertIsNotEnabled()
        assertTrue(saves.isEmpty())
        assertTrue(runs.isEmpty())
        clickText("Save scenes")

        val saved = saves.single()
        assertEquals(3, saved.length())
        val ids = (0..2).map { saved.getJSONObject(it).getString("id") }
        assertEquals(3, ids.toSet().size)
        assertTrue(ids.all { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) })
        assertEquals("Evening", saved.getJSONObject(0).getString("name"))
        assertJson("""[{"type":"settings","domain":"screensaver","values":{"enabled":true}},
            {"type":"key","action":"screensaver"}]""", saved.getJSONObject(0).getJSONArray("steps"))
        assertEquals("Music", saved.getJSONObject(1).getString("name"))
        assertJson("""[{"type":"key","action":"apps"}]""", saved.getJSONObject(1).getJSONArray("steps"))
        assertEquals("Dashboard", saved.getJSONObject(2).getString("name"))
        assertJson("""[{"type":"key","action":"home"}]""", saved.getJSONObject(2).getJSONArray("steps"))
        compose.runOnIdle { received = JSONArray(saved.toString()) }
        compose.onNodeWithText("Save scenes").assertIsNotEnabled()
        clickText("Run on Portal")
        assertEquals(listOf(ids[2]), runs)
        field("Scene name").scrollReplace("Unsaved dashboard")
        compose.onNodeWithText("Run on Portal").assertIsNotEnabled()
        clickText("Discard changes")
        clickText("Run on Portal")
        assertEquals(listOf(ids[2], ids[2]), runs)
    }

    @Test fun sceneAddsLaunchAndSourceActionsAndBlocksInvalidDraftsBeforeSaving() {
        val original = sceneFixture()
        val before = original.toString()
        val saves = mutableListOf<JSONArray>()
        fixture { ScenesPanel(original, false, saves::add, {}) }

        clickText("Add action")
        chooseMenu("Launch an app")
        compose.onNodeWithText("Save scenes").assertIsNotEnabled()
        field("Installed app package").scrollReplace("not a package")
        compose.onNodeWithText("Save scenes").assertIsNotEnabled()
        field("Installed app package").scrollReplace("com.example.player")
        compose.onNodeWithText("Save scenes").assertIsEnabled()
        clickText("Add action")
        chooseMenu("Photo source")
        clickText("Built-in photos")
        chooseMenu("Local folder")
        field("Portal folder path").scrollReplace("/sdcard/../data")
        compose.onNodeWithText("Save scenes").assertIsNotEnabled()
        field("Portal folder path").scrollReplace("/sdcard/Pictures/Evening")
        clickText("Save scenes")

        val saved = saves.single()
        val scene = saved.getJSONObject(0)
        assertEquals("evening-id", scene.getString("id"))
        assertJson("""{"type":"launch","packageName":"com.example.player"}""",
            scene.getJSONArray("steps").getJSONObject(2))
        assertJson("""{"type":"source","values":{"source":"local","folderPath":"/sdcard/Pictures/Evening"}}""",
            scene.getJSONArray("steps").getJSONObject(3))
        assertJson(original.getJSONObject(0).getJSONObject("future"), scene.getJSONObject("future"))
        assertJson(original.getJSONObject(0).getJSONObject("schedule"), scene.getJSONObject("schedule"))
        assertJson(original.getJSONObject(1), saved.getJSONObject(1))
        assertEquals(before, original.toString())
    }

    @Test fun sceneReorderRemoveAndDiscardPreserveIdsAndOpaqueStepFields() {
        val original = sceneFixture()
        val saves = mutableListOf<JSONArray>()
        fixture { ScenesPanel(original, false, saves::add, {}) }

        compose.onAllNodes(hasText("Move up") and hasClickAction())[0].assertIsNotEnabled()
        compose.onAllNodes(hasText("Move down") and hasClickAction())[0].scrollClick()
        compose.onAllNodes(hasText("Remove") and hasClickAction())[1].scrollClick()
        clickText("Discard changes")
        compose.onNodeWithText("Save scenes").assertIsNotEnabled()
        compose.onAllNodes(hasText("Move down") and hasClickAction())[0].scrollClick()
        clickText("Save scenes")

        val saved = saves.single()
        val scene = saved.getJSONObject(0)
        assertEquals("evening-id", scene.getString("id"))
        assertJson(original.getJSONObject(0).getJSONArray("steps").get(1), scene.getJSONArray("steps").get(0))
        assertJson(original.getJSONObject(0).getJSONArray("steps").get(0), scene.getJSONArray("steps").get(1))
        assertJson(original.getJSONObject(0).getJSONObject("future"), scene.getJSONObject("future"))
        assertJson(original.getJSONObject(0).getJSONObject("schedule"), scene.getJSONObject("schedule"))
        assertJson(original.getJSONObject(1), saved.getJSONObject(1))
    }

    @Test fun sceneNavigationAndSettingControlsSaveTheirValuesAndNewActionsUseNativeFields() {
        val original = sceneFixture()
        val saves = mutableListOf<JSONArray>()
        fixture { ScenesPanel(original, false, saves::add, {}) }

        switch("Enabled").scrollClick()
        clickText("Open photo frame")
        chooseMenu("Wake screen")
        clickText("Add action")
        chooseMenu("Device setting")
        clickText("Add action")
        chooseMenu("Navigation")
        clickText("Go home")
        chooseMenu("Sleep screen")
        clickText("Save scenes")

        assertJson("""[
            {"type":"settings","domain":"screensaver","values":{"enabled":false},"future":{"keep-step":true}},
            {"type":"key","action":"wake","future":"keep-navigation"},
            {"type":"settings","domain":"screensaver","values":{"enabled":true}},
            {"type":"key","action":"sleep"}
        ]""", saves.single().getJSONObject(0).getJSONArray("steps"))
        assertJson(original.getJSONObject(1), saves.single().getJSONObject(1))
    }

    @Test fun sceneScheduleUsesIsoDaysAndPortalLocalTimeAndRequiresAtLeastOneDay() {
        val original = sceneFixture()
        val saves = mutableListOf<JSONArray>()
        fixture { ScenesPanel(original, false, saves::add, {}) }

        switch("Run on a schedule").scrollClick()
        compose.onNodeWithContentDescription("Monday").scrollClick()
        compose.onNodeWithContentDescription("Sunday").scrollClick()
        compose.onNodeWithText("Save scenes").assertIsNotEnabled()
        assertTrue(saves.isEmpty())
        compose.onNodeWithContentDescription("Wednesday").scrollClick()
        clickText("22:15")
        onView(isAssignableFrom(TimePicker::class.java)).inRoot(isDialog()).perform(setTime(6, 45))
        tapNativePositiveButton()
        compose.waitForIdle()
        compose.onNodeWithText("06:45").assertExists()
        clickText("Save scenes")

        val scene = saves.single().getJSONObject(0)
        assertJson("""{"enabled":true,"hour":6,"minute":45,"days":[3],"future":"keep-schedule"}""",
            scene.getJSONObject("schedule"))
        assertJson(original.getJSONObject(0).getJSONArray("steps"), scene.getJSONArray("steps"))
        assertJson(original.getJSONObject(1), saves.single().getJSONObject(1))
    }

    @Test fun sceneWaitEditsCannotSaveBlankFractionalOutOfRangeOrExcessTotalWaits() {
        val original = sceneFixture()
        val saves = mutableListOf<JSONArray>()
        fixture { ScenesPanel(original, false, saves::add, {}) }

        clickText("Add action")
        chooseMenu("Wait")
        val wait = field("Wait in milliseconds (0–5000)")
        for (invalid in listOf("", "1.5", "-1", "5001")) {
            wait.scrollReplace(invalid)
            compose.onNodeWithText("Save scenes").assertIsNotEnabled()
        }
        wait.scrollReplace("3000")
        clickText("Add action")
        chooseMenu("Wait")
        compose.onAllNodes(hasSetTextAction() and hasText("Wait in milliseconds (0–5000)"))[1]
            .scrollReplace("3000")
        compose.onNodeWithText("Save scenes").assertIsNotEnabled()
        assertTrue(saves.isEmpty())
        compose.onAllNodes(hasSetTextAction() and hasText("Wait in milliseconds (0–5000)"))[1]
            .scrollReplace("2000")
        clickText("Save scenes")
        val steps = saves.single().getJSONObject(0).getJSONArray("steps")
        assertJson("""{"type":"wait","ms":3000}""", steps.getJSONObject(2))
        assertJson("""{"type":"wait","ms":2000}""", steps.getJSONObject(3))
    }

    @Test fun sceneNumericSettingAndNameValidationDoNotSavePreviousValues() {
        val original = sceneFixture()
        original.getJSONObject(0).getJSONArray("steps").getJSONObject(0)
            .getJSONObject("values").put("intervalSec", 30)
        val saves = mutableListOf<JSONArray>()
        fixture { ScenesPanel(original, false, saves::add, {}) }

        field("intervalSec").scrollReplace("")
        compose.onNodeWithText("Save scenes").assertIsNotEnabled()
        field("intervalSec").scrollReplace("forty")
        compose.onNodeWithText("Save scenes").assertIsNotEnabled()
        field("intervalSec").scrollReplace("45")
        field("Scene name").scrollReplace("")
        compose.onNodeWithText("Save scenes").assertIsNotEnabled()
        assertTrue(saves.isEmpty())
        field("Scene name").scrollReplace("Evening at home")
        clickText("Save scenes")
        val scene = saves.single().getJSONObject(0)
        assertEquals("Evening at home", scene.getString("name"))
        assertEquals(45, scene.getJSONArray("steps").getJSONObject(0).getJSONObject("values").getInt("intervalSec"))
        assertTrue(scene.getJSONArray("steps").getJSONObject(0).getJSONObject("values").getBoolean("enabled"))
        assertJson(original.getJSONObject(0).getJSONArray("steps").getJSONObject(0).getJSONObject("future"),
            scene.getJSONArray("steps").getJSONObject(0).getJSONObject("future"))
    }

    @Test fun sceneMalformedNumericIdentityCannotBeResavedAfterAValidNameEdit() {
        val original = sceneFixture()
        original.getJSONObject(0).put("id", 123)
        val saves = mutableListOf<JSONArray>()
        val runs = mutableListOf<String>()
        fixture { ScenesPanel(original, false, saves::add, runs::add) }

        compose.onNodeWithText("Run on Portal").assertIsNotEnabled()
        field("Scene name").scrollReplace("Valid renamed scene")
        compose.onNodeWithText("Save scenes").assertIsNotEnabled()
        compose.onNodeWithText("Run on Portal").assertIsNotEnabled()
        assertTrue(saves.isEmpty())
        assertTrue(runs.isEmpty())
    }

    @Test fun sceneBusyGatesTemplatesRunScheduleAndMutationsAndRemovalSavesOnlyTheSurvivor() {
        var working by mutableStateOf(false)
        val original = sceneFixture()
        val saves = mutableListOf<JSONArray>()
        val runs = mutableListOf<String>()
        fixture { ScenesPanel(original, working, saves::add, runs::add) }

        field("Scene name").scrollReplace("Unsaved")
        compose.runOnIdle { working = true }
        compose.onNodeWithText("Working…").assertIsNotEnabled()
        compose.onNodeWithText("Discard changes").assertIsNotEnabled()
        compose.onNodeWithText("Run on Portal").assertIsNotEnabled()
        compose.onNode(hasText("Dashboard") and hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithText("Add action").assertIsNotEnabled()
        compose.onNodeWithText("Remove scene").assertIsNotEnabled()
        // Disabled text fields deliberately omit the SetText accessibility action.
        compose.onNodeWithText("Scene name").assertIsNotEnabled()
        switch("Run on a schedule").assertIsNotEnabled()
        compose.onAllNodes(hasText("Remove") and hasClickAction())[0].assertIsNotEnabled()
        assertTrue(saves.isEmpty())
        assertTrue(runs.isEmpty())
        compose.runOnIdle { working = false }
        clickText("Discard changes")
        clickText("Remove scene")
        clickText("Save scenes")
        assertEquals(1, saves.single().length())
        assertJson(original.getJSONObject(1), saves.single().getJSONObject(0))
    }

    @Test fun jarvisModelAndWakeSaveOnlyChangedAllowlistedControlsAndDiscardRestoresBoth() {
        var received by mutableStateOf(jarvisFixture())
        val original = received.toString()
        val configurations = mutableListOf<JSONObject>()
        var launches = 0
        fixture { JarvisManagementPanel(received, false, configurations::add, { launches++ }) }

        compose.onNodeWithText("Save Jarvis settings").assertIsNotEnabled()
        clickText("model-a")
        chooseMenu("model-b")
        switch().scrollClick()
        assertTrue(configurations.isEmpty())
        clickText("Discard changes")
        compose.onNodeWithText("Save Jarvis settings").assertIsNotEnabled()
        compose.onNodeWithText("model-b").assertDoesNotExist()
        switch().assertIsOff()
        assertTrue(configurations.isEmpty())
        clickText("model-a")
        chooseMenu("model-b")
        switch().scrollClick()
        clickText("Save Jarvis settings")
        assertJson("""{"model":"model-b","wakeEnabled":true}""", configurations.single())
        assertEquals(original, received.toString())
        compose.runOnIdle {
            received = jarvisFixture().put("model", "model-b").put("wakeEnabled", true)
        }
        compose.onNodeWithText("Save Jarvis settings").assertIsNotEnabled()
        switch().assertIsOn()
        clickText("Open Jarvis on Portal")
        assertEquals(1, launches)
    }

    @Test fun jarvisAvailableModelsAreDeduplicatedAndMalformedEntriesCannotBeSelected() {
        val status = jarvisFixture().put("availableModels", JSONArray()
            .put("model-a").put("model-b").put("model-b")
            .put("invalid model").put(JSONObject().put("model", "object-model")).put(JSONObject.NULL))
        val configurations = mutableListOf<JSONObject>()
        fixture { JarvisManagementPanel(status, false, configurations::add, {}) }

        clickText("model-a")
        compose.onNode(hasText("invalid model") and hasAnyAncestor(isPopup())).assertDoesNotExist()
        compose.onNode(hasText("object-model") and hasAnyAncestor(isPopup())).assertDoesNotExist()
        assertEquals(1, compose.onAllNodes(hasText("model-b") and hasAnyAncestor(isPopup()))
            .fetchSemanticsNodes().size)
        chooseMenu("model-b")
        clickText("Save Jarvis settings")
        assertJson("""{"model":"model-b"}""", configurations.single())
    }

    @Test fun jarvisWithoutWakeCapabilitySavesModelOnlyAndNeverOffersAWakeToggle() {
        val status = jarvisFixture().put("wakeSupported", false).put("wakeEnabled", true)
        val configurations = mutableListOf<JSONObject>()
        fixture { JarvisManagementPanel(status, false, configurations::add, {}) }

        switch().assertDoesNotExist()
        compose.onNodeWithText("Allow background wake").assertDoesNotExist()
        clickText("model-a")
        chooseMenu("model-b")
        clickText("Save Jarvis settings")
        assertJson("""{"model":"model-b"}""", configurations.single())
    }

    @Test fun jarvisUnknownWakePolicyIsReadOnlyWhileAnAvailableModelCanStillSave() {
        val status = jarvisFixture().put("wakeEnabled", JSONObject.NULL)
        val configurations = mutableListOf<JSONObject>()
        fixture { JarvisManagementPanel(status, false, configurations::add, {}) }

        switch().assertIsNotEnabled()
        clickText("model-a")
        chooseMenu("model-b")
        clickText("Save Jarvis settings")
        assertJson("""{"model":"model-b"}""", configurations.single())
    }

    @Test fun jarvisWithoutAnAvailableModelListCanSaveOnlyKnownWakePolicy() {
        val status = jarvisFixture().apply { remove("availableModels") }
        val configurations = mutableListOf<JSONObject>()
        fixture { JarvisManagementPanel(status, false, configurations::add, {}) }

        compose.onNode(hasText("model-a") and hasClickAction()).assertIsNotEnabled()
        switch().scrollClick()
        clickText("Save Jarvis settings")
        assertJson("""{"wakeEnabled":true}""", configurations.single())
    }

    @Test fun jarvisUnsupportedInstalledVersionCanOpenButCannotConfigure() {
        val configurations = mutableListOf<JSONObject>()
        var launches = 0
        fixture {
            JarvisManagementPanel(jarvisFixture().put("supported", false), false,
                configurations::add, { launches++ })
        }
        compose.onNodeWithText("Save Jarvis settings").assertDoesNotExist()
        compose.onNodeWithText("Conversation model").assertDoesNotExist()
        switch().assertDoesNotExist()
        clickText("Open Jarvis on Portal")
        assertEquals(1, launches)
        assertTrue(configurations.isEmpty())
    }

    @Test fun jarvisNotInstalledCannotOpenOrConfigureEvenWithStrayCapabilities() {
        val configurations = mutableListOf<JSONObject>()
        var launches = 0
        fixture {
            JarvisManagementPanel(jarvisFixture().put("installed", false), false,
                configurations::add, { launches++ })
        }
        compose.onNodeWithText("Open Jarvis on Portal").assertIsNotEnabled()
        compose.onNodeWithText("Save Jarvis settings").assertDoesNotExist()
        switch().assertDoesNotExist()
        assertEquals(0, launches)
        assertTrue(configurations.isEmpty())
    }

    @Test fun jarvisBusyGatesLaunchModelWakeSaveAndDiscardWithoutLosingTheDraft() {
        var working by mutableStateOf(false)
        val configurations = mutableListOf<JSONObject>()
        var launches = 0
        fixture { JarvisManagementPanel(jarvisFixture(), working, configurations::add, { launches++ }) }

        clickText("model-a")
        chooseMenu("model-b")
        switch().scrollClick()
        compose.runOnIdle { working = true }
        compose.onNodeWithText("Open Jarvis on Portal").assertIsNotEnabled()
        compose.onNode(hasText("model-b") and hasClickAction()).assertIsNotEnabled()
        switch().assertIsNotEnabled()
        compose.onNodeWithText("Working…").assertIsNotEnabled()
        compose.onNodeWithText("Discard changes").assertIsNotEnabled()
        assertTrue(configurations.isEmpty())
        assertEquals(0, launches)
        compose.runOnIdle { working = false }
        clickText("Save Jarvis settings")
        assertJson("""{"model":"model-b","wakeEnabled":true}""", configurations.single())
    }

    private fun fixture(content: @Composable () -> Unit) {
        compose.setContent { PanelFixture(content) }
        compose.waitForIdle()
    }

    private fun restoredFixture(content: @Composable () -> Unit): StateRestorationTester =
        StateRestorationTester(compose).also { restoration ->
            restoration.setContent { PanelFixture(content) }
            compose.waitForIdle()
        }

    @Composable
    private fun PanelFixture(content: @Composable () -> Unit) {
        MaterialTheme {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                content()
            }
        }
    }

    private fun clickText(text: String) =
        compose.onNode(hasText(text) and hasClickAction()).scrollClick()

    private fun chooseMenu(text: String) =
        compose.onNode(hasText(text) and hasAnyAncestor(isPopup())).performClick()

    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label))

    private fun switch(label: String = "Allow background wake") = compose.onNode(
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch) and
            hasAnySibling(hasText(label) or hasAnyDescendant(hasText(label))))

    private fun widgetControl(title: String, description: String) =
        compose.onNode(hasContentDescription(description) and inWidget(title))

    private fun widgetButton(title: String, text: String) =
        compose.onNode(hasText(text) and hasClickAction() and inWidget(title))

    private fun inWidget(title: String) = hasAnyAncestor(hasAnyChild(hasText(title)))

    private fun SemanticsNodeInteraction.scrollClick() {
        performScrollTo().performClick()
    }

    private fun SemanticsNodeInteraction.scrollReplace(value: String) {
        performScrollTo().performTextReplacement(value)
    }

    private fun layoutFixture() = JSONObject("""
        {
          "assignments":{"com.example.player":"Music","com.example.browser":"Reading"},
          "homeOrder":["com.example.player","com.example.browser"],
          "gridOrder":["builtin:tools","widget-tile:widget:42","app:com.example.player","folder:Reading",
            "widget-tile:custom:weather:one","future:tile"],
          "gridSlots":["widget-tile:widget:42",null,"app:com.example.player","","folder:Reading",
            "widget-tile:custom:weather:one","future:tile"],
          "widgets":[
            {"id":42,"package":"com.example.provider","class":"ClockProvider","spanX":2,"spanY":1,
              "kind":"appwidget","customId":"","future":{"keep":true}},
            {"id":0,"package":"","class":"","spanX":2,"spanY":2,"explicitSize":true,
              "kind":"weather","customId":"weather:one","future":"keep-weather"}
          ],
          "future":{"opaque":[1,2,3]},
          "revision":"fixture-revision"
        }
    """.trimIndent())

    private fun sceneFixture() = JSONArray("""
        [
          {"id":"evening-id","name":"Evening fixture","future":{"keep":true},
            "steps":[
              {"type":"settings","domain":"screensaver","values":{"enabled":true},"future":{"keep-step":true}},
              {"type":"key","action":"screensaver","future":"keep-navigation"}
            ],
            "schedule":{"enabled":false,"hour":22,"minute":15,"days":[1,7],"future":"keep-schedule"}},
          {"id":"other-id","name":"Other fixture","steps":[{"type":"launch","packageName":"com.example.player"}],
            "future":{"untouched":[1,2]}}
        ]
    """.trimIndent())

    private fun jarvisFixture() = JSONObject("""
        {"installed":true,"supported":true,"running":false,"status":"ready",
          "microphoneGranted":true,"wakeSupported":true,"wakeEnabled":false,"wakeListening":false,
          "model":"model-a","availableModels":["model-a","model-b"],"future":{"opaque":"keep-on-portal"}}
    """.trimIndent())

    private fun setTime(hour: Int, minute: Int) = object : ViewAction {
        override fun getConstraints(): Matcher<View> = isAssignableFrom(TimePicker::class.java)
        override fun getDescription() = "Select $hour:$minute in the native schedule time picker"
        override fun perform(uiController: UiController, view: View) {
            // Commit any previously focused numeric editor before setting the new time.
            (view as TimePicker).apply {
                clearFocus()
                this.hour = hour
                this.minute = minute
                clearFocus()
            }
            uiController.loopMainThreadUntilIdle()
            assertEquals(hour, view.hour)
            assertEquals(minute, view.minute)
        }
    }

    private fun tapNativePositiveButton() {
        var coordinates: Pair<Int, Int>? = null
        onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = withId(android.R.id.button1)
            override fun getDescription() = "Read the native positive button's screen coordinates"
            override fun perform(uiController: UiController, view: View) {
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                coordinates = location[0] + view.width / 2 to location[1] + view.height / 2
            }
        })
        val (x, y) = requireNotNull(coordinates)
        // Espresso's injected tap misses this Android 17 Fold dialog. Shell input
        // follows the same OS touch path verified manually; this runs off the UI thread.
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("input tap $x $y").use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
            }
    }

    private fun assertJson(expected: String, actual: Any) = assertJson(
        if (expected.trimStart().startsWith("[")) JSONArray(expected) else JSONObject(expected), actual)

    /** Structural comparison tolerates JSON key order, but detects dropped fields and slot holes. */
    private fun assertJson(expected: Any, actual: Any) {
        when (expected) {
            is JSONObject -> {
                assertTrue("Expected an object, got $actual", actual is JSONObject)
                actual as JSONObject
                val keys = expected.keys().asSequence().toSet()
                assertEquals(keys, actual.keys().asSequence().toSet())
                for (key in keys) assertJson(expected.get(key), actual.get(key))
            }
            is JSONArray -> {
                assertTrue("Expected an array, got $actual", actual is JSONArray)
                actual as JSONArray
                assertEquals(expected.length(), actual.length())
                for (index in 0 until expected.length()) assertJson(expected.get(index), actual.get(index))
            }
            is Number -> {
                assertTrue("Expected a number, got $actual", actual is Number)
                assertEquals(expected.toDouble(), (actual as Number).toDouble(), 0.0)
            }
            else -> assertEquals(expected, actual)
        }
    }

    @Test fun layoutLongPressDragReordersWidgetsWithoutMovingAppsFoldersOrHoles() {
        val original = layoutFixture()
        val before = original.toString()
        val saves = mutableListOf<JSONObject>()
        fixture { LayoutEditorPanel(original, false, saves::add, {}) }

        fun dragPoints(): Pair<androidx.compose.ui.geometry.Offset, androidx.compose.ui.geometry.Offset> {
            val targetRow = compose.onNode(hasAnyChild(hasText("Weather")))
            targetRow.performScrollTo()
            val source = compose.onNode(hasAnyChild(hasText("com.example.provider")))
                .fetchSemanticsNode().boundsInRoot
            val target = targetRow.fetchSemanticsNode().boundsInRoot
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            assertTrue("The source row must have a visible drag surface.", source.height > 12f)
            assertTrue("The target row must be visible.", target.height > 12f)
            // The right-hand padding avoids the resize, remove and Move buttons. Crossing the
            // second row's center exercises the pointer reorder handler in LayoutReorderRows.
            return androidx.compose.ui.geometry.Offset(source.right - 4f, source.bottom - 4f) -
                root.topLeft to androidx.compose.ui.geometry.Offset(source.right - 4f, target.center.y + 4f) -
                root.topLeft
        }

        val (shortStart, shortEnd) = dragPoints()
        compose.onRoot().performTouchInput {
            down(shortStart)
            moveTo(shortEnd, delayMillis = 80)
            up()
        }
        compose.onNodeWithText("Save layout").assertIsNotEnabled()
        assertTrue(saves.isEmpty())

        val (start, end) = dragPoints()
        compose.onRoot().performTouchInput {
            down(start)
            val holdMillis = viewConfiguration.longPressTimeoutMillis + 100
            var held = 0L
            while (held < holdMillis) {
                val interval = (holdMillis - held).coerceAtMost(16L)
                moveTo(start, delayMillis = interval)
                held += interval
            }
            for (step in 1..6) moveTo(start + (end - start) * (step / 6f), delayMillis = 40)
            up()
        }
        compose.onNodeWithText("Save layout").assertIsEnabled()
        assertTrue(saves.isEmpty())
        clickText("Save layout")

        val saved = saves.single()
        assertJson(original.getJSONArray("widgets").getJSONObject(1), saved.getJSONArray("widgets").getJSONObject(0))
        assertJson(original.getJSONArray("widgets").getJSONObject(0), saved.getJSONArray("widgets").getJSONObject(1))
        assertJson("""["builtin:tools","widget-tile:custom:weather:one","app:com.example.player",
            "folder:Reading","widget-tile:widget:42","future:tile"]""", saved.getJSONArray("gridOrder"))
        assertJson("""["widget-tile:custom:weather:one",null,"app:com.example.player","",
            "folder:Reading","widget-tile:widget:42","future:tile"]""", saved.getJSONArray("gridSlots"))
        for (field in listOf("assignments", "homeOrder", "future", "revision"))
            assertJson(original.get(field), saved.get(field))
        assertEquals(before, original.toString())
    }
}
