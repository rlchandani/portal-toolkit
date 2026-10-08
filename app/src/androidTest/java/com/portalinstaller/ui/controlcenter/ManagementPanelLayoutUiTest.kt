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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyChild
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual panels constrained to a 360dp phone, with no transport or Portal device calls. */
@RunWith(AndroidJUnit4::class)
class ManagementPanelLayoutUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var fontScale by mutableStateOf(1.3f)

    @Before fun keepWindowAwake() {
        compose.activityRule.scenario.onActivity {
            it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    @Test fun sourceActionsKeepReadableLabelsAndTheirOriginalCallbacksAtLargeFonts() {
        val saves = mutableListOf<JSONObject>()
        var previews = 0
        fixture {
            PhotoSourcesPanel(JSONObject().put("source", "folder").put("folderPath", FOLDER),
                false, saves::add, { previews++ })
        }

        for (scale in listOf(1.3f, 2f)) {
            compose.runOnIdle { fontScale = scale }
            assertSingleLineLabel("Use this folder")
            assertSingleLineLabel("Preview on Portal")
            click("Use this folder")
            click("Preview on Portal")
            assertEquals(FOLDER, saves.last().getString("folderPath"))
            assertEquals(setOf("folderPath"), saves.last().keys().asSequence().toSet())
        }
        assertEquals(2, saves.size)
        assertEquals(2, previews)
    }

    @Test fun layoutMoveAndPageControlsKeepReadableLabelsAndPreserveTheOrderDraft() {
        val received = JSONObject().put("assignments", JSONObject()).put("widgets", JSONArray())
            .put("homeOrder", JSONArray((1..9).map { "com.example.app$it" }))
            .put("gridOrder", JSONArray()).put("gridSlots", JSONArray())
        val saves = mutableListOf<JSONObject>()
        fixture { LayoutEditorPanel(received, false, saves::add, {}) }
        click("Home app order")

        val firstRow = hasAnyAncestor(hasAnyChild(hasText("com.example.app1")))
        for (scale in listOf(1.3f, 2f)) {
            compose.runOnIdle { fontScale = scale }
            assertSingleLineLabel("Move up", firstRow)
            assertSingleLineLabel("Move down", firstRow)
            assertSingleLineLabel("Previous")
            assertSingleLineLabel("1–8 of 9")
            assertSingleLineLabel("Next")
            click("Next")
            compose.onNodeWithText("9–9 of 9").assertExists()
            click("Previous")
            compose.onNodeWithText("1–8 of 9").assertExists()
        }
        click("Move down", firstRow)
        click("Save layout")
        val order = saves.single().getJSONArray("homeOrder")
        assertEquals("com.example.app2", order.getString(0))
        assertEquals("com.example.app1", order.getString(1))
        assertEquals("com.example.app1", received.getJSONArray("homeOrder").getString(0))
    }

    @Test fun photoActionsAndPaginationKeepReadableLabelsAndRemainUsableAtLargeFonts() {
        val files = JSONArray((1..25).map { index ->
            JSONObject().put("name", "Photo $index").put("path", "$FOLDER/$index.jpg").put("modified", 1)
        })
        var imports = 0
        var refreshes = 0
        fixture {
            PhotoLibraryPanel("layout-fixture", files, FOLDER, false, null,
                { imports++ }, { refreshes++ }, {}, {}, {}, { null })
        }

        for (scale in listOf(1.3f, 2f)) {
            compose.runOnIdle { fontScale = scale }
            assertSingleLineLabel("Add photos")
            assertSingleLineLabel("Refresh")
            click("Add photos")
            click("Refresh")
            assertSingleLineLabel("Previous")
            assertSingleLineLabel("1 of 2")
            assertSingleLineLabel("Next")
            click("Next")
            compose.onNodeWithText("2 of 2").assertExists()
            click("Previous")
            compose.onNodeWithText("1 of 2").assertExists()
        }
        assertEquals(2, imports)
        assertEquals(2, refreshes)
    }

    private fun fixture(content: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                MaterialTheme {
                    Box(Modifier.width(360.dp).fillMaxSize().testTag("panel-viewport")) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                            content()
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun click(text: String, within: SemanticsMatcher? = null) {
        var matcher = hasText(text) and hasClickAction()
        if (within != null) matcher = matcher and within
        compose.onNode(matcher).performScrollTo().performClick()
    }

    private fun assertSingleLineLabel(text: String, within: SemanticsMatcher? = null) {
        var matcher = hasText(text)
        if (within != null) matcher = matcher and within
        val label = compose.onNode(matcher, useUnmergedTree = true).performScrollTo()
        val layouts = mutableListOf<TextLayoutResult>()
        label.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            assertTrue("Missing text layout for $text", action(layouts))
        }
        val result = layouts.single()
        assertFalse("$text exceeds its measured height at font scale $fontScale", result.didOverflowHeight)
        val glyphs = (0 until result.layoutInput.text.length).map { result.getBoundingBox(it) }
        assertTrue("$text glyphs exceed their measured width at font scale $fontScale; size=${result.size}; glyphs=$glyphs",
            glyphs.all { it.left >= -1f && it.right <= result.size.width + 1f })
        assertEquals("$text is squeezed into extra lines at font scale $fontScale", 1, result.lineCount)
        val bounds = label.fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("panel-viewport").fetchSemanticsNode().boundsInRoot
        assertTrue("$text leaves the phone's horizontal bounds at font scale $fontScale",
            bounds.width > 0 && bounds.left >= viewport.left && bounds.right <= viewport.right)
    }

    private companion object {
        const val FOLDER = "/sdcard/Pictures/Immortal"
    }
}
