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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

    @Test fun explicitCheckboxSelectionAndSelectAllDoNotChangeActivePortal() {
        var state by mutableStateOf(ControlCenterState(devices = listOf(family, living),
            activeDeviceId = "family", selectedTargetIds = setOf("family", "living")))
        compose.setContent { MaterialTheme { ManagePortalsBar(state, photos = false,
            onSelect = { state = state.copy(selectedTargetIds = it) }) } }
        compose.onNodeWithTag("group-target-summary").assertIsDisplayed()
        compose.onNodeWithTag("manage-portals").performClick()
        compose.onNodeWithTag("group-target-living").assertIsOn().performClick().assertIsOff()
        compose.runOnIdle { assertEquals(setOf("family"), state.selectedTargetIds); assertEquals("family", state.activeDeviceId) }
        compose.onNodeWithTag("select-all-portals").performClick()
        compose.onNodeWithTag("group-target-living").assertIsOn()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Settings apply to: Family Area, Living Area").assertIsDisplayed()
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
