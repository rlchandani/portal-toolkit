/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.controlcenter

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.safeDrawing
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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.portalinstaller.data.companion.CompanionConnection
import com.portalinstaller.data.companion.CompanionDevice
import com.portalinstaller.data.companion.ControlCenterState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The real header at phone widths, without pairing, transport, or device operations. */
@RunWith(AndroidJUnit4::class)
class CompactPortalHeaderUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun ordinaryHeaderStays64DpWithAccessibleTouchTargets() {
        fixture { header(selectedState(kitchen.copy(name = LONG_NAME))) }

        val density = compose.activity.resources.displayMetrics.density
        val viewport = bounds(compose.onNodeWithTag(VIEWPORT))
        assertEquals("The header must retain the native small app bar height",
            64 * density, viewport.height, 1f)
        for (control in listOf(selector(), refresh())) {
            control.assertIsDisplayed().assertIsEnabled()
            val target = bounds(control)
            assertTrue("Header controls need at least 48dp touch targets",
                target.width >= 48 * density - 1 && target.height >= 48 * density - 1)
            assertInside("Header control", target, viewport)
        }
        selector().assert(SemanticsMatcher.expectValue(
            SemanticsProperties.Role, Role.DropdownList))
    }

    @Test fun longNameAt280DpAndDoubleFontScaleCannotOverlapOrObstructRefresh() {
        val portal = kitchen.copy(name = LONG_NAME)
        var refreshes = 0
        fixture(fontScale = 2f) {
            header(selectedState(portal), onRefresh = { refreshes++ })
        }

        val viewport = bounds(compose.onNodeWithTag(VIEWPORT))
        val anchor = bounds(selector().assertIsDisplayed())
        val refreshBounds = bounds(refresh().assertIsDisplayed().assertIsEnabled())
        val name = compose.onNodeWithText(LONG_NAME, useUnmergedTree = true)
            .assertIsDisplayed()
        val nameBounds = bounds(name)
        assertInside("Portal selector", anchor, viewport)
        assertInside("Refresh button", refreshBounds, viewport)
        assertInside("Portal name", nameBounds, anchor)
        assertTrue("The selector touch target must stop before refresh",
            anchor.right <= refreshBounds.left)
        assertTrue("The long name must stop before refresh",
            nameBounds.right <= refreshBounds.left)
        val layouts = mutableListOf<TextLayoutResult>()
        name.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            assertTrue("The Portal name needs a measurable text layout", action(layouts))
        }
        val layout = layouts.single()
        assertTrue("A long Portal name must use at most two lines", layout.lineCount in 1..2)
        assertTrue("The long name must ellipsize within its allotted space",
            layout.isLineEllipsized(layout.lineCount - 1))
        assertTrue("Name glyphs must fit the measured title height",
            layout.getLineBottom(layout.lineCount - 1) <= layout.size.height + 1)
        refresh().performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, refreshes) }
    }

    @Test fun allFiveConnectionStatesHaveTextAndAccessibleFullStatus() {
        var state by mutableStateOf(ControlCenterState())
        fixture(fontScale = 2f) { header(state) }

        val statuses = listOf(
            CompanionConnection.UNPAIRED to "No Portal selected",
            CompanionConnection.CONNECTING to "Connecting…",
            CompanionConnection.CONNECTED to "Connected",
            CompanionConnection.OFFLINE to "Offline · Waiting to reconnect",
            CompanionConnection.UNAUTHORIZED to "Pairing expired · Pair again",
        )
        for ((connection, label) in statuses) {
            compose.runOnIdle {
                state = if (connection == CompanionConnection.UNPAIRED) ControlCenterState()
                    else selectedState().copy(connection = connection)
            }
            compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
            selector().assert(SemanticsMatcher("Announces the full connection status") { node ->
                node.config.getOrNull(SemanticsProperties.StateDescription)
                    ?.contains(label) == true
            })
        }
    }

    @Test fun menuSelectionEmitsTheRawIdOnceUpdatesSelectionAndDismisses() {
        var state by mutableStateOf(selectedState())
        val selected = mutableListOf<String>()
        fixture {
            header(state, onSelect = { id ->
                selected.add(id)
                state = state.copy(activeDeviceId = id)
            })
        }

        selector().performTouchInput { click() }
        menuItem(kitchen.name).assert(SemanticsMatcher.expectValue(
            SemanticsProperties.Selected, true))
        menuItem(studio.name).assert(SemanticsMatcher.expectValue(
            SemanticsProperties.Selected, false)).performClick()
        compose.onNode(isPopup()).assertDoesNotExist()
        selector().assert(hasText(studio.name))
        compose.runOnIdle { assertEquals(listOf("portal/raw:studio-02"), selected) }

        selector().performClick()
        menuItem(studio.name).assert(SemanticsMatcher.expectValue(
            SemanticsProperties.Selected, true))
        menuItem(kitchen.name).assert(SemanticsMatcher.expectValue(
            SemanticsProperties.Selected, false))
    }

    @Test fun manySavedPortalsUseABoundedScrollableNativeMenu() {
        val devices = List(60) { index ->
            CompanionDevice("portal/raw:$index", "Saved Portal $index", "http://portal$index.local:8723")
        }
        val selected = mutableListOf<String>()
        fixture(fontScale = 2f) {
            header(ControlCenterState(devices = devices, activeDeviceId = devices.first().id),
                onSelect = { selected.add(it) })
        }

        selector().performClick()
        compose.onNode(hasScrollAction() and hasAnyAncestor(isPopup())).assertExists()
        assertTrue("A long roster must remain bounded within the phone window",
            bounds(compose.onNode(isPopup())).height <=
                360 * compose.activity.resources.displayMetrics.density + 1)
        menuItem("Saved Portal 59").assertIsNotDisplayed()
            .performScrollTo().assertIsDisplayed().performClick()
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("portal/raw:59"), selected) }
    }

    @Test fun addRemainsAvailableWithoutASelectionWhileDeviceActionsAreGuarded() {
        var state by mutableStateOf(ControlCenterState())
        val actions = mutableListOf<String>()
        fixture {
            header(state, onAdd = { actions.add("add") },
                onDisconnect = { actions.add("disconnect") },
                onForget = { actions.add("forget:$it") })
        }

        val states = listOf(
            ControlCenterState(),
            ControlCenterState(devices = listOf(kitchen), busy = true),
            ControlCenterState(devices = listOf(kitchen), activeDeviceId = "missing",
                connection = CompanionConnection.CONNECTING, busy = true),
        )
        for (unselected in states) {
            compose.runOnIdle { state = unselected }
            selector().assertIsEnabled().performClick()
            menuItem("Disconnect").assertDoesNotExist()
            menuItem("Forget this Portal").assertDoesNotExist()
            menuItem("Add a Portal").assertIsEnabled().performClick()
            compose.onNode(isPopup()).assertDoesNotExist()
        }
        compose.runOnIdle { assertEquals(listOf("add", "add", "add"), actions) }
    }

    @Test fun disconnectAndForgetRemainAvailableForBusyOfflineAndExpiredPairings() {
        var state by mutableStateOf(selectedState())
        val actions = mutableListOf<String>()
        fixture {
            header(state, onAdd = { actions.add("add") },
                onDisconnect = { actions.add("disconnect") },
                onForget = { actions.add("forget:$it") })
        }

        for (connection in listOf(CompanionConnection.CONNECTING,
            CompanionConnection.OFFLINE, CompanionConnection.UNAUTHORIZED)) {
            compose.runOnIdle { state = selectedState().copy(connection = connection, busy = true) }
            selector().performClick()
            menuItem("Add a Portal").assertIsEnabled().performClick()
            compose.onNode(isPopup()).assertDoesNotExist()
            selector().performClick()
            menuItem("Disconnect").assertIsEnabled().performClick()
            compose.onNode(isPopup()).assertDoesNotExist()
            selector().performClick()
            menuItem("Forget this Portal").assertIsEnabled().performClick()
            compose.onNode(isPopup()).assertDoesNotExist()
        }
        compose.runOnIdle {
            assertEquals(List(3) { listOf("add", "disconnect", "forget:portal/raw:kitchen-01") }
                .flatten(), actions)
        }
    }

    @Test fun removingTheSelectedPortalWhileMenuIsOpenRemovesItsPairingActions() {
        var state by mutableStateOf(selectedState())
        val actions = mutableListOf<String>()
        fixture {
            header(state, onDisconnect = { actions.add("disconnect") },
                onForget = { actions.add("forget:$it") })
        }

        selector().performClick()
        menuItem("Forget this Portal").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(devices = listOf(studio)) }
        menuItem("Disconnect").assertDoesNotExist()
        menuItem("Forget this Portal").assertDoesNotExist()
        menuItem("Add a Portal").assertIsEnabled()
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
    }

    @Test fun refreshGuardsSelectionBusyAndAnExistingRefreshButAllowsReconnection() {
        var state by mutableStateOf(ControlCenterState())
        var refreshing by mutableStateOf(false)
        var refreshes = 0
        fixture { header(state, refreshing = refreshing, onRefresh = { refreshes++ }) }

        val disabled = listOf(
            ControlCenterState() to false,
            selectedState().copy(activeDeviceId = null) to false,
            selectedState().copy(activeDeviceId = "missing") to false,
            selectedState().copy(busy = true) to false,
            selectedState() to true,
        )
        for ((nextState, nextRefreshing) in disabled) {
            compose.runOnIdle { state = nextState; refreshing = nextRefreshing }
            refresh().assertIsNotEnabled().performTouchInput { click() }
        }
        compose.runOnIdle {
            assertEquals(0, refreshes)
            state = selectedState().copy(connection = CompanionConnection.OFFLINE)
            refreshing = false
        }
        refresh().assertIsEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, refreshes) }
    }

    @Composable
    private fun header(
        state: ControlCenterState,
        refreshing: Boolean = false,
        onSelect: (String) -> Unit = {},
        onAdd: () -> Unit = {},
        onDisconnect: () -> Unit = {},
        onForget: (String) -> Unit = {},
        onRefresh: () -> Unit = {},
    ) {
        CompactPortalHeader(state, refreshing, onSelect, onAdd, onDisconnect, onForget, onRefresh)
    }

    private fun fixture(fontScale: Float = 1f, content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
            ) {
                MaterialTheme {
                    // Measure the 64dp content separately from the native system-bar inset.
                    Column(Modifier.width(280.dp).consumeWindowInsets(WindowInsets.safeDrawing)
                        .testTag(VIEWPORT)) { content() }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun selectedState(device: CompanionDevice = kitchen) = ControlCenterState(
        devices = listOf(device, studio),
        activeDeviceId = device.id,
        connection = CompanionConnection.CONNECTED,
    )

    private fun selector() = compose.onNodeWithContentDescription("Active Portal")
    private fun refresh() = compose.onNodeWithContentDescription("Refresh current page")
    private fun menuItem(label: String) = compose.onNode(
        hasText(label) and hasClickAction() and hasAnyAncestor(isPopup()))

    // Use layout size, since a clipped semantics rectangle can hide a real overlap.
    private fun bounds(node: SemanticsNodeInteraction): Rect {
        val measured = node.fetchSemanticsNode()
        return Rect(measured.positionInRoot.x, measured.positionInRoot.y,
            measured.positionInRoot.x + measured.size.width,
            measured.positionInRoot.y + measured.size.height)
    }

    private fun assertInside(label: String, child: Rect, parent: Rect) {
        assertTrue("$label exceeds its allotted bounds: child=$child, parent=$parent",
            child.left >= parent.left - 1 && child.right <= parent.right + 1 &&
                child.top >= parent.top - 1 && child.bottom <= parent.bottom + 1)
    }

    private companion object {
        const val VIEWPORT = "compact-header-viewport"
        const val LONG_NAME = "The upstairs family room Portal with a very long household name"
        val kitchen = CompanionDevice("portal/raw:kitchen-01", "Kitchen Portal", "http://kitchen.local:8723")
        val studio = CompanionDevice("portal/raw:studio-02", "Studio Portal", "http://studio.local:8723")
    }
}
