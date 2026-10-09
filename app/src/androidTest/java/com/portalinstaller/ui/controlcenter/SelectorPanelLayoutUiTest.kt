/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.controlcenter

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.portalinstaller.R
import com.portalinstaller.data.store.ThemeMode
import com.portalinstaller.ui.more.SettingsTab
import com.portalinstaller.util.AppLanguage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual selector panels on a 360dp phone with enlarged text and callback-only fixtures. */
@RunWith(AndroidJUnit4::class)
class SelectorPanelLayoutUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun keepWindowAwake() {
        compose.activityRule.scenario.onActivity {
            it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    @Test fun conversationModelIsBesideItsLabelAndSavesOnlyAfterConfirmation() {
        val status = JSONObject().put("installed", true).put("supported", true)
            .put("model", "model-a").put("availableModels", JSONArray(listOf("model-a", "model-b")))
        val saves = mutableListOf<JSONObject>()
        fixture { JarvisManagementPanel(status, false, saves::add, {}) }

        assertChoiceOnRight("Conversation model", "model-a")
        click("model-a")
        choose("model-b")
        assertChoiceOnRight("Conversation model", "model-b")
        assertTrue(saves.isEmpty())
        click("Save Jarvis settings")
        assertEquals(setOf("model"), saves.single().keys().asSequence().toSet())
        assertEquals("model-b", saves.single().getString("model"))
        assertEquals("model-a", status.getString("model"))
    }

    @Test fun sourceIsBesideItsLabelAndTheCredentialFormStaysFullWidthBelowIt() {
        val folder = "/sdcard/Pictures/Immortal"
        val sources = JSONObject().put("source", "folder").put("folderPath", folder)
        val saves = mutableListOf<JSONObject>()
        fixture { PhotoSourcesPanel(sources, false, saves::add, {}) }

        assertChoiceOnRight("Source", "Photos on this Portal")
        val picker = bounds(button("Photos on this Portal"))
        val field = bounds(compose.onNode(hasText("Folder on Portal") and hasSetTextAction()))
        assertTrue("The source form must start below the picker", field.top >= picker.bottom)
        assertTrue("The source form must extend to the left of the picker", field.left < picker.left)
        assertEquals("The source form must keep the card's full width",
            field.right, picker.right, 1f)

        click("Photos on this Portal")
        choose("Built-in photos")
        assertChoiceOnRight("Source", "Built-in photos")
        assertTrue(saves.isEmpty())
        click("Activate source")
        assertEquals(setOf("source"), saves.single().keys().asSequence().toSet())
        assertEquals("default", saves.single().getString("source"))
        assertEquals(folder, sources.getString("folderPath"))
    }

    @Test fun sceneChoicesStayOnTheRightAndKeepNavigationDraftsUntilSave() {
        val sceneName = "An evening scene for the living room"
        val scenes = JSONArray().put(JSONObject().put("id", "evening").put("name", sceneName)
            .put("steps", JSONArray().put(JSONObject().put("type", "key").put("action", "screensaver"))))
        val saves = mutableListOf<JSONArray>()
        fixture { ScenesPanel(scenes, false, saves::add, {}) }

        assertChoiceOnRight("Scene", sceneName)
        assertChoiceOnRight("Action type", "Navigation")
        assertChoiceOnRight("Navigation", "Open photo frame")
        click("Open photo frame")
        choose("Go home")
        assertChoiceOnRight("Navigation", "Go home")
        assertTrue(saves.isEmpty())
        click("Save scenes")
        val saved = saves.single().getJSONObject(0)
        assertEquals("evening", saved.getString("id"))
        assertEquals(sceneName, saved.getString("name"))
        assertEquals("home", saved.getJSONArray("steps").getJSONObject(0).getString("action"))
        assertEquals("screensaver", scenes.getJSONObject(0).getJSONArray("steps")
            .getJSONObject(0).getString("action"))
    }

    @Test fun advancedAppearanceUsesEqualWidthLocalizedSegmentsAndTheOriginalCallback() {
        var theme by mutableStateOf(ThemeMode.SYSTEM)
        val selections = mutableListOf<ThemeMode>()
        fixture(scroll = false) {
            SettingsTab(
                context = compose.activity,
                selectedLanguage = AppLanguage.SYSTEM,
                onSelectLanguage = {},
                themeMode = theme,
                onSelectThemeMode = { theme = it; selections.add(it) },
                useDynamicColor = false,
                onToggleDynamicColor = {},
            )
        }

        compose.onNodeWithTag("advanced-theme-SYSTEM").performScrollTo()
        compose.waitForIdle()
        val options = ThemeMode.entries.map { compose.onNodeWithTag("advanced-theme-${it.name}") }
        val segments = options.map(::bounds)
        val panel = bounds(compose.onNodeWithTag(VIEWPORT))
        val inset = 32 * compose.activity.resources.displayMetrics.density
        assertEquals("The pill must span the card's content width", panel.left + inset, segments.first().left, 1f)
        assertEquals("The pill must span the card's content width", panel.right - inset, segments.last().right, 1f)
        segments.forEach { segment ->
            assertEquals("Theme segments must have equal widths", segments.first().width, segment.width, 1f)
            assertEquals("Theme segments must stay on one row", segments.first().top, segment.top, 1f)
            assertEquals("Theme segments must have equal heights", segments.first().bottom, segment.bottom, 1f)
        }
        segments.zipWithNext().forEach { (left, right) ->
            assertEquals("Only the 1dp separator should divide segments",
                compose.activity.resources.displayMetrics.density, right.left - left.right, 1f)
        }
        val labelResources = listOf(R.string.settings_theme_system, R.string.settings_theme_light,
            R.string.settings_theme_dark)
        ThemeMode.entries.zip(labelResources).forEach { (mode, resource) ->
            compose.onNode(hasText(compose.activity.getString(resource)) and
                hasAnyAncestor(hasTestTag("advanced-theme-${mode.name}")),
                useUnmergedTree = true).assertExists()
        }

        options.first().assertIsSelected()
        options.last().performClick().assertIsSelected()
        assertEquals(listOf(ThemeMode.DARK), selections)
    }

    private fun assertChoiceOnRight(title: String, value: String) {
        val selector = button(value).performScrollTo()
        compose.waitForIdle()
        val label = bounds(compose.onNode(hasText(title) and
            !hasAnyAncestor(hasClickAction()) and !hasClickAction(), useUnmergedTree = true))
        val control = bounds(selector)
        val panel = bounds(compose.onNodeWithTag(VIEWPORT))
        assertTrue("$title must be to the left of its selector: label=$label, control=$control",
            label.right < control.left)
        assertTrue("$title and its selector must share a row",
            label.top < control.bottom && control.top < label.bottom)
        assertTrue("$title selector must stay inside the phone",
            control.width > 0 && control.left >= panel.left && control.right <= panel.right)
    }

    private fun bounds(node: SemanticsNodeInteraction): Rect {
        val measured = node.fetchSemanticsNode()
        return Rect(
            measured.positionInRoot.x,
            measured.positionInRoot.y,
            measured.positionInRoot.x + measured.size.width,
            measured.positionInRoot.y + measured.size.height,
        )
    }

    private fun button(text: String) = compose.onNode(
        hasText(text) and hasClickAction() and (
            !hasSetTextAction() or SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.DropdownList)))
    private fun click(text: String) { button(text).performScrollTo().performClick() }
    private fun choose(text: String) {
        compose.onNode(hasText(text) and hasClickAction() and hasAnyAncestor(isPopup())).performClick()
    }

    private fun fixture(scroll: Boolean = true, content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                MaterialTheme {
                    Box(Modifier.width(360.dp).height(640.dp).testTag(VIEWPORT)) {
                        if (scroll) {
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                                content()
                            }
                        } else {
                            content()
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private companion object {
        const val VIEWPORT = "selector-panel-viewport"
    }
}
