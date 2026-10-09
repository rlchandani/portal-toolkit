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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.portalinstaller.data.store.ThemeMode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingControlAlignmentUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val applied = mutableListOf<Pair<String, JSONObject>>()

    @Before fun awake() {
        compose.activityRule.scenario.onActivity {
            it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    @Test fun enumSelectorsAndPhotoStepperSitToTheRightOfTheirLabels() {
        showRegistry(1.1f)
        fields.forEach { (key, title) -> assertInline(key, title) }
    }

    @Test fun largeTextKeepsInlineSelectorsInsideTheCard() {
        showRegistry(2f)
        fields.forEach { (key, title) -> assertInline(key, title) }
    }

    @Test fun dropdownAndStepperStillApplyTheActualRegistryValues() {
        showRegistry(1.1f)
        compose.onNodeWithTag("registry-unit-value").performScrollTo().performClick()
        compose.onNodeWithText("Celsius").performClick()
        compose.onNodeWithTag("registry-interval-value").performScrollTo()
        compose.onNode(hasText("+") and hasAnyAncestor(hasTestTag("registry-interval-value")))
            .performClick()
        compose.runOnIdle {
            assertEquals("immortal", applied[0].first)
            assertEquals("celsius", applied[0].second.getString("unit"))
            assertEquals(35, applied[1].second.getInt("interval"))
        }
    }

    @Test fun dropdownMarksTheCurrentChoiceInsideItsMenu() {
        showRegistry(1.1f)
        compose.onNodeWithTag("registry-unit-value").performScrollTo().performClick()
        compose.onNode(hasText("Automatic") and hasAnyAncestor(isPopup())).assertIsSelected()
    }

    @Test fun segmentedAppearanceSelectionUpdatesAndStaysMutuallyExclusive() {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(320.dp)) {
                    var theme by remember { mutableStateOf(ThemeMode.SYSTEM) }
                    PhoneAppearanceSection(theme) { theme = it }
                }
            }
        }
        compose.onNodeWithTag("phone-theme-DARK").performClick().assertIsSelected()
        compose.onNodeWithTag("phone-theme-LIGHT").performClick().assertIsSelected()
        compose.onNodeWithTag("phone-theme-SYSTEM").performClick().assertIsSelected()
    }

    private fun assertInline(key: String, title: String) {
        val tag = "registry-$key-row"
        compose.onNodeWithTag(tag).performScrollTo()
        compose.waitForIdle()
        val row = measured(compose.onNodeWithTag(tag).fetchSemanticsNode())
        val label = measured(compose.onNode(
            hasText(title) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true
        ).fetchSemanticsNode())
        val selector = measured(compose.onNodeWithTag("registry-$key-value").fetchSemanticsNode())
        assertTrue("$title selector overlaps its label", selector.left >= label.right + 1)
        assertTrue("$title selector must share its label's row",
            selector.top < label.bottom && selector.bottom > label.top)
        assertTrue("$title selector exceeds the row", selector.right <= row.right + 1)
        assertTrue("$title label exceeds the row", label.left >= row.left - 1)
    }

    private fun measured(node: androidx.compose.ui.semantics.SemanticsNode) = Rect(
        node.positionInRoot.x, node.positionInRoot.y,
        node.positionInRoot.x + node.size.width, node.positionInRoot.y + node.size.height)

    private fun showRegistry(scale: Float) {
        val controls = JSONArray()
        fields.take(3).forEach { (key, title) ->
            controls.put(JSONObject().put("key", key).put("title", title).put("type", "enum")
                .put("value", "auto").put("options", JSONArray()
                    .put(JSONObject().put("value", "auto").put("label", "Automatic"))
                    .put(JSONObject().put("value", "celsius").put("label", "Celsius"))))
        }
        controls.put(JSONObject().put("key", "interval").put("title", "Photo interval")
            .put("type", "int").put("value", 30).put("display", "30s")
            .put("min", 5).put("max", 60).put("step", 5))
        val schema = JSONObject().put("domains", JSONArray().put(
            JSONObject().put("id", "immortal").put("title", "Immortal").put("controls", controls)))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                MaterialTheme {
                    Column(Modifier.width(320.dp).height(540.dp).verticalScroll(rememberScrollState())) {
                        RegistrySettingsPanel(schema, false, "", true, onApply = { id, body ->
                            applied.add(id to body)
                        })
                    }
                }
            }
        }
    }

    private val fields = listOf(
        "appearance" to "Appearance",
        "unit" to "Temperature unit",
        "ambient" to "Ambient sound",
        "interval" to "Photo interval",
    )
}
