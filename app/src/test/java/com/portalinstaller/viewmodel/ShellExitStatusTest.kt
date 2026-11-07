/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.portalinstaller.PortalApplication
import com.portalinstaller.R
import com.portalinstaller.data.AppContainer
import com.portalinstaller.data.ConnectionHolder
import com.portalinstaller.data.store.ShellHistoryStore
import dadb.AdbShellResponse
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ShellExitStatusTest {
    private fun fixture(
        response: AdbShellResponse? = null,
        failure: Throwable? = null,
        command: String = "  generated command  ",
        connected: Boolean = true,
        expected: ShellEntry?,
        saved: Boolean = false
    ) {
        val scheduler = TestCoroutineScheduler()
        val store = ViewModelStore()
        val owner = Thread.currentThread()
        val previousHandler = owner.uncaughtExceptionHandler
        val uncaught = mutableListOf<Throwable>()
        var vm: ShellViewModel? = null
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        try {
            owner.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error ->
                uncaught.add(error)
            }
            val app = Mockito.mock(PortalApplication::class.java)
            val container = Mockito.mock(AppContainer::class.java)
            val holder = Mockito.mock(ConnectionHolder::class.java)
            val history = Mockito.mock(ShellHistoryStore::class.java)
            val dadb = Mockito.mock(Dadb::class.java)
            val context = Mockito.mock(Context::class.java)
            Mockito.`when`(app.applicationContext).thenReturn(app)
            Mockito.`when`(app.container).thenReturn(container)
            Mockito.`when`(container.connectionHolder).thenReturn(holder)
            Mockito.`when`(container.shellHistoryStore).thenReturn(history)
            Mockito.`when`(holder.dadb).thenReturn(MutableStateFlow(if (connected) dadb else null))
            Mockito.`when`(history.commands).thenReturn(flowOf(emptyList()))
            Mockito.`when`(context.getString(R.string.shell_not_connected)).thenReturn("Not connected")
            Mockito.`when`(context.getString(R.string.error_unknown)).thenReturn("Unknown error")
            val trimmed = command.trim()
            if (response != null) Mockito.`when`(dadb.shell(trimmed)).thenReturn(response)
            if (failure != null) Mockito.`when`(dadb.shell(trimmed)).thenThrow(failure)

            val actual = ShellViewModel(app)
            vm = actual
            store.put("fixture", actual)
            actual.run(context, command)
            scheduler.runCurrent()
            val expectedSize = if (expected == null) 0 else 1
            val deadline = System.nanoTime() + 5_000_000_000L
            while (actual.isRunning.value || actual.transcript.value.size != expectedSize) {
                check(System.nanoTime() < deadline) { "Shell fixture did not complete its public run" }
                Thread.sleep(1)
                scheduler.runCurrent()
            }
            scheduler.runCurrent()
            assertTrue("Unexpected uncaught failure: $uncaught", uncaught.isEmpty())
            assertFalse(actual.isRunning.value)
            val savedCommands = Mockito.mockingDetails(history).invocations
                .filter { it.method.name == "add" }
                .map { it.arguments[0] as String }
            val expectedTranscript = expected?.let(::listOf).orEmpty()
            val expectedHistory = if (saved) listOf(trimmed) else emptyList()
            assertTrue(
                "transcript=${actual.transcript.value}; history=$savedCommands",
                actual.transcript.value == expectedTranscript && savedCommands == expectedHistory
            )
            if (connected && trimmed.isNotEmpty()) {
                Mockito.verify(dadb).shell(trimmed)
            } else {
                Mockito.verifyNoInteractions(dadb)
            }
        } finally {
            try {
                val scope = vm?.viewModelScope?.coroutineContext?.get(Job)
                store.clear()
                scheduler.runCurrent()
                val deadline = System.nanoTime() + 5_000_000_000L
                while (scope?.children?.any { !it.isCompleted } == true) {
                    check(System.nanoTime() < deadline) { "Shell fixture lifecycle did not retire" }
                    Thread.sleep(1)
                    scheduler.runCurrent()
                }
            } finally {
                owner.uncaughtExceptionHandler = previousHandler
                Dispatchers.resetMain()
            }
        }
    }

    @Test fun `returned exit one reports stderr as an error without saving history`() {
        fixture(
            response = AdbShellResponse("", "generated missing file\n", 1),
            expected = ShellEntry("generated command", "generated missing file", true)
        )
    }

    @Test fun `returned exit 127 is an error even with no output`() {
        fixture(
            response = AdbShellResponse("", "", 127),
            expected = ShellEntry("generated command", "", true)
        )
    }

    @Test fun `negative returned exit remains an error`() {
        fixture(
            response = AdbShellResponse("generated interrupted\n", "", -1),
            expected = ShellEntry("generated command", "generated interrupted", true)
        )
    }

    @Test fun `exit zero retains stdout trimming and successful history`() {
        fixture(
            response = AdbShellResponse("generated success\n\n", "", 0),
            expected = ShellEntry("generated command", "generated success", false),
            saved = true
        )
    }

    @Test fun `exit zero with stderr remains successful`() {
        fixture(
            response = AdbShellResponse("", "generated warning\n", 0),
            expected = ShellEntry("generated command", "generated warning", false),
            saved = true
        )
    }

    @Test fun `thrown transport failure retains existing error behavior`() {
        fixture(
            failure = IOException("generated shell failure"),
            expected = ShellEntry("generated command", "generated shell failure", true)
        )
    }

    @Test fun `disconnected command keeps the existing localized error`() {
        fixture(
            connected = false,
            expected = ShellEntry("generated command", "Not connected", true)
        )
    }

    @Test fun `blank command has no transcript transport or history effect`() {
        fixture(command = " \n ", expected = null)
    }
}
