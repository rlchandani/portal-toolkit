/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.controlcenter

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Fixture UI workflows only: no MainActivity, sessions, accounts, network or Portal hardware. */
@RunWith(AndroidJUnit4::class)
class PhotoLibraryPaginationUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fortyNinePhotosRenderOnlyTheRequestedPageAndNavigationRespectsBothEnds() {
        val fixture = showLibrary()
        assertPage("A", 1..24, "1 of 3")
        compose.onNodeWithText("Previous").assertIsNotEnabled()
        compose.onNodeWithText("Next").assertIsEnabled()

        clickLibrary("Next")
        assertPage("A", 25..48, "2 of 3")
        compose.onNodeWithText("Previous").assertIsEnabled()
        compose.onNodeWithText("Next").assertIsEnabled()
        clickLibrary("Next")
        assertPage("A", 49..49, "3 of 3")
        compose.onNodeWithText("Next").assertIsNotEnabled()
        clickLibrary("Previous")
        assertPage("A", 25..48, "2 of 3")

        assertTrue(fixture.deletions.isEmpty())
        assertTrue(fixture.folderRequests.isEmpty())
        assertEquals(0, fixture.activations)
    }

    @Test fun refreshRetainsTheSameFolderPageAndBrowsingAnotherFolderResetsThePage() {
        val fixture = showLibrary()
        clickLibrary("Next")
        assertPage("A", 25..48, "2 of 3")

        fixture.refreshResult = photoFiles("A", FOLDER_A, 49, modified = 2)
        clickLibrary("Refresh")
        assertEquals(1, fixture.refreshes)
        assertPage("A", 25..48, "2 of 3")

        browseFolder(FOLDER_A, FOLDER_B)
        assertEquals(listOf(FOLDER_B), fixture.folderRequests)
        assertPage("B", 1..24, "1 of 2")
        compose.onNodeWithText("Previous").assertIsNotEnabled()
        clickLibrary("Next")
        assertPage("B", 25..27, "2 of 2")
        browseFolder(FOLDER_B, FOLDER_A)
        assertPage("A", 1..24, "1 of 3")
        compose.onNodeWithText("Previous").assertIsNotEnabled()
        assertEquals(listOf(FOLDER_B, FOLDER_A), fixture.folderRequests)
        assertEquals(0, fixture.activations)
        assertTrue(fixture.deletions.isEmpty())
    }

    @Test fun deletingFromALaterPageRequiresConfirmationAndUsesTheSelectedPhotoPath() {
        val fixture = showLibrary()
        clickLibrary("Next")
        assertPage("A", 25..48, "2 of 3")

        openPhoto("A", 25)
        clickDialog("Delete photo")
        compose.onNodeWithText("Delete this photo from the Portal?").assertExists()
        assertTrue(fixture.deletions.isEmpty())
        clickDialog("Keep photo")
        compose.onNodeWithText("Delete").assertDoesNotExist()
        assertTrue(fixture.deletions.isEmpty())
        assertPage("A", 25..48, "2 of 3")

        openPhoto("A", 48)
        clickDialog("Delete photo")
        assertTrue(fixture.deletions.isEmpty())
        clickDialog("Delete")
        assertEquals(listOf("fixture-device-a" to "/sdcard/Pictures/A/a-photo048.png"), fixture.deletions)
        compose.onNodeWithText("Delete").assertDoesNotExist()
        // This fixture records the request. The panel keeps the page until refreshed files arrive.
        assertPage("A", 25..48, "2 of 3")
        assertEquals(0, fixture.activations)
    }

    @Test fun deviceSwitchDismissesPendingDeleteAndPreviewAndCannotDeleteAnOldDevicePhoto() {
        val fixture = showLibrary()
        clickLibrary("Next")
        assertPage("A", 25..48, "2 of 3")
        openPhoto("A", 25)
        clickDialog("Delete photo")
        compose.onNodeWithText("Delete").assertIsEnabled()

        compose.runOnIdle {
            fixture.deviceId.value = "fixture-device-b"
            fixture.files.value = photoFiles("B", FOLDER_A, 49)
        }
        compose.onNodeWithText("Delete").assertDoesNotExist()
        compose.onNodeWithText("Keep photo").assertDoesNotExist()
        compose.onNodeWithText("Delete this photo from the Portal?").assertDoesNotExist()
        assertTrue(fixture.deletions.isEmpty())
        assertPage("B", 1..24, "1 of 3")
        compose.onNodeWithText("Previous").assertIsNotEnabled()

        clickLibrary("Next")
        assertPage("B", 25..48, "2 of 3")
        openPhoto("B", 25)
        clickDialog("Delete photo")
        clickDialog("Delete")
        assertEquals(listOf("fixture-device-b" to "/sdcard/Pictures/A/b-photo025.png"), fixture.deletions)

        openPhoto("B", 25)
        compose.onNodeWithText("Done").assertExists()
        compose.runOnIdle {
            fixture.deviceId.value = "fixture-device-c"
            fixture.files.value = photoFiles("C", FOLDER_A, 49)
        }
        compose.onNodeWithText("Done").assertDoesNotExist()
        compose.onNodeWithText("Delete photo").assertDoesNotExist()
        assertPage("C", 1..24, "1 of 3")
        assertEquals(listOf("fixture-device-b" to "/sdcard/Pictures/A/b-photo025.png"), fixture.deletions)
        assertTrue(fixture.folderRequests.isEmpty())
    }

    @Test fun aShorterRefreshClampsTheLastPageAndPreviousReturnsToTheFirstTwentyFour() {
        val fixture = showLibrary()
        clickLibrary("Next")
        assertPage("A", 25..48, "2 of 3")
        clickLibrary("Next")
        assertPage("A", 49..49, "3 of 3")

        fixture.refreshResult = photoFiles("A", FOLDER_A, 25, modified = 2)
        clickLibrary("Refresh")
        assertPage("A", 25..25, "2 of 2")
        compose.onNodeWithText("Next").assertIsNotEnabled()
        compose.onNodeWithText("Previous").assertIsEnabled()
        clickLibrary("Previous")
        assertPage("A", 1..24, "1 of 2")
        compose.onNodeWithText("Previous").assertIsNotEnabled()
        assertEquals(1, fixture.refreshes)
        assertTrue(fixture.deletions.isEmpty())
    }

    private fun showLibrary(): LibraryFixture {
        val fixture = LibraryFixture()
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                    // Capture the rendered device in each callback. A leaked old dialog would
                    // deliver its old path to the newly rendered device and fail the context test.
                    val renderedDeviceId = fixture.deviceId.value
                    PhotoLibraryPanel(
                        deviceId = renderedDeviceId,
                        files = fixture.files.value,
                        folder = fixture.folder.value,
                        working = false,
                        uploadDetail = null,
                        onImport = {},
                        onRefresh = {
                            fixture.refreshes++
                            fixture.refreshResult?.let { fixture.files.value = it }
                        },
                        onChangeFolder = { folder ->
                            fixture.folderRequests.add(folder)
                            fixture.folder.value = folder
                            fixture.files.value = when (folder) {
                                FOLDER_A -> photoFiles("A", folder, 49)
                                FOLDER_B -> photoFiles("B", folder, 27)
                                else -> error("Unexpected fixture folder.")
                            }
                        },
                        onActivateFolder = { fixture.activations++ },
                        onDelete = { path -> fixture.deletions.add(renderedDeviceId to path) },
                        loadThumbnail = fixture.loadThumbnail,
                    )
                }
            }
        }
        assertPage("A", 1..24, "1 of 3")
        return fixture
    }

    private fun assertPage(label: String, indices: IntRange, pageText: String) {
        val expectedNames = indices.map { photoName(label, it) }.toSet()
        compose.waitUntil(5_000) {
            val descriptions = thumbnailDescriptions()
            descriptions.size == expectedNames.size && descriptions.toSet() == expectedNames
        }
        assertEquals(expectedNames.size, thumbnailDescriptions().size)
        assertEquals(expectedNames, thumbnailDescriptions().toSet())
        compose.onNodeWithText(pageText).assertExists()
    }

    private fun thumbnailDescriptions(): List<String> = compose.onAllNodes(thumbnails)
        .fetchSemanticsNodes().flatMap { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                .filter { it.startsWith("Fixture photo ") }
        }

    private fun clickLibrary(label: String) {
        compose.onNode(hasText(label) and hasClickAction()).performScrollTo().performClick()
    }

    private fun clickDialog(label: String) {
        compose.onNode(hasText(label) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
    }

    private fun browseFolder(from: String, to: String) {
        clickLibrary("Folder: $from")
        compose.onNode(hasSetTextAction() and hasText("Folder on Portal")).performTextReplacement(to)
        clickDialog("Browse folder")
    }

    private fun openPhoto(label: String, index: Int) {
        val name = photoName(label, index)
        compose.onNode(hasContentDescription(name)).performScrollTo().performClick()
        compose.onNode(hasText(name) and hasAnyAncestor(isDialog())).assertExists()
    }

    private class LibraryFixture {
        val deviceId = mutableStateOf("fixture-device-a")
        val folder = mutableStateOf(FOLDER_A)
        val files = mutableStateOf(photoFiles("A", FOLDER_A, 49))
        val deletions = mutableListOf<Pair<String, String>>()
        val folderRequests = mutableListOf<String>()
        var refreshResult: JSONArray? = null
        var refreshes = 0
        var activations = 0
        private val png = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).let { bitmap ->
            try {
                bitmap.eraseColor(0xff176abb.toInt())
                ByteArrayOutputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    it.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        }
        val loadThumbnail: suspend (String) -> ByteArray? = { png }
    }

    private companion object {
        const val FOLDER_A = "/sdcard/Pictures/A"
        const val FOLDER_B = "/sdcard/Pictures/B"
        val thumbnails = SemanticsMatcher("fixture photo thumbnail") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                .any { it.startsWith("Fixture photo ") }
        }

        fun photoName(label: String, index: Int) =
            String.format(Locale.ROOT, "Fixture photo %s%03d.png", label, index)

        fun photoFiles(label: String, folder: String, count: Int, modified: Int = 1): JSONArray =
            JSONArray((1..count).map { index ->
                JSONObject().put("name", photoName(label, index))
                    .put("path", String.format(Locale.ROOT, "%s/%s-photo%03d.png",
                        folder, label.lowercase(Locale.ROOT), index))
                    .put("size", 80).put("modified", modified)
            })
    }
}
