/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.controlcenter

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.portalinstaller.data.companion.CompanionDevice
import com.portalinstaller.data.companion.ControlCenterState
import com.portalinstaller.data.companion.GroupOperation
import com.portalinstaller.data.companion.GroupWorkItem
import com.portalinstaller.data.companion.GroupWorkKind
import com.portalinstaller.data.companion.GroupWorkStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Pure Compose fixtures: no pairing, transport, devices, or owned-photo mutation. */
@RunWith(AndroidJUnit4::class)
class ManagePortalsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun photosAndSettingsHaveOneTopPortalMenuAndAPassiveDestinationSummary() {
        var photos by mutableStateOf(true)
        val state = ControlCenterState(devices = listOf(family, living),
            activeDeviceId = "family", selectedTargetIds = setOf("living"))
        compose.setContent { MaterialTheme { Column {
            CompactPortalHeader(state = state, refreshing = false, onSelectDevice = {},
                onSelectTargets = {}, onAddPortal = {}, onDisconnect = {},
                onForgetPortal = {}, onRefresh = {})
            ManagePortalsBar(state, photos = photos)
        } } }

        for (isPhotos in listOf(true, false)) {
            compose.runOnIdle { photos = isPhotos }
            compose.onAllNodesWithTag("manage-portals").assertCountEquals(1)
            compose.onNodeWithText("${if (isPhotos) "Uploads to" else "Settings apply to"}: Living Area")
                .assertIsDisplayed()
            compose.onNodeWithText("Values from: Family Area").assertIsDisplayed()
            val summary = compose.onNodeWithTag("group-target-summary")
                .assertIsDisplayed().assertHasNoClickAction()
            compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("group-target-summary")))
                .assertCountEquals(0)
            summary.performTouchInput { click() }
            compose.onNode(isPopup()).assertDoesNotExist()
            compose.onNodeWithText("Manage Portals").assertDoesNotExist()
            compose.onNodeWithTag("manage-portals").performClick()
            compose.onNodeWithText("View Portal").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Send changes to").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("group-target-living").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("view-portal-family").performScrollTo().performClick()
            compose.onNode(isPopup()).assertDoesNotExist()
        }
    }

    @Test fun passiveSummaryShowsEmptyTargetsAndSavingWithoutAnotherEntryPoint() {
        val state = ControlCenterState(devices = listOf(family, living),
            activeDeviceId = "family", targetSelectionSaving = true)
        compose.setContent { MaterialTheme { ManagePortalsBar(state, photos = true) } }

        compose.onNodeWithText("No targets selected. Choose Portals in the top menu before applying changes.")
            .assertIsDisplayed().assertHasNoClickAction()
        compose.onNodeWithText("Saving selection…").assertIsDisplayed().assertHasNoClickAction()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
        compose.onNodeWithTag("group-target-summary").performTouchInput { click() }
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.onNodeWithText("Manage Portals").assertDoesNotExist()
    }

    @Test fun partialFailureShowsBothRoomsAndRetryTargetsTheCapturedOperation() {
        val retries = mutableListOf<Long>()
        val operation = GroupOperation(7, "Change screensaver: shuffle", GroupWorkKind.SETTINGS,
            listOf(GroupWorkItem("family", "Family Area", "screensaver", status = GroupWorkStatus.SUCCEEDED),
                GroupWorkItem("living", "Living Area", "screensaver", status = GroupWorkStatus.FAILED, detail = "Offline or unreachable.")))
        compose.setContent { MaterialTheme { Column { GroupProgressPanel(listOf(operation), { retries += it }, {}, {}) } } }
        compose.onNodeWithText("Family Area · screensaver: Confirmed").assertIsDisplayed()
        compose.onNodeWithText("Living Area · screensaver: Failed").assertIsDisplayed()
        compose.onNodeWithText("Retry failed only").performClick()
        compose.runOnIdle { assertEquals(listOf(7L), retries) }
    }

    @Test fun cancelledUploadRetainsOwnDestinationAndWarnsAboutAlreadyPostedRequests() {
        val operation = GroupOperation(8, "Send 1 photo(s)", GroupWorkKind.PHOTOS,
            listOf(GroupWorkItem("family", "Family Area", "Image 1", "/sdcard/Family", GroupWorkStatus.SUCCEEDED),
                GroupWorkItem("living", "Living Area", "Image 1", "/sdcard/Living", GroupWorkStatus.CANCELLED)), cancelRequested = true)
        compose.setContent { MaterialTheme { GroupProgressPanel(listOf(operation), {}, {}, {}) } }
        compose.onNodeWithText("Destination: /sdcard/Family").assertIsDisplayed()
        compose.onNodeWithText("Destination: /sdcard/Living").assertIsDisplayed()
        compose.onNodeWithText("Queued work cancelled. Requests already sent may finish; posted changes and photos are not undone.").assertIsDisplayed()
        compose.onNodeWithText("Retry failed only").assertDoesNotExist()
    }

    private companion object {
        val family = CompanionDevice("family", "Family Area", "http://family:8723", photoFolder = "/sdcard/Family")
        val living = CompanionDevice("living", "Living Area", "http://living:8723", photoFolder = "/sdcard/Living")
    }
}
