/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.controlcenter

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import org.json.JSONArray
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated Compose tests: no livePortal opt-in, sessions, or Portal connections. */
@RunWith(AndroidJUnit4::class)
class PhotoPreviewUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun nullPreviewEndsLoadingWithRetry() {
        val fixture = showLibrary()
        fixture.response = { null }

        openPreview()

        assertFailedPreview()
    }

    @Test
    fun retryShowsLoadingAndRecoversToAnImage() {
        val fixture = showLibrary()
        fixture.response = { null }
        openPreview()
        assertFailedPreview()
        val retry = CompletableDeferred<ByteArray?>()
        fixture.response = { retry.await() }

        compose.onNodeWithText("Retry").performClick()

        assertLoadingPreview()
        retry.complete(fixture.png)
        assertLoadedPreview(FIRST_NAME)
    }

    @Test
    fun invalidImageEndsLoadingWithRetry() {
        val fixture = showLibrary()
        fixture.response = { byteArrayOf(1, 2, 3) }

        openPreview()

        assertFailedPreview()
    }

    @Test
    fun thrownLoadEndsLoadingWithRetry() {
        val fixture = showLibrary()
        fixture.response = { throw IOException("Fake thumbnail unavailable") }

        openPreview()

        assertFailedPreview()
        compose.onNodeWithText("Fake thumbnail unavailable").assertDoesNotExist()
    }

    @Test
    fun cancelledLoadIsNotShownAsFailure() {
        val fixture = showLibrary()
        val completed = CountDownLatch(1)
        val completionCause = AtomicReference<Throwable?>()
        fixture.response = {
            currentCoroutineContext().job.invokeOnCompletion { cause ->
                completionCause.set(cause)
                completed.countDown()
            }
            throw CancellationException("Preview cancelled")
        }

        openPreview()

        assertTrue("Preview coroutine did not complete", completed.await(5, TimeUnit.SECONDS))
        assertTrue("Cancellation was swallowed", completionCause.get() is CancellationException)
        compose.waitForIdle()
        compose.onNodeWithText(ERROR).assertDoesNotExist()
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.onNodeWithText("Done").performClick()
        compose.onNode(progress).assertDoesNotExist()
    }

    @Test
    fun switchingDeviceDismissesPreviousPreview() {
        val fixture = showLibrary()
        fixture.response = { null }
        openPreview()
        assertFailedPreview()

        fixture.response = { fixture.png }
        compose.runOnIdle { fixture.deviceId.value = "another-fake-device" }

        compose.onNodeWithText("Done").assertDoesNotExist()
        compose.onNodeWithText(ERROR).assertDoesNotExist()
        waitForThumbnails()
        openPreview()
        assertLoadedPreview(FIRST_NAME)
    }

    @Test
    fun selectingAnotherPhotoStartsFreshLoading() {
        val fixture = showLibrary()
        fixture.response = { null }
        openPreview()
        assertFailedPreview()
        compose.onNodeWithText("Done").performClick()
        val secondPreview = CompletableDeferred<ByteArray?>()
        fixture.response = { path -> if (path == SECOND_PATH) secondPreview.await() else null }

        openPreview(SECOND_NAME)

        assertLoadingPreview()
        secondPreview.complete(fixture.png)
        assertLoadedPreview(SECOND_NAME)
    }

    private fun showLibrary(): ThumbnailFixture {
        val fixture = ThumbnailFixture()
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxWidth()) {
                    PhotoLibraryPanel(
                        deviceId = fixture.deviceId.value,
                        files = fixture.files,
                        folder = "/sdcard/Pictures",
                        working = false,
                        uploadDetail = null,
                        onImport = fixture.noOp,
                        onRefresh = fixture.noOp,
                        onChangeFolder = fixture.ignorePath,
                        onActivateFolder = fixture.noOp,
                        onDelete = fixture.ignorePath,
                        loadThumbnail = fixture.loadThumbnail,
                    )
                }
            }
        }
        waitForThumbnails()
        return fixture
    }

    private fun waitForThumbnails() {
        compose.waitUntil(5_000) {
            listOf(FIRST_NAME, SECOND_NAME).all { name ->
                runCatching {
                    compose.onAllNodes(hasContentDescription(name)).fetchSemanticsNodes().isNotEmpty()
                }.getOrDefault(false)
            }
        }
    }

    private fun openPreview(name: String = FIRST_NAME) {
        compose.onNodeWithContentDescription(name).performClick()
        compose.onNodeWithText("Done").assertIsDisplayed()
    }

    private fun assertFailedPreview() {
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("Retry")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(ERROR).assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsDisplayed()
        compose.onNode(progress).assertDoesNotExist()
        compose.onNode(hasAnyAncestor(isDialog()) and hasContentDescription(FIRST_NAME))
            .assertDoesNotExist()
    }

    private fun assertLoadingPreview() {
        // A Retry replaces the progress node; wait for its first rendered frame.
        try {
            compose.waitUntil(5_000) {
                runCatching { compose.onNode(progress).assertIsDisplayed() }.isSuccess
            }
        } catch (failure: Throwable) {
            val bounds = runCatching {
                compose.onAllNodes(progress).fetchSemanticsNodes().map {
                    "window=${it.boundsInWindow}, root=${it.boundsInRoot}, parent=${it.parent?.boundsInRoot}"
                }
            }.getOrDefault(emptyList())
            throw AssertionError("Loading indicator did not render: $bounds", failure)
        }
        compose.onNodeWithText(ERROR).assertDoesNotExist()
        compose.onNodeWithText("Retry").assertDoesNotExist()
    }

    private fun assertLoadedPreview(name: String) {
        val image = hasAnyAncestor(isDialog()) and hasContentDescription(name)
        compose.waitUntil(5_000) {
            compose.onAllNodes(image).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(image).assertIsDisplayed()
        compose.onNode(progress).assertDoesNotExist()
        compose.onNodeWithText(ERROR).assertDoesNotExist()
        compose.onNodeWithText("Retry").assertDoesNotExist()
    }

    private class ThumbnailFixture {
        val deviceId = mutableStateOf("fake-device")
        val files = JSONArray(
            """[
                {"name":"First photo.png","path":"/sdcard/Pictures/first.png","size":80,"modified":1},
                {"name":"Second photo.png","path":"/sdcard/Pictures/second.png","size":80,"modified":1}
            ]"""
        )
        val png = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).let { bitmap ->
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
        @Volatile
        var response: suspend (String) -> ByteArray? = { png }

        // Keep callback identities stable when the device or test response changes.
        val loadThumbnail: suspend (String) -> ByteArray? = { path ->
            check(path == FIRST_PATH || path == SECOND_PATH)
            response(path)
        }
        val noOp: () -> Unit = {}
        val ignorePath: (String) -> Unit = {}
    }

    private companion object {
        const val FIRST_NAME = "First photo.png"
        const val SECOND_NAME = "Second photo.png"
        const val FIRST_PATH = "/sdcard/Pictures/first.png"
        const val SECOND_PATH = "/sdcard/Pictures/second.png"
        const val ERROR = "Couldn't load this photo preview. Try again."
        val progress = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
    }
}
