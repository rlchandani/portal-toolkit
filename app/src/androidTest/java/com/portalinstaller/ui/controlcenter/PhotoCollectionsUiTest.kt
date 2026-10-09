/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.controlcenter

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.portalinstaller.data.companion.CompanionPhotoFolder
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Generated metadata only; these tests never connect to a Portal or access personal photos. */
@RunWith(AndroidJUnit4::class)
class PhotoCollectionsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun discoveredFolderAndAllPhotosChoicesChangeBrowsingWithoutActivatingSlideshow() {
        val all = mutableStateOf(true)
        val folder = mutableStateOf("/sdcard/Pictures/Immortal")
        var activations = 0
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    PhotoLibraryPanel("fixture", JSONArray(), folder.value, false, null, {}, {},
                        onChangeFolder = { folder.value = it; all.value = false },
                        onActivateFolder = { activations++ }, onDelete = {}, loadThumbnail = { null },
                        allPhotos = all.value, canBrowseAll = true,
                        folders = listOf(CompanionPhotoFolder("/sdcard/Pictures/PortalInstaller", 9)),
                        onAllPhotos = { all.value = true })
                }
            }
        }
        click("All photos on Portal")
        compose.onNode(hasText("PortalInstaller") and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText("Folder: /sdcard/Pictures/PortalInstaller").assertExists()
        assertEquals("/sdcard/Pictures/PortalInstaller", folder.value)
        click("Folder: /sdcard/Pictures/PortalInstaller")
        compose.onNode(hasText("All photos") and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText("All photos on Portal").assertExists()
        assertEquals(0, activations)
    }

    @Test fun incompleteScanShowsWarningAndDoesNotOfferToActivateAPartialCollection() {
        showLibrary(loaded = true, truncated = true)
        compose.onNodeWithText("This is a partial collection. Choose a folder to browse it directly.").assertExists()
        compose.onNodeWithText("Use all photos for slideshow").assertIsNotEnabled()
    }

    @Test fun initialDiscoveryShowsLoadingRatherThanAnEmptyCollection() {
        showLibrary(loaded = false, truncated = false)
        compose.onNodeWithText("Finding photos…").assertExists()
        compose.onNodeWithText("No photos found").assertDoesNotExist()
        compose.onNodeWithText("0 photos").assertDoesNotExist()
    }

    @Test fun failedDiscoveryKeepsRetryVisibleWithoutAnEndlessLoadingMessage() {
        compose.setContent {
            MaterialTheme {
                PhotoLibraryPanel("fixture", JSONArray(), "/sdcard/Pictures/Immortal", false,
                    null, {}, {}, {}, {}, {}, { null }, allPhotos = true, canBrowseAll = true,
                    listingLoaded = false, listingError = "The Portal is unavailable. Refresh to retry.")
            }
        }
        compose.onNodeWithText("The Portal is unavailable. Refresh to retry.").assertExists()
        compose.onNodeWithText("Finding photos…").assertDoesNotExist()
        compose.onNodeWithText("No photos found").assertDoesNotExist()
        compose.onNodeWithText("Refresh").assertExists()
    }

    @Test fun refreshingChangedPhotoSizeReloadsTheThumbnailEvenWhenItsTimestampIsUnchanged() {
        val loads = AtomicInteger()
        val files = mutableStateOf(JSONArray("""[{"name":"fixture.jpg","path":"/sdcard/fixture.jpg","size":10,"modified":1}]"""))
        compose.setContent {
            MaterialTheme {
                PhotoLibraryPanel("fixture", files.value, "/sdcard", false,
                    null, {}, {}, {}, {}, {}, { loads.incrementAndGet(); null })
            }
        }
        compose.waitUntil(5_000) { loads.get() == 1 }
        compose.runOnIdle {
            files.value = JSONArray("""[{"name":"fixture.jpg","path":"/sdcard/fixture.jpg","size":20,"modified":1}]""")
        }
        compose.waitUntil(5_000) { loads.get() == 2 }
        assertEquals(2, loads.get())
    }

    private fun showLibrary(loaded: Boolean, truncated: Boolean) {
        compose.setContent {
            MaterialTheme {
                PhotoLibraryPanel("fixture", if (truncated) JSONArray("""[{"name":"fixture.jpg","path":"/sdcard/fixture.jpg","size":10,"modified":1}]""") else JSONArray(), "/sdcard/Pictures/Immortal", false,
                    null, {}, {}, {}, {}, {}, { null }, allPhotos = true, canBrowseAll = true,
                    listingLoaded = loaded, truncated = truncated)
            }
        }
    }

    private fun click(label: String) {
        compose.onNode(hasText(label) and hasClickAction()).performScrollTo().performClick()
    }
}
