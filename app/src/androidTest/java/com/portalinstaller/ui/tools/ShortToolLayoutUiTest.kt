/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.tools

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.portalinstaller.R
import com.portalinstaller.ui.tools.shell.ShellTab
import com.portalinstaller.ui.tools.shell.ToolTabLayout
import com.portalinstaller.viewmodel.ShellEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Shell UI and the shared Files/Logcat layout, without a ViewModel or transport. */
@RunWith(AndroidJUnit4::class)
class ShortToolLayoutUiTest {
    @get:Rule
    val compose = createComposeRule()

    private var fontScale by mutableStateOf(1.3f)
    private var pixelsPerDp = 1f
    private val labels = mutableMapOf<Int, String>()

    @Test
    fun keyboardSendUsesTheCommandHandlerWithoutASecondRunTap() {
        val sent = mutableListOf<String>()
        showTool {
            ShellTab(LocalContext.current, emptyList(), false, emptyList(), emptyList(),
                onRun = sent::add, onCancel = {}, onClear = {})
        }
        val field = compose.onNode(hasSetTextAction()).performScrollTo()
        field.performTextInput("  input fixture  ")
        field.performImeAction()
        compose.runOnIdle { assertEquals(listOf("input fixture"), sent) }
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    @Test
    fun keyboardSendKeepsDestructiveConfirmationAndRunningGuards() {
        var running by mutableStateOf(false)
        var sends = 0
        showTool {
            ShellTab(LocalContext.current, emptyList(), running, emptyList(), emptyList(),
                onRun = { sends++ }, onCancel = {}, onClear = {})
        }
        val field = compose.onNode(hasSetTextAction()).performScrollTo()
        field.performTextInput("reboot")
        field.performImeAction()
        compose.onNodeWithText(labels.getValue(R.string.shell_confirm_destructive_title))
            .assertExists()
        compose.onNodeWithText(labels.getValue(R.string.cancel)).performClick()
        compose.runOnIdle { assertEquals(0, sends); running = true }
        field.performScrollTo().performImeAction()
        compose.runOnIdle { assertEquals(0, sends) }
    }

    @Test
    fun emptyShellSendClearAndHistoryAreReachableAtShortHeightAndLargeFonts() {
        var clears = 0
        showTool {
            ShellTab(
                context = LocalContext.current,
                transcript = emptyList(),
                isRunning = false,
                commandHistory = listOf("history fixture"),
                quickCommands = listOf("quick fixture"),
                onRun = { throw AssertionError("This layout test must not run commands") },
                onCancel = {},
                onClear = { clears++ }
            )
        }

        for (scale in listOf(1.3f, 2f)) {
            compose.runOnIdle { fontScale = scale }
            assertReachable(action(R.string.shell_send))
            assertReachable(hasText("▲") and hasClickAction()).performClick()
            compose.onNode(hasSetTextAction()).assertTextContains("history fixture")
            assertReachable(action(R.string.shell_clear)).performClick()
        }
        compose.runOnIdle { assertEquals(2, clears) }
    }

    @Test
    fun populatedShellKeepsABoundedLazyTranscriptAndClearStillClearsIt() {
        val entries = (0 until 200).map { ShellEntry("fixture $it", "output $it", false) }
        var transcript by mutableStateOf(entries)
        var clears = 0
        showTool {
            ShellTab(
                context = LocalContext.current,
                transcript = transcript,
                isRunning = false,
                commandHistory = emptyList(),
                quickCommands = listOf("quick fixture"),
                onRun = { throw AssertionError("This layout test must not run commands") },
                onCancel = {},
                onClear = { transcript = emptyList(); clears++ }
            )
        }

        for (scale in listOf(1.3f, 2f)) {
            compose.runOnIdle { fontScale = scale; transcript = entries }
            assertBoundedLazyList()
            assertReachable(action(R.string.shell_send))
            assertReachable(action(R.string.shell_clear)).performClick()
            compose.onNodeWithText(labels.getValue(R.string.shell_empty)).assertExists()
        }
        compose.runOnIdle { assertEquals(2, clears) }
    }

    @Test
    fun runningShellCancelAndClearRemainReachableWithWrappedActions() {
        var cancels = 0
        var clears = 0
        showTool {
            ShellTab(
                context = LocalContext.current,
                transcript = emptyList(),
                isRunning = true,
                commandHistory = emptyList(),
                quickCommands = listOf("quick fixture"),
                onRun = { throw AssertionError("This layout test must not run commands") },
                onCancel = { cancels++ },
                onClear = { clears++ }
            )
        }

        for (scale in listOf(1.3f, 2f)) {
            compose.runOnIdle { fontScale = scale }
            assertReachable(action(R.string.shell_cancel)).performClick()
            assertReachable(action(R.string.shell_clear)).performClick()
        }
        compose.runOnIdle {
            assertEquals(2, cancels)
            assertEquals(2, clears)
        }
    }

    @Test
    fun sharedLayoutScrollsOverflowChromeAndKeepsItsNestedListUsable() {
        // Extra controls/status reproduce Files selection/transfer and Logcat crash chrome.
        var clicks = 0
        showTool {
            ToolTabLayout { listModifier ->
                Box(Modifier.height(240.dp)) { Text("Overflow controls and status") }
                TextButton(onClick = { clicks++ }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Reachable action")
                }
                LazyColumn(modifier = listModifier) {
                    items((0 until 200).toList()) { Text("List fixture $it") }
                }
            }
        }

        for (scale in listOf(1.3f, 2f)) {
            compose.runOnIdle { fontScale = scale }
            assertReachable(hasText("Reachable action") and hasClickAction()).performClick()
            assertBoundedLazyList()
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(199)
            compose.onNodeWithText("List fixture 199").assertIsDisplayed()
        }
        compose.runOnIdle { assertEquals(2, clicks) }
    }

    private fun showTool(content: @Composable () -> Unit) {
        compose.setContent {
            val context = LocalContext.current
            listOf(R.string.shell_send, R.string.shell_clear, R.string.shell_cancel, R.string.shell_empty,
                R.string.shell_confirm_destructive_title, R.string.cancel)
                .forEach { labels[it] = context.getString(it) }
            pixelsPerDp = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(pixelsPerDp, fontScale)) {
                MaterialTheme {
                    Layout(content = {
                        Box(Modifier.fillMaxSize().testTag(VIEWPORT)) { content() }
                    }) { measurables, constraints ->
                        val width = 360.dp.roundToPx().coerceAtMost(constraints.maxWidth)
                        val height = 170.dp.roundToPx().coerceAtMost(constraints.maxHeight)
                        val panel = measurables.single().measure(Constraints.fixed(width, height))
                        layout(width, height) { panel.place(0, 0) }
                    }
                }
            }
        }
        compose.waitForIdle()
        val viewport = compose.onNodeWithTag(VIEWPORT).fetchSemanticsNode()
        assertEquals("Fixture must exercise the requested width", 360f * pixelsPerDp, viewport.size.width.toFloat(), 1f)
        assertEquals("Fixture must exercise the requested short height", 170f * pixelsPerDp, viewport.size.height.toFloat(), 1f)
    }

