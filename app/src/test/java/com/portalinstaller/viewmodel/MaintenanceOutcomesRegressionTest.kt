/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.viewmodel

import com.portalinstaller.data.InstalledApp
import com.portalinstaller.data.ConnectionHolder
import com.portalinstaller.data.PortalRestoreSummary
import com.portalinstaller.data.store.AppProfile
import dadb.Dadb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy

class MaintenanceOutcomesRegressionTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun installed(packageName: String, split: Boolean = false): InstalledApp {
        val base = temporary.newFile().apply { writeText("$packageName:base") }
        val splits = if (split) listOf(temporary.newFile().apply { writeText("$packageName:split") }.path) else null
        return InstalledApp("Same displayed name", packageName, base.path, splits, base.length(), 24)
    }

    private class Device {
        val calls = mutableListOf<List<String>>()
        val installed = mutableMapOf("com.example.music" to "old-apk")
        val data = mutableMapOf("com.example.music" to "owner-data")
        var failPackage: String? = null
        var cancelPackage: String? = null
        val dadb = Proxy.newProxyInstance(Dadb::class.java.classLoader, arrayOf(Dadb::class.java)) { proxy, method, args ->
            when (method.name) {
                "installMultiple" -> {
                    val files = (args!![0] as List<*>).map { (it as File).readText() }
                    val pkg = files.first().substringBefore(':')
                    calls += files
                    if (pkg == cancelPackage) throw CancellationException("cancelled in transport")
                    if (pkg == failPackage) throw IOException("INSTALL_FAILED_UPDATE_INCOMPATIBLE")
                    installed[pkg] = files.joinToString()
                    null
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                "toString" -> "ProfileFakeDevice"
                "close" -> null
                else -> error("Unexpected destructive command: ${method.name}")
            }
        } as Dadb
    }

    private suspend fun apply(
        packages: Set<String>, apps: List<InstalledApp>, device: Device,
        results: MutableList<Pair<String, Boolean>> = mutableListOf(),
        reasons: MutableMap<String, String> = mutableMapOf(),
        progress: (Int, String, InstalledApp?) -> Unit = { _, _, _ -> }
    ) = applyProfilePackages(
        AppProfile("id", "Music", packages, 42), apps, results, reasons, "Not installed on phone", progress
    ) { _, files ->
        device.dadb.installMultiple(files, "-r")
        Result.success(Unit)
    }

    @Test fun `profile selects exact package despite equal labels and similar package names`() = runTest {
        val device = Device()
        val music = installed("com.example.music", split = true)
        val beta = installed("com.example.music.beta")
        val result = apply(setOf("com.example.music"), listOf(music, beta), device)
        assertEquals(listOf("com.example.music"), result.succeeded)
        assertTrue(result.failed.isEmpty())
        assertEquals(listOf(listOf("com.example.music:base", "com.example.music:split")), device.calls)
        assertFalse(device.installed.containsKey("com.example.music.beta"))
        assertEquals(mapOf("com.example.music" to "owner-data"), device.data)
    }

    @Test fun `missing exact package never installs a same label or prefix match`() = runTest {
        val device = Device()
        val result = apply(setOf("com.example.music"), listOf(installed("com.example.music.beta")), device)
        assertTrue(result.succeeded.isEmpty())
        assertEquals(listOf("com.example.music" to "Not installed on phone"), result.failed)
        assertTrue(device.calls.isEmpty())
        assertEquals(mapOf("com.example.music" to "old-apk"), device.installed)
        assertEquals(mapOf("com.example.music" to "owner-data"), device.data)
    }

    @Test fun `thrown install failure records exact failed package and retains earlier results`() = runTest {
        val device = Device().apply { failPackage = "com.example.music" }
        val result = apply(setOf("com.example.alpha", "com.example.music", "com.example.zulu"),
            listOf(installed("com.example.music"), installed("com.example.zulu"), installed("com.example.alpha")), device)
        assertEquals(listOf("com.example.alpha", "com.example.zulu"), result.succeeded)
        assertEquals(listOf("com.example.music" to "INSTALL_FAILED_UPDATE_INCOMPATIBLE"), result.failed)
        assertEquals("old-apk", device.installed["com.example.music"])
        assertEquals(mapOf("com.example.music" to "owner-data"), device.data)
    }

    @Test fun `transport cancellation retains confirmed results and marks all unconfirmed packages`() = runTest {
        val device = Device().apply { cancelPackage = "com.example.music" }
        val results = mutableListOf<Pair<String, Boolean>>()
        val reasons = mutableMapOf<String, String>()
        val packages = setOf("com.example.alpha", "com.example.music", "com.example.zulu")
        val failure = runCatching { apply(packages,
            packages.map { installed(it) }, device, results, reasons) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        val outcome = foldProfileResults(results, "Music", reasons, packages.sorted(), cancelled = true)
        assertTrue(outcome.cancelled)
        assertEquals(listOf("com.example.alpha"), outcome.succeeded)
        assertEquals(listOf("com.example.music", "com.example.zulu"), outcome.failed.map { it.first })
        assertEquals(2, device.calls.size)
        assertEquals("old-apk", device.installed["com.example.music"])
        assertFalse(device.installed.containsKey("com.example.zulu"))
        assertEquals(mapOf("com.example.music" to "owner-data"), device.data)
    }

    @Test fun `progress cancellation prevents a profile install reaching transport`() = runTest {
        val device = Device()
        lateinit var job: kotlinx.coroutines.Job
        job = launch(start = CoroutineStart.LAZY) {
            apply(setOf("com.example.music"), listOf(installed("com.example.music")), device,
                progress = { _, _, _ -> job.cancel() })
        }
        job.start(); job.join()
        assertTrue(job.isCancelled)
        assertTrue(device.calls.isEmpty())
        assertEquals(mapOf("com.example.music" to "old-apk"), device.installed)
        assertEquals(mapOf("com.example.music" to "owner-data"), device.data)
    }

    @Test fun `partial restore is a failed outcome with its counts retained in message`() {
        val outcome = backupRestoreOutcome(PortalRestoreSummary(1, listOf("com.example.music"), 0), "1 installed; 1 failed")
        assertTrue(outcome is BackupState.Failed)
        assertEquals("1 installed; 1 failed", (outcome as BackupState.Failed).message)
    }

    @Test fun `stopped restore is a failed outcome even if failure list is absent`() {
        assertTrue(backupRestoreOutcome(PortalRestoreSummary(0, emptyList(), 0, "disconnected"), "Stopped") is BackupState.Failed)
    }

    @Test fun `fully confirmed restore is a success outcome`() {
        assertEquals(BackupState.Success("Restored"),
            backupRestoreOutcome(PortalRestoreSummary(2, emptyList(), 1), "Restored"))
    }

    @Test fun `mixed restore success and failure still requires refresh`() {
        val summary = PortalRestoreSummary(1, listOf("com.example.music"), 0)
        assertTrue(shouldRefreshAfterRestore(summary))
        assertTrue(backupRestoreOutcome(summary, "Partial") is BackupState.Failed)
    }

    @Test fun `no confirmed restore mutations never require refresh`() {
        listOf(
            PortalRestoreSummary(0, emptyList(), 0),
            PortalRestoreSummary(0, listOf("com.example.music"), 0),
            PortalRestoreSummary(0, emptyList(), 0, failedSettings = listOf("system.volume_ring")),
            PortalRestoreSummary(0, listOf("com.example.music"), 0, "Stopped")
        ).forEach { assertFalse(shouldRefreshAfterRestore(it)) }
    }

    @Test fun `partial settings success requires refresh with failed outcome`() {
        val summary = PortalRestoreSummary(0, emptyList(), 1, failedSettings = listOf("system.volume_ring"))
        assertTrue(shouldRefreshAfterRestore(summary))
        assertTrue(backupRestoreOutcome(summary, "Partial settings") is BackupState.Failed)
    }

    @Test fun `stopped restore requires refresh only when a mutation was confirmed`() {
        assertTrue(shouldRefreshAfterRestore(
            PortalRestoreSummary(1, listOf("com.example.music"), 0, "Stopped")))
        assertFalse(shouldRefreshAfterRestore(
            PortalRestoreSummary(0, listOf("com.example.music"), 0, "Stopped")))
    }

    @Test fun `fully confirmed app or settings restore requires refresh`() {
        assertTrue(shouldRefreshAfterRestore(PortalRestoreSummary(1, emptyList(), 0)))
        assertTrue(shouldRefreshAfterRestore(PortalRestoreSummary(0, emptyList(), 1)))
    }

    @Test fun `partial or stopped completion refreshes once and retains failed state and history`() = runTest {
        listOf(
            PortalRestoreSummary(1, listOf("com.example.music"), 0),
            PortalRestoreSummary(0, emptyList(), 1, failedSettings = listOf("system.volume_ring")),
            PortalRestoreSummary(1, listOf("com.example.music"), 0, "Stopped")
        ).forEach { summary ->
            val holder = ConnectionHolder().apply { set(Device().dadb, "origin") }
            try {
                var outcome: BackupState = BackupState.Idle
                val history = mutableListOf<Pair<String, Boolean>>()
                val refreshedSessions = mutableListOf<Long>()
                val origin = holder.requireSession()
                completeBackupRestore(
                    summary, "Partial", holder, origin,
                    publishOutcome = { outcome = it },
                    recordHistory = { message, success -> history += message to success },
                    onRestored = { refreshedSessions += holder.requireSession().generation }
                )
                assertEquals(BackupState.Failed("Partial"), outcome)
                assertEquals(listOf("Partial" to false), history)
                assertEquals(listOf(origin.generation), refreshedSessions)
            } finally {
                holder.clear()
            }
        }
    }

    @Test fun `empty successful completion skips refresh and retains successful history`() = runTest {
        val holder = ConnectionHolder().apply { set(Device().dadb, "origin") }
        try {
            var outcome: BackupState = BackupState.Idle
            val history = mutableListOf<Boolean>()
            var refreshCount = 0
            completeBackupRestore(
                PortalRestoreSummary(0, emptyList(), 0), "No changes", holder, holder.requireSession(),
                publishOutcome = { outcome = it },
                recordHistory = { _, success -> history += success },
                onRestored = { refreshCount++ }
            )
            assertEquals(BackupState.Success("No changes"), outcome)
            assertEquals(listOf(true), history)
            assertEquals(0, refreshCount)
        } finally {
            holder.clear()
        }
    }

    @Test fun `session replacement during history prevents stale refresh callback`() = runTest {
        val holder = ConnectionHolder().apply { set(Device().dadb, "origin") }
        try {
            val origin = holder.requireSession()
            val historyStarted = CompletableDeferred<Unit>()
            val finishHistory = CompletableDeferred<Unit>()
            val refreshedSessions = mutableListOf<Long>()
            val completion = async {
                runCatching {
                    completeBackupRestore(
                        PortalRestoreSummary(1, emptyList(), 0), "Restored", holder, origin,
                        publishOutcome = {},
                        recordHistory = { _, _ -> historyStarted.complete(Unit); finishHistory.await() },
                        onRestored = { refreshedSessions += holder.requireSession().generation }
                    )
                }
            }
            historyStarted.await()
            holder.set(Device().dadb, "replacement")
            finishHistory.complete(Unit)
            assertTrue(completion.await().exceptionOrNull() is CancellationException)
            assertTrue(refreshedSessions.isEmpty())
        } finally {
            holder.clear()
        }
    }

    @Test fun `stale restore cannot publish state history or callback after reconnecting same transport`() = runTest {
        val transport = Device().dadb
        val holder = ConnectionHolder().apply { set(transport, "origin") }
        val origin = holder.requireSession()
        holder.clear()
        holder.set(transport, "reconnected")
        try {
            val outcomes = mutableListOf<BackupState>()
            val history = mutableListOf<Boolean>()
            var refreshCount = 0
            val result = runCatching {
                completeBackupRestore(
                    PortalRestoreSummary(1, emptyList(), 0), "Restored", holder, origin,
                    publishOutcome = { outcomes += it },
                    recordHistory = { _, success -> history += success },
                    onRestored = { refreshCount++ }
                )
            }
            assertTrue(result.exceptionOrNull() is CancellationException)
            assertTrue(outcomes.isEmpty())
            assertTrue(history.isEmpty())
            assertEquals(0, refreshCount)
        } finally {
            holder.clear()
        }
    }
}
