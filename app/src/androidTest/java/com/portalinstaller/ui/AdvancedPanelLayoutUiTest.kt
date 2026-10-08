/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.portalinstaller.R
import com.portalinstaller.data.PortalAppInfo
import com.portalinstaller.ui.apps.PortalAppsTab
import com.portalinstaller.ui.tools.mirror.ControlBar
import com.portalinstaller.viewmodel.PortalAppsSort
import com.portalinstaller.viewmodel.UninstallState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Narrow layouts with enlarged text, using callbacks only and no Portal connection. */
@RunWith(AndroidJUnit4::class)
class AdvancedPanelLayoutUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val labels = mutableMapOf<Int, String>()

    @Test
    fun mirrorActionsWrapAndRemainInsideANarrowPanel() {
        val actions = listOf(
            R.string.mirror_key_back,
            R.string.mirror_key_home,
            R.string.mirror_key_recents,
            R.string.mirror_text_open,
            R.string.mirror_save
        )
        showPanel(actions) {
            ControlBar(onKey = {}, onOpenKeyboard = {}, onSaveFrame = {})
        }
        assertActionsInsidePanel(actions)
        assertTrue(actionBounds(actions.last()).top > actionBounds(actions.first()).top)
    }

    @Test
    fun portalAppActionsWrapAndRemainInsideANarrowPanel() {
        val actions = listOf(
            R.string.apps_launch,
            R.string.apps_force_stop,
            R.string.apps_clear_data,
            R.string.uninstall
        )
        showPanel(actions) {
            val app = PortalAppInfo(
                packageName = "com.example.layoutfixture",
                name = "Layout fixture",
                versionName = "1.0",
                versionCode = 1L,
                sizeBytes = 1024L,
                iconApkPath = null
            )
            PortalAppsTab(
                packages = listOf(app.packageName),
                apps = listOf(app),
                isLoading = false,
                uninstallState = UninstallState.Idle,
                sort = PortalAppsSort.NAME,
                sessionGeneration = 41L,
                portalIdentity = "QA Portal",
                onSortChange = {},
                onRefresh = {},
                onUninstall = { _, _ -> },
                onLaunch = {},
                onForceStop = {},
                onClearData = { _, _ -> }
            )
        }
        assertActionsInsidePanel(actions)
        assertTrue(actionBounds(actions.last()).top > actionBounds(actions.first()).top)
    }

    private fun showPanel(actionResources: List<Int>, content: @Composable () -> Unit) {
        compose.setContent {
            val context = LocalContext.current
            actionResources.forEach { labels[it] = context.getString(it) }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                MaterialTheme {
                    Layout(content = {
                        Box(Modifier.fillMaxSize().testTag(PANEL), content = { content() })
                    }) { measurables, constraints ->
                        val width = 280.dp.roundToPx().coerceAtMost(constraints.maxWidth)
                        val height = 440.dp.roundToPx().coerceAtMost(constraints.maxHeight)
                        val panel = measurables.single().measure(Constraints.fixed(width, height))
                        layout(width, height) { panel.place(0, 0) }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun assertActionsInsidePanel(actions: List<Int>) {
        val panel = compose.onNodeWithTag(PANEL).fetchSemanticsNode().boundsInRoot
        actions.forEach { resource ->
            val bounds = actionBounds(resource)
            assertTrue("${labels[resource]} has no visible size", bounds.width > 0 && bounds.height > 0)
            assertTrue("${labels[resource]} extends left of panel", bounds.left >= panel.left - 1f)
            assertTrue("${labels[resource]} extends right of panel", bounds.right <= panel.right + 1f)
            assertTrue("${labels[resource]} extends above panel", bounds.top >= panel.top - 1f)
            assertTrue("${labels[resource]} extends below panel", bounds.bottom <= panel.bottom + 1f)
        }
    }

    private fun actionBounds(resource: Int): Rect {
        val node = compose.onNode(hasText(labels.getValue(resource)) and hasClickAction())
            .fetchSemanticsNode()
        // Use the measured size so partial clipping cannot satisfy containment.
        return Rect(
            node.positionInRoot.x,
            node.positionInRoot.y,
            node.positionInRoot.x + node.size.width,
            node.positionInRoot.y + node.size.height
        )
    }

    private companion object {
        const val PANEL = "advanced-layout-panel"
    }
}
