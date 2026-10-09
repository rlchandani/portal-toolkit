/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.controlcenter

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.portalinstaller.viewmodel.RemoteTextController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Keyboard and gestures are tested without sending text or input to an owner's Portal. */
@RunWith(AndroidJUnit4::class)
class RemoteControlsPanelUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val device = mutableStateOf("fixture:1")
    private val enabled = mutableStateOf(true)
    private val sent = mutableListOf<String>()
    // Fixture owner deliberately outlives each activity/composition, just like the real ViewModel.
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var controller: RemoteTextController

    @After fun closeOwner() { controllerScope.cancel() }

    @Test fun keyboardIsOnDemandAndDismissingKeepsAnUnsentDraft() {
        show()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        open()
        field().performTextInput("Unsent fixture text")
        close()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        open()
        field().assertTextContains("Unsent fixture text")
        compose.runOnIdle { assertEquals(emptyList<String>(), sent) }
    }

    @Test fun aFailedSendKeepsTheDraftAndASuccessfulRetryClosesTheSheet() {
        var success = false
        show { text -> sent.add(text); success }
        open()
        field().performTextInput("Fixture retry")
        send()
        compose.onNodeWithText("Couldn’t confirm sending. Your draft is kept.").assertIsDisplayed()
        field().assertTextContains("Fixture retry")
        success = true
        send()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        open()
        assertEmptyDraft()
        compose.runOnIdle { assertEquals(listOf("Fixture retry", "Fixture retry"), sent) }
    }

    @Test fun pendingSendCannotBeSentTwiceAndOnlyClosesAfterConfirmation() {
        val response = CompletableDeferred<Boolean>()
        show { text -> sent.add(text); response.await() }
        open()
        field().performTextInput("One fixture request")
        send()
        compose.onNodeWithText("Sending…").assertIsNotEnabled()
        field().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(listOf("One fixture request"), sent) }
        response.complete(true)
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty()
        }
    }

    @Test fun pendingSendAndSuccessfulAcknowledgementSurviveActualActivityRecreation() {
        val response = CompletableDeferred<Boolean>()
        show { text -> sent.add(text); response.await() }
        open()
        field().performTextInput("Synthetic recreation draft")
        send()
        compose.onNodeWithText("Sending…").assertIsNotEnabled()

        recreateFixture()

        field().assertTextContains("Synthetic recreation draft").assertIsNotEnabled()
        compose.onNodeWithText("Sending…").assertIsNotEnabled()
        compose.runOnIdle {
            assertNull(controller.send(device.value))
            assertEquals(1, sent.size)
        }
        response.complete(true)
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty()
        }
        // The acknowledgement remains applied after another recreation, rather than restoring text.
        recreateFixture()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        open()
        assertEmptyDraft()
        compose.runOnIdle { assertEquals(1, sent.size) }
    }

    @Test fun failedAcknowledgementAfterActivityRecreationRetainsDraftAndError() {
        val response = CompletableDeferred<Boolean>()
        show { text -> sent.add(text); response.await() }
        open()
        field().performTextInput("Synthetic recreation retry")
        send()
        recreateFixture()
        response.complete(false)
        compose.waitUntil(5_000) { controller.state.value.error }
        compose.onNodeWithText("Couldn’t confirm sending. Your draft is kept.").assertIsDisplayed()
        field().assertTextContains("Synthetic recreation retry")
        recreateFixture()
        compose.onNodeWithText("Couldn’t confirm sending. Your draft is kept.").assertIsDisplayed()
        field().assertTextContains("Synthetic recreation retry")
        compose.runOnIdle { assertEquals(1, sent.size) }
    }

    @Test fun disconnectClosesTheKeyboardAndSelectionChangeDiscardsTheOldDraft() {
        show()
        open()
        field().performTextInput("Device-specific fixture")
        compose.runOnIdle { enabled.value = false }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText("Type on Portal").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Portal touchpad").assertIsNotEnabled()
        compose.runOnIdle { device.value = "fixture:2"; enabled.value = true }
        open()
        assertEmptyDraft()
        compose.runOnIdle { assertEquals(emptyList<String>(), sent) }
    }

    @Test fun largeFontKeyboardAllowsScrollingToSendAndClose() {
        show(fontScale = 2f)
        open()
        field().performTextInput("Readable fixture")
        compose.onNodeWithText("Send text").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Close keyboard").performScrollTo().assertIsDisplayed().performClick()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    private fun show(fontScale: Float = 1f, response: suspend (String) -> Boolean = { sent.add(it); true }) {
        controller = RemoteTextController(controllerScope) { _, text -> response(text) }
        controller.updateSession(device.value, enabled.value)
        compose.setContent { FixturePanel(fontScale) }
    }

    private fun recreateFixture() {
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent { FixturePanel() }
        }
        compose.waitForIdle()
    }

    @Composable
    private fun FixturePanel(fontScale: Float = 1f) {
        val key = device.value
        val connected = enabled.value
        // Only the fixture drives session observation here; production observes it in the ViewModel.
        LaunchedEffect(key, connected) { controller.updateSession(key, connected) }
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    RemoteControlsPanel(key, connected,
                        onNavigate = {}, onSlideshow = {}, onMove = { _, _ -> },
                        onTap = {}, onScroll = {}, textController = controller,
                        playback = { Text("Playback fixture") })
                }
            }
        }
    }

    private fun open() = compose.onNodeWithText("Type on Portal").performScrollTo().performClick()
    private fun close() = compose.onNodeWithContentDescription("Close keyboard").performClick()
    private fun field() = compose.onNodeWithTag("remote-text-input").assertIsDisplayed()
    private fun assertEmptyDraft() = field().assert(
        SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    private fun send() = compose.onNodeWithText("Send text").performScrollTo().performClick()
}
