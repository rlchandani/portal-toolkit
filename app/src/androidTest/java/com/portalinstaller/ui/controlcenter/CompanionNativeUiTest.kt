/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.controlcenter

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.portalinstaller.MainActivity
import com.portalinstaller.data.companion.CompanionClient
import com.portalinstaller.data.companion.CompanionSession
import com.portalinstaller.data.companion.FileCompanionSessionStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in tests of the real application, using pairing only inside its process. */
@RunWith(AndroidJUnit4::class)
class CompanionNativeUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var session: CompanionSession
    private val client = CompanionClient()

    @Before fun pairedApplication() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("livePortal") == "1")
        val saved = FileCompanionSessionStore.create(
            InstrumentationRegistry.getInstrumentation().targetContext).load()
        assumeTrue("Pair exactly one Portal before running live UI tests.", saved.size == 1)
        session = saved.single()
        waitForText("Home layout")
    }

    @Test fun remoteKeyboardOpensOnDemandAndCanCloseWithoutSending() {
        selectTab("Remote")
        waitForText("Touchpad")
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText("Type on Portal").performScrollTo().performClick()
        compose.onNodeWithTag("remote-text-input").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close keyboard").performClick()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText("Touchpad").assertIsDisplayed()
    }

    @Test fun nativeTabsAndManagementPagesStayInTheMainActivity() {
        val tabs = listOf(
            "Remote" to "Control the Portal without crossing the room.",
            "Photos" to "A collection that stays on your Portal.",
            "Apps" to "Find an app",
            "Settings" to "Search settings",
        )
        for ((label, marker) in tabs) {
            selectTab(label)
            waitForText(marker)
            compose.onNodeWithText(marker).assertIsDisplayed()
            assertEquals(MainActivity::class.java, compose.activity.javaClass)
        }
        selectTab("Home")
        val details = listOf(
            "Home layout" to "Arrange widgets and favorites on your Portal.",
            "Scenes" to "Save routines and run them on your Portal.",
            "Jarvis" to "Voice assistant and local wake options.",
            "Snapshots" to "Keep a copy of your settings and home layout.",
            "Activity" to "Installations, routines, and retained results.",
        )
        for ((label, marker) in details) {
            waitForText(label)
            compose.onNodeWithText(label).performScrollTo().performClick()
            waitForText(marker)
            compose.onNodeWithText(marker).assertIsDisplayed()
            assertEquals(MainActivity::class.java, compose.activity.javaClass)
            compose.onNodeWithText("‹ Back").performScrollTo().performClick()
            waitForText("Your Portal")
        }
        selectTab("Settings")
        waitForText("Search settings")
        compose.onNode(hasSetTextAction() and hasText("Search settings"))
            .performTextReplacement("companion-qa-no-match")
        compose.onNodeWithText("Device tools").performScrollTo().performClick()
        val returnLabel = "‹ Immortal control center"
        waitForText(returnLabel)
        val statusTop = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            ?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
        val back = compose.onNodeWithText(returnLabel)
        back.assertIsDisplayed()
        assertTrue("The tools return button overlaps the status bar.",
            back.fetchSemanticsNode().boundsInWindow.top >= statusTop)
        back.performClick()
        waitForText("Search settings")
    }

    @Test fun nativeSettingAppliesAndToolbarRefreshesTheCurrentPage() = runBlocking {
        val original = weatherUnit()
        val changed = if (original == "c") "f" else "c"
        val originalLabel = unitLabel(original)
        val changedLabel = unitLabel(changed)
        try {
            selectTab("Settings")
            waitForText("Search settings")
            compose.onNode(hasSetTextAction() and hasText("Search settings"))
                .performTextReplacement("Temperature unit")
            waitForText(originalLabel)
            compose.onNodeWithText(originalLabel).performScrollTo().performClick()
            compose.onNodeWithText(changedLabel).performClick()
            compose.waitUntil(10_000) {
                runBlocking { weatherUnit() == changed }
            }
            waitForText(changedLabel)
            // Change the Portal externally, then prove the toolbar refreshes settings,
            // rather than merely updating the Home status.
            setWeatherUnit(original)
            compose.onNodeWithContentDescription("Refresh current page").performClick()
            waitForText(originalLabel)
            compose.onNodeWithText(originalLabel).assertIsDisplayed()
            assertEquals(original, weatherUnit())
        } finally {
            setWeatherUnit(original)
        }
    }

    private suspend fun weatherUnit(): String {
        val domains = client.get(session, "settings").getJSONObject("settings")
            .getJSONArray("domains").objects()
        return domains.single { it.getString("id") == "immortal" }
            .getJSONArray("controls").objects().single { it.getString("key") == "weatherUnit" }
            .getString("value")
    }

    private suspend fun setWeatherUnit(value: String) {
        client.post(session, "settings", JSONObject().put("domain", "immortal")
            .put("values", JSONObject().put("weatherUnit", value)))
    }

    private fun unitLabel(value: String): String = when (value) {
        "c" -> "Celsius"
        "f" -> "Fahrenheit"
        else -> "Auto"
    }

    private fun selectTab(label: String) {
        compose.onNode(hasText(label) and SemanticsMatcher.expectValue(
            SemanticsProperties.Role, Role.Tab)).performClick()
    }

    private fun waitForText(text: String) {
        compose.waitUntil(10_000) {
            runCatching { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
                .getOrDefault(false)
        }
        compose.waitForIdle()
    }
}
