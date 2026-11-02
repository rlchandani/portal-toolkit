/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.devices

import android.net.Uri
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.portalinstaller.viewmodel.BackupState
import com.portalinstaller.viewmodel.BackupViewModel
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real BackupCard/ViewModel/file inspection; generated files, no Portal connection. */
@RunWith(AndroidJUnit4::class)
class BackupPreviewUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun awakeAndCheckActualFont() {
        compose.activityRule.scenario.onActivity {
            it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val expected = InstrumentationRegistry.getArguments().getString("QA_FONT_SCALE")?.toFloat() ?: 1f
        assertEquals("Set the actual owned emulator font before this run",
            expected, compose.activity.resources.configuration.fontScale, 0.01f)
    }

    @Test fun previewOffersReachableCancelAction() {
        val state = mutableStateOf<BackupState>(BackupState.Previewing)
        var cancelled = false
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    card(state.value) { cancelled = true; state.value = BackupState.Idle }
                }
            }
        }
        val cancel = compose.onNodeWithText("Cancel").performScrollTo().assertIsDisplayed().assertIsEnabled()
        capture("backup-preview-cancel")
        cancel.performClick()
        compose.runOnIdle { assertTrue(cancelled); assertEquals(BackupState.Idle, state.value) }
    }

    @Test fun generatedBackupUsesActualViewModelAndReportsItsPreview() {
        val model = model()
        val source = archiveFile("normal")
        try {
            show(model)
            compose.runOnIdle { model.inspectBackup(compose.activity, Uri.fromFile(source)) }
            compose.waitUntil(5_000) { model.backupState.value is BackupState.ReadyToRestore }
            compose.runOnIdle {
                val preview = (model.backupState.value as BackupState.ReadyToRestore).preview
                assertEquals("normal", preview.model)
                assertEquals(0, preview.appCount)
            }
            compose.onNodeWithText("Preview: normal").performScrollTo().assertIsDisplayed()
        } finally { model.cancelBackup(); source.delete() }
    }

    @Test fun cancellingBlockedFileInspectionReleasesBusyCardAndDiscardsLateResult() {
        val model = model()
        val blocked = BlockedArchive("cancelled")
        lateinit var previewJob: Job
        var inspectionStarted = false
        try {
            show(model)
            compose.runOnIdle {
                previewJob = requireNotNull(model.inspectBackup(compose.activity, Uri.fromFile(blocked.file)))
                inspectionStarted = true
            }
            assertTrue(blocked.writerOpened.await(5, TimeUnit.SECONDS))
            val cancel = compose.onNodeWithText("Cancel").performScrollTo().assertIsDisplayed()
            val started = SystemClock.elapsedRealtimeNanos()
            cancel.performClick()
            compose.runOnIdle { assertEquals(BackupState.Idle, model.backupState.value) }
            val elapsedMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
            saveCancellationMetrics(elapsedMs)
            assertTrue("Cancellation must release the rendered busy state within one second", elapsedMs < 1_000)
            blocked.complete()
            assertCancelledPreviewFinished(previewJob)
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(BackupState.Idle, model.backupState.value) }
            compose.onNodeWithText("Create backup").performScrollTo().assertIsEnabled()
        } finally {
            model.cancelBackup()
            try {
                blocked.close()
            } finally {
                if (inspectionStarted) assertCancelledPreviewFinished(previewJob)
            }
        }
    }

    @Test fun cancelledInspectionCannotReplaceANewerGeneratedPreview() {
        val model = model()
        val blocked = BlockedArchive("old")
        var source: File? = null
        lateinit var oldPreviewJob: Job
        var inspectionStarted = false
        try {
            val newSource = archiveFile("new")
            source = newSource
            show(model)
            compose.runOnIdle {
                oldPreviewJob = requireNotNull(model.inspectBackup(compose.activity, Uri.fromFile(blocked.file)))
                inspectionStarted = true
            }
            assertTrue(blocked.writerOpened.await(5, TimeUnit.SECONDS))
            compose.onNodeWithText("Cancel").performScrollTo().performClick()
            compose.runOnIdle { model.inspectBackup(compose.activity, Uri.fromFile(newSource)) }
            compose.waitUntil(5_000) {
                (model.backupState.value as? BackupState.ReadyToRestore)?.preview?.model == "new"
            }
            blocked.complete()
            assertCancelledPreviewFinished(oldPreviewJob)
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals("new", (model.backupState.value as BackupState.ReadyToRestore).preview.model)
            }
            compose.onNodeWithText("Preview: new").performScrollTo().assertIsDisplayed()
        } finally {
            model.cancelBackup()
            try {
                blocked.close()
            } finally {
                try {
                    if (inspectionStarted) assertCancelledPreviewFinished(oldPreviewJob)
                } finally {
                    source?.delete()
                }
            }
        }
    }

    private fun assertCancelledPreviewFinished(job: Job) {
        runBlocking { withTimeout(5_000) { job.join() } }
        assertTrue("Cancelled preview reader must be terminal", job.isCompleted)
        assertTrue("Preview job must retain cancellation", job.isCancelled)
    }

    private fun saveCancellationMetrics(elapsedMs: Double) {
        val fontScale = compose.activity.resources.configuration.fontScale
        val directory = requireNotNull(compose.activity.getExternalFilesDir("qa"))
        check(directory.isDirectory || directory.mkdirs()) { "Could not create QA metrics directory" }
        File(directory, "backup-preview-metrics-${(fontScale * 100).roundToInt()}.json").writeText(
            JSONObject()
                .put("actualFontScale", fontScale.toDouble())
                .put("cancelToIdleMs", elapsedMs)
                .toString(2),
            Charsets.UTF_8
        )
    }

    private fun model() = BackupViewModel(compose.activity.application)

    private fun show(model: BackupViewModel) {
        compose.setContent {
            val state by model.backupState.collectAsState()
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    card(state, model::cancelBackup)
                    if (state is BackupState.ReadyToRestore)
                        Text("Preview: ${(state as BackupState.ReadyToRestore).preview.model}")
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun card(state: BackupState, cancel: () -> Unit) {
        BackupCard(true, state, 0, false, null, {}, {}, {}, cancel)
    }

    private fun archive(model: String): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("""{"formatVersion":1,"apps":[],"device":{"model":"$model"}}""".toByteArray())
            zip.closeEntry()
        }
    }.toByteArray()

    private fun archiveFile(model: String) =
        File.createTempFile("generated-backup-", ".zip", compose.activity.cacheDir).apply { writeBytes(archive(model)) }

    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val scale = (compose.activity.resources.configuration.fontScale * 100).roundToInt()
        val target = File(compose.activity.getExternalFilesDir("qa"), "$name-$scale.png")
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    /** A real sandbox-local FIFO holds file IO until the rendered Cancel action is pressed. */
    private inner class BlockedArchive(model: String) {
        val file = File(compose.activity.cacheDir, "generated-backup-${System.nanoTime()}.pipe")
        val writerOpened = CountDownLatch(1)
        private val release = CountDownLatch(1)
        private val writer: Thread
        @Volatile private var error: Throwable? = null

        init {
            var child: Process? = null
            try {
                val process = ProcessBuilder("/system/bin/mkfifo", file.absolutePath).start()
                child = process
                assertTrue("FIFO fixture creation timed out", process.waitFor(3, TimeUnit.SECONDS))
                assertEquals("FIFO fixture could not be created", 0, process.exitValue())
                process.inputStream.close()
                process.errorStream.close()
                process.outputStream.close()
                writer = thread(name = "generated-backup-writer") {
                    try {
                        FileOutputStream(file).use {
                            writerOpened.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                            it.write(archive(model))
                        }
                    } catch (failure: Throwable) { error = failure }
                }
            } catch (failure: Throwable) {
                try {
                    child?.let { process ->
                        if (process.isAlive) process.destroyForcibly()
                        assertTrue("FIFO helper must be terminal after cleanup",
                            process.waitFor(3, TimeUnit.SECONDS))
                    }
                } finally {
                    deleteOwnedFile()
                }
                throw failure
            }
        }

        fun complete() {
            release.countDown()
            writer.join(3_000)
            assertFalse("Generated writer must be terminal", writer.isAlive)
            // Cancellation may close the read side before the writer finishes.
            assertTrue(error?.let { it is java.io.IOException } ?: true)
        }

        fun close() {
            release.countDown()
            if (writer.isAlive) {
                // Opening both sides releases an abandoned initial open during failure cleanup.
                RandomAccessFile(file, "rw").use {
                    writer.join(3_000)
                    assertFalse("Generated writer must be terminal before FIFO removal", writer.isAlive)
                }
            }
            assertFalse("Generated writer must be terminal before FIFO removal", writer.isAlive)
            deleteOwnedFile()
        }

        private fun deleteOwnedFile() {
            check(!file.exists() || file.delete()) { "Could not remove generated FIFO ${file.name}" }
        }
    }
}
