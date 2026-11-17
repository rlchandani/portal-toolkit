/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.timers

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.portalinstaller.data.timers.TimerAlerts
import com.portalinstaller.data.timers.TimerCommand
import com.portalinstaller.data.timers.TimerMerge
import com.portalinstaller.data.timers.TimersRepository
import com.portalinstaller.data.timers.timersRepository
import com.portalinstaller.ui.controlcenter.EmptyMessage
import com.portalinstaller.ui.music.Note
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The Portals are read again this often while the page is visible. */
private const val POLL_MS = 5_000L
/** The countdown text redraws this often. */
private const val TICK_MS = 1_000L

/**
 * Home › Timers: start a timer (1/5/10/15/30 min or custom, with a name) that shows on every
 * Portal, and pause / +1 min / cancel / stop any running one. Reads the Portals only while
 * visible; a phone notification says when a timer ends ([TimerAlerts]).
 */
@Composable
fun TimersPageScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { timersRepository(context) }
    val state by repository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val snackbars = remember { SnackbarHostState() }
    var custom by remember { mutableStateOf(false) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(repository, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                repository.refresh()
                TimerAlerts.sync(context, repository.state.value.timers)
                delay(POLL_MS)
            }
        }
    }
    LaunchedEffect(state.notice?.seq) { state.notice?.let { snackbars.showSnackbar(it.text) } }
    val now by produceState(SystemClock.elapsedRealtime()) {
        while (true) { delay(TICK_MS); value = SystemClock.elapsedRealtime() }
    }
    val send: (TimerCommand) -> Unit = { command ->
        scope.launch {
            if (repository.command(command)) TimerAlerts.sync(context, repository.state.value.timers)
        }
    }
    val start: (String, Long) -> Unit = { name, ms ->
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        send(TimerCommand.Start(name, ms))
    }
    val shown = TimerMerge.display(state.timers, now)
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "back") {
                TextButton(onClick = onBack) { Text("‹ Timers", style = MaterialTheme.typography.titleLarge) }
            }
            item(key = "intro") { Note("Timers show on every Portal. This phone tells you when one ends.") }
            when {
                !state.loaded -> item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                state.portals == 0 -> item(key = "none") { EmptyMessage("No Portals yet", "Pair a Portal to start timers in every room.") }
                state.notOnHomeWifi -> item(key = "away") { EmptyMessage("Can’t reach your Portals", TimersRepository.OFFLINE) }
                state.needsUpdate -> item(key = "update") {
                    EmptyMessage("Update Hearthpane", "Your Portals need the latest Hearthpane for shared timers.")
                }
                else -> {
                    item(key = "start") {
                        StartTimerCard(enabled = TimersRepository.START !in state.pending, onStart = start, onCustom = { custom = true })
                    }
                    if (shown.isEmpty()) item(key = "empty") {
                        EmptyMessage("No timers running", "Timers started on any Portal appear here too.")
                    }
                    items(shown, key = { it.id }) { timer ->
                        TimerCard(timer, now, pending = timer.id in state.pending, onCommand = send)
                    }
                }
            }
        }
        SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter))
    }
    if (custom) CustomTimerDialog(onStart = { name, ms -> custom = false; start(name, ms) }, onDismiss = { custom = false })
}
