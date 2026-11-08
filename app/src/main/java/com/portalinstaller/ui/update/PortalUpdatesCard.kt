/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.update

import android.content.Context
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.portalinstaller.data.companion.CompanionClient
import com.portalinstaller.data.companion.FileCompanionSessionStore
import com.portalinstaller.data.ota.PortalOtaClient
import com.portalinstaller.data.ota.PortalUpdatesController
import com.portalinstaller.ui.controlcenter.GroupCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun updatesController(context: Context): PortalUpdatesController {
    val store = FileCompanionSessionStore.create(context.applicationContext)
    return PortalUpdatesController(PortalOtaClient(CompanionClient()),
        sessions = { withContext(Dispatchers.IO) { store.load() } })
}

private val SMALL_SPINNER = 18.dp

/**
 * Home: "Hearthpane 1.229 available on Living · Update now" for each paired Portal with an update.
 * Hidden when none has one (older Portals without `/remote/ota` count as none). Polls only while
 * an update is in progress and the app is in front.
 */
@Composable
fun PortalUpdatesCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val controller = remember { updatesController(context) }
    val state by controller.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(controller) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            controller.load()
            while (true) {
                delay(PortalUpdatesController.POLL_MS)
                if (controller.state.value.anyBusy) controller.poll()
            }
        }
    }
    val lines = state.lines()
    if (lines.isEmpty() && state.notice == null) return
    GroupCard(modifier.testTag("home-portal-updates")) {
        Text("Portal updates", style = MaterialTheme.typography.titleMedium)
        lines.forEach { (roomId, line) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(line.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    color = if (line.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(8.dp))
                val action = line.action
                if (line.busy) CircularProgressIndicator(Modifier.size(SMALL_SPINNER), strokeWidth = 2.dp)
                else if (action != null) TextButton(onClick = { scope.launch { controller.install(roomId) } }) { Text(action) }
            }
        }
        state.notice?.let { notice ->
            Text(notice.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