    private fun action(resource: Int) = hasText(labels.getValue(resource)) and hasClickAction()

    private fun assertReachable(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        val action = compose.onNode(matcher).performScrollTo().assertIsDisplayed()
        assertInsideViewport(action)
        val size = action.fetchSemanticsNode().size
        assertTrue("Action needs a full 48dp touch width", size.width >= 48f * pixelsPerDp - 1f)
        assertTrue("Action needs a full 48dp touch height; measured=$size",
            size.height >= 48f * pixelsPerDp - 1f)
        return action
    }

    private fun assertBoundedLazyList() {
        val list = compose.onNode(hasScrollToIndexAction()).performScrollTo().assertIsDisplayed()
        assertInsideViewport(list)
        val height = list.fetchSemanticsNode().size.height
        assertTrue("List must retain a usable height", height >= 96f * pixelsPerDp - 1f)
        assertTrue("Compact lazy list must stay bounded", height <= 240f * pixelsPerDp + 1f)
    }

    private fun assertInsideViewport(node: SemanticsNodeInteraction) {
        val viewport = compose.onNodeWithTag(VIEWPORT).fetchSemanticsNode().boundsInRoot
        val measured = node.fetchSemanticsNode()
        // Unclipped bounds: a partially visible action must not satisfy containment.
        val bounds = Rect(
            measured.positionInRoot.x, measured.positionInRoot.y,
            measured.positionInRoot.x + measured.size.width,
            measured.positionInRoot.y + measured.size.height
        )
        assertTrue("Left edge clipped at font $fontScale: $bounds", bounds.left >= viewport.left - 1f)
        assertTrue("Right edge clipped at font $fontScale: $bounds", bounds.right <= viewport.right + 1f)
        assertTrue("Top edge clipped at font $fontScale: $bounds", bounds.top >= viewport.top - 1f)
        assertTrue("Bottom edge clipped at font $fontScale: $bounds", bounds.bottom <= viewport.bottom + 1f)
    }

    private companion object {
        const val VIEWPORT = "short-tool-viewport"
    }
}
