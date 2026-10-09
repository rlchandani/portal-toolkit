/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.controlcenter

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.portalinstaller.MainActivity
import com.portalinstaller.data.companion.CompanionClient
import com.portalinstaller.data.companion.FileCompanionSessionStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith

/** Opt-in workflow against both saved Portals; never changes photos or slideshow settings. */
@RunWith(AndroidJUnit4::class)
class CompanionPhotoLibraryUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun photosTabShowsBothRoomCollectionsAndFolderChoicesWithoutLeavingTheActivity() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("livePortal") == "1")
        val sessions = FileCompanionSessionStore.create(
            InstrumentationRegistry.getInstrumentation().targetContext).load()
            .filter { it.device.name in setOf("Family Area", "Living Area") }
        assertEquals(2, sessions.size)
        val client = CompanionClient()
        for (session in sessions) {
            val expected = client.photos(session, "/sdcard", recursive = true).getJSONArray("files").length()
            val folder = "/sdcard/Pictures/PortalInstaller"
            val folderCount = client.photos(session, folder).getJSONArray("files").length()
            compose.onNodeWithContentDescription("Active Portal").performClick()
            compose.onNode(hasText(session.device.name) and hasClickAction()).performClick()
            selectTab("Photos")
            waitForText("$expected photos")
            compose.onNodeWithText("All photos on Portal").assertExists()
            compose.onNodeWithText("All photos on Portal").performScrollTo().performClick()
            compose.onNode(hasText("PortalInstaller") and hasClickAction()).performClick()
            waitForText("$folderCount photos")
            compose.onNodeWithText("Folder: $folder").performScrollTo().performClick()
            compose.onNode(hasText("All photos") and hasClickAction()).performClick()
            waitForText("$expected photos")
            repeat(3) {
                selectTab("Home")
                selectTab("Photos")
                waitForText("$expected photos")
                compose.onNodeWithText("All photos on Portal").assertExists()
                assertEquals(MainActivity::class.java, compose.activity.javaClass)
            }
        }
        selectTab("Home")
    }

    private fun selectTab(text: String) {
        compose.onNode(hasText(text) and SemanticsMatcher.expectValue(
            SemanticsProperties.Role, Role.Tab)).performClick()
    }

    private fun waitForText(text: String) {
        compose.waitUntil(15_000) {
            runCatching { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
                .getOrDefault(false)
        }
    }
}
