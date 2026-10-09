/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.common

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Shared controls in isolation; AwakeTestRunner keeps the fixture activity visible. */
@RunWith(AndroidJUnit4::class)
class PremiumControlsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun dropdownEmitsTheRawValueOnceUpdatesItsLabelAndDismisses() {
        var value by mutableStateOf("model/default")
        val selections = mutableListOf<String>()
        fixture {
            ChoiceDropdown(
                value = value,
                options = modelOptions,
                onSelect = { value = it; selections.add(it) },
                modifier = Modifier.testTag(CHOICE),
                accessibilityLabel = "Conversation model",
                matchAnchorWidth = true,
            )
        }

        selector().assertIsEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.DropdownList))
            .performTouchInput { click() }
        assertEquals("A wide selector's menu must keep the same width",
            bounds(selector()).width, bounds(compose.onNode(isPopup())).width, 1f)
        menuOption("Quiet conversation").assertIsDisplayed().performClick()

        compose.onNode(isPopup()).assertDoesNotExist()
        selector().assert(hasText("Quiet conversation") or
            hasAnyDescendant(hasText("Quiet conversation")))
        compose.runOnIdle {
            assertEquals(listOf("model/quiet-v2"), selections)
            assertEquals("model/quiet-v2", value)
        }
    }

    @Test fun disabledDropdownCannotOpenOrEmitAChoice() {
        val selections = mutableListOf<String>()
        fixture {
            ChoiceDropdown(
                value = "model/default",
                options = modelOptions,
                onSelect = { selections.add(it) },
                modifier = Modifier.testTag(CHOICE),
                enabled = false,
            )
        }

        selector().assertIsNotEnabled().performTouchInput { click() }

        compose.onNode(isPopup()).assertDoesNotExist()
        compose.runOnIdle { assertTrue(selections.isEmpty()) }
    }

    @Test fun disabledMenuOptionCannotEmitOrDismissTheEnabledMenu() {
        val selections = mutableListOf<String>()
        fixture {
            ChoiceDropdown(
                value = "model/default",
                options = modelOptions + ChoiceOption(
                    "model/unavailable", "Unavailable conversation", enabled = false),
                onSelect = { selections.add(it) },
                modifier = Modifier.testTag(CHOICE),
            )
        }

        selector().performTouchInput { click() }
        menuOption("Unavailable conversation").assertIsNotEnabled()
            .performTouchInput { click() }

        compose.onNode(isPopup()).assertExists()
        compose.runOnIdle { assertTrue(selections.isEmpty()) }
        menuOption("Quiet conversation").assertIsEnabled().performClick()
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("model/quiet-v2"), selections) }
    }

    @Test fun longDropdownMenuCanScrollToAndChooseItsLastOption() {
        val options = List(60) { ChoiceOption("source/$it", "Photo source $it") }
        val selections = mutableListOf<String>()
        fixture(fontScale = 2f) {
            ChoiceDropdown(
                value = options.first().value,
                options = options,
                onSelect = { selections.add(it) },
                modifier = Modifier.testTag(CHOICE),
            )
        }

        selector().performTouchInput { click() }
        compose.onNode(hasScrollAction() and hasAnyAncestor(isPopup())).assertExists()
        assertTrue("Long menus must remain compact within the device window",
            bounds(compose.onNode(isPopup())).height <=
                360 * compose.activity.resources.displayMetrics.density + 1)
        menuOption(options.last().label).assertIsNotDisplayed()
            .performScrollTo().assertIsDisplayed().performTouchInput { click() }

        compose.onNode(isPopup()).assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("source/59"), selections) }
    }

    @Test fun twoJoinedActionsHaveEqualAccessibleBoundsAtNarrowWidthAndDoubleFontScale() {
        val labels = listOf("Save changes", "Discard changes")
        fixture(fontScale = 2f) {
            JoinedActionGroup(
                actions = labels.mapIndexed { index, label ->
                    JoinedAction(label, {}, emphasized = index == 0)
                },
                modifier = Modifier.testTag(ACTIONS),
            )
        }

        assertJoinedBounds(labels)
    }

    @Test fun threeJoinedActionsHaveEqualAccessibleBoundsAtNarrowWidthAndDoubleFontScale() {
        val labels = listOf("Save changes", "Preview on Portal", "Discard changes")
        fixture(fontScale = 2f) {
            JoinedActionGroup(
                actions = labels.mapIndexed { index, label ->
                    JoinedAction(label, {}, emphasized = index == 0)
                },
                modifier = Modifier.testTag(ACTIONS),
            )
        }

        assertJoinedBounds(labels)
    }

    @Test fun joinedActionsKeepButtonSemanticsAndGuardCallbacksAsEnabledStateChanges() {
        var enabled by mutableStateOf(true)
        val calls = mutableListOf<String>()
        val labels = listOf("Save changes", "Discard changes", "Preview")
        fixture {
            JoinedActionGroup(
                actions = listOf(
                    JoinedAction(labels[0], { calls.add("save") },
                        enabled = enabled, emphasized = true),
                    JoinedAction(labels[1], { calls.add("discard") }, enabled = enabled),
                    JoinedAction(labels[2], { calls.add("preview") }, enabled = false),
                ),
                modifier = Modifier.testTag(ACTIONS),
            )
        }

        labels.forEach { action(it).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected)) }
        action(labels[0]).assertIsEnabled().performClick()
        action(labels[1]).assertIsEnabled().performClick()
        action(labels[2]).assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(listOf("save", "discard"), calls)
            enabled = false
        }

        labels.forEach { label ->
            action(label).assertIsNotEnabled()
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
                .performTouchInput { click() }
        }
        compose.runOnIdle {
            assertEquals(listOf("save", "discard"), calls)
            enabled = true
        }
        action(labels[0]).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("save", "discard", "save"), calls) }
    }

    private fun assertJoinedBounds(labels: List<String>) {
        val viewport = bounds(compose.onNodeWithTag(VIEWPORT))
        val group = bounds(compose.onNodeWithTag(ACTIONS))
        assertEquals("The action group must fill the available width", viewport.width, group.width, 1f)
        val buttons = labels.map { label ->
            val button = action(label).assertIsDisplayed().assertIsEnabled()
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
            bounds(button)
        }
        val minHeight = 48 * compose.activity.resources.displayMetrics.density
        buttons.forEachIndexed { index, button ->
            assertTrue("${labels[index]} must have a usable touch target",
                button.width > 0 && button.height >= minHeight - 1)
            assertEquals("Actions must have equal widths", buttons.first().width, button.width, 1f)
            assertEquals("Actions must have equal heights", buttons.first().height, button.height, 1f)
            assertEquals("Actions must stay on one row", buttons.first().top, button.top, 1f)
            assertInside("${labels[index]} button", button, group)

            val label = bounds(compose.onNode(
                hasText(labels[index]) and withinTag(ACTIONS) and !hasClickAction(),
                useUnmergedTree = true,
            ))
            assertInside("${labels[index]} label", label, button)
        }
        buttons.zipWithNext().forEach { (left, right) ->
            assertTrue("Adjacent action touch targets must not overlap", left.right <= right.left)
        }
    }

    private fun assertInside(description: String, child: Rect, parent: Rect) {
        assertTrue("$description exceeds its available bounds: child=$child, parent=$parent",
            child.left >= parent.left - 1 && child.right <= parent.right + 1 &&
                child.top >= parent.top - 1 && child.bottom <= parent.bottom + 1)
    }

    /** Layout bounds avoid mistaking a clipped semantics rectangle for a correctly sized button. */
    private fun bounds(node: SemanticsNodeInteraction): Rect {
        val measured = node.fetchSemanticsNode()
        return Rect(
            measured.positionInRoot.x,
            measured.positionInRoot.y,
            measured.positionInRoot.x + measured.size.width,
            measured.positionInRoot.y + measured.size.height,
        )
    }

    // The tag is on the non-editable anchor, independently of its menu's option nodes.
    private fun selector() = compose.onNodeWithTag(CHOICE)

    // The selected label also appears in the anchor; only popup items are menu choices.
    private fun menuOption(label: String) = compose.onNode(
        hasText(label) and hasClickAction() and hasAnyAncestor(isPopup()))

    private fun action(label: String) = compose.onNode(
        hasText(label) and withinTag(ACTIONS) and
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))

    private fun withinTag(tag: String) = hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))

    private fun fixture(fontScale: Float = 1f, content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
            ) {
                MaterialTheme {
                    Column(Modifier.width(280.dp).testTag(VIEWPORT)) { content() }
                }
            }
        }
        compose.waitForIdle()
    }

    private companion object {
        const val VIEWPORT = "premium-controls-viewport"
        const val CHOICE = "premium-choice-field"
        const val ACTIONS = "premium-joined-actions"
        val modelOptions = listOf(
            ChoiceOption("model/default", "Everyday conversation"),
            ChoiceOption("model/quiet-v2", "Quiet conversation"),
        )
    }
}
