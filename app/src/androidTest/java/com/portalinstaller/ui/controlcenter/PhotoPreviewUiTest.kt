/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.controlcenter

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import org.json.JSONArray
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated Compose tests: no livePortal opt-in, sessions, or Portal connections. */
@RunWith(AndroidJUnit4::class)
class PhotoPreviewUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var renderedActivity: ComponentActivity

    @Before fun awakeAndCheckRenderedFont() {
        val expectedScale = InstrumentationRegistry.getArguments()
            .getString("QA_FONT_SCALE")?.toFloat() ?: 1f
        // Android applies settings asynchronously; wait on the actual rendered resources.
        compose.waitUntil(5_000) {
            kotlin.math.abs(compose.activity.resources.configuration.fontScale - expectedScale) < 0.01f
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        renderedActivity = compose.activity
        compose.activityRule.scenario.onActivity {
            it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        assertEquals("Use the actual rendered activity system font", expectedScale,
            renderedActivity.resources.configuration.fontScale, 0.01f)
    }

    @Test
    fun largeEncodedThumbnailOpensPreviewAndClosesCleanly() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val actualScale = renderedActivity.resources.configuration.fontScale
        val bytes = instrumentation.context.assets.open("qa-display-4096x3072.jpg")
            .use { it.readBytes() }
        val fixture = showLibrary(bytes)
        openPreview()
        compose.waitUntil(5_000) { fixture.previewCompleted.count == 0L }
        assertLoadedPreview(FIRST_NAME) { fixture.trace.joinToString("\n") }
        val image = compose.onNode(hasAnyAncestor(isDialog()) and hasContentDescription(FIRST_NAME))
            .captureToImage().asAndroidBitmap()
        try {
            val directory = requireNotNull(instrumentation.targetContext.getExternalFilesDir("qa"))
            check(directory.isDirectory || directory.mkdirs())
            val scale = kotlin.math.round(actualScale * 100).toInt()
            FileOutputStream(File(directory, "display-photo-preview-$scale.png")).use {
                check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { image.recycle() }
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Done").assertDoesNotExist()
        compose.onNodeWithText(ERROR).assertDoesNotExist()
        waitForThumbnails()
    }

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

    private fun showLibrary(thumbnailBytes: ByteArray? = null): ThumbnailFixture {
        val fixture = ThumbnailFixture()
        if (thumbnailBytes != null) fixture.response = { thumbnailBytes }
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

    private fun assertLoadedPreview(name: String, diagnostics: () -> String = { "" }) {
        val image = hasAnyAncestor(isDialog()) and hasContentDescription(name)
        try {
            compose.waitUntil(5_000) {
                compose.onAllNodes(image).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (failure: Throwable) {
            fun tree(node: androidx.compose.ui.semantics.SemanticsNode, depth: Int = 0): String =
                "${" ".repeat(depth)}${node.config}, bounds=${node.boundsInRoot}\n" +
                    node.children.joinToString("") { tree(it, depth + 1) }
            val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes()
            val directory = requireNotNull(InstrumentationRegistry.getInstrumentation()
                .targetContext.getExternalFilesDir("qa"))
            check(directory.isDirectory || directory.mkdirs())
            File(directory, "photo-preview-failure-tree.txt").writeText(
                "activityDestroyed=${renderedActivity.isDestroyed}, " +
                    "activityReplaced=${compose.activity !== renderedActivity}\n" +
                    diagnostics() + "\n" +
                    roots.joinToString("\n") { tree(it) })
            throw failure
        }
        assertTrue("Fixture activity changed during preview", compose.activity === renderedActivity)
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
            val request = requestCount.incrementAndGet()
            trace.add("request=$request path=$path")
            currentCoroutineContext().job.invokeOnCompletion { cause ->
                trace.add("request=$request completed=${cause?.javaClass?.simpleName ?: "normally"}")
                if (request >= 3) previewCompleted.countDown()
            }
            response(path)
        }
        val noOp: () -> Unit = {}
        val ignorePath: (String) -> Unit = {}
        val previewCompleted = CountDownLatch(1)
        val trace = ConcurrentLinkedQueue<String>()
        private val requestCount = AtomicInteger()
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
