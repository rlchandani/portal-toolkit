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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.portalinstaller.data.store.ThemeMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class SettingsLayoutUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun keepWindowAwake() {
        compose.activityRule.scenario.onActivity {
            it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    @Test fun settingsSearchAndAdvancedCardHaveAVisibleGap() {
        showSettings()
        assertGap("settings-search", "settings-advanced")
    }

    @Test fun appearanceAndDeviceToolsCardsHaveAVisibleGap() {
        showSettings()
        assertGap("phone-appearance", "settings-device-tools")
    }

    @Test fun multipleMatchingDomainsHaveSeparatedCardsAndTitles() {
        showSettings(query = "clock")
        assertGap("settings-domain-immortal-card", "settings-domain-digitalclock-title")
    }

    private fun assertGap(firstTag: String, secondTag: String) {
        compose.onNodeWithTag(secondTag).performScrollTo()
        compose.waitForIdle()
        val first = compose.onNodeWithTag(firstTag).fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag(secondTag).fetchSemanticsNode().boundsInRoot
        assertTrue("$firstTag and $secondTag need at least 12dp separation; gap=${second.top-first.bottom}",
            second.top - first.bottom >= 12 * compose.activity.resources.displayMetrics.density - 1)
    }

    @Test fun largeTextThemeChoicesShareOneFullWidthPillEqually() {
        showSettings(fontScale = 2f, width = 280)
        compose.onNodeWithTag("phone-theme-choices").performScrollTo()
        compose.waitForIdle()
        val parent = compose.onNodeWithTag("phone-theme-choices").fetchSemanticsNode().boundsInRoot
        val choices = ThemeMode.entries.map {
            compose.onNodeWithTag("phone-theme-${it.name}").fetchSemanticsNode().boundsInRoot
        }
        choices.forEach { bounds ->
            assertTrue("Theme option clipped horizontally", bounds.left >= parent.left - 1 && bounds.right <= parent.right + 1)
            assertTrue("Theme option clipped vertically", bounds.top >= parent.top - 1 && bounds.bottom <= parent.bottom + 1)
        }
        assertTrue("Theme options must stay in one segmented row", choices.map { it.top }.distinct().size == 1)
        assertTrue("Theme options must have equal widths", choices.maxOf { it.width } - choices.minOf { it.width } <= 1f)
        assertTrue("Theme options must have equal heights", choices.maxOf { it.height } - choices.minOf { it.height } <= 1f)
        val separatorWidth = 2 * compose.activity.resources.displayMetrics.density
        assertTrue("Segmented control must fill its available width",
            kotlin.math.abs(choices.sumOf { it.width.toDouble() } + separatorWidth - parent.width) <= 2)
    }

    @Test fun increasedTextKeepsSearchAndAdvancedSectionsSeparated() {
        showSettings(fontScale = 1.3f)
        assertGap("settings-search", "settings-advanced")
    }

    private fun schema() = JSONObject().put("domains", JSONArray()
        .put(JSONObject().put("id", "immortal").put("title", "Immortal").put("controls", JSONArray()
            .put(JSONObject().put("key", "clock").put("title", "Clock").put("type", "bool").put("value", true))))
        .put(JSONObject().put("id", "digitalclock").put("title", "Digital clock").put("controls", JSONArray()
            .put(JSONObject().put("key", "clockSize").put("title", "Clock size").put("type", "int").put("value", 2)
                .put("min", 1).put("max", 5)))))

    private fun showSettings(query: String = "", fontScale: Float = 1f, width: Int = 360) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                MaterialTheme {
                Box(Modifier.width(width.dp).height(600.dp)) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                        SettingsPageContent(query, {}, false, {}, domainPicker = {
                            Text("Immortal", Modifier.testTag("domain-picker"))
                        }, portalSettings = {
                            RegistrySettingsPanel(schema(), false, query, false,
                                onlyDomain = if (query.isBlank()) "immortal" else null, onApply = { _, _ -> })
                        }, phonePreferences = {
                            PhoneAppearanceSection(ThemeMode.SYSTEM, {})
                        }, onTools = {}, onPair = {})
                    }
                }
            }
            }
        }
        compose.waitForIdle()
    }
}
