/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.share

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Toolkit → Settings: the name Portals show as the sender. The next share uses the new name.
 * Integrator places this inside the Settings tab.
 */
@Composable
fun ShareSenderNameSetting(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var saved by remember { mutableStateOf(SenderName.get(context)) }
    var draft by remember { mutableStateOf(saved) }
    Column(modifier.fillMaxWidth()) {
        Text("Name on shared photos and notes", style = MaterialTheme.typography.titleMedium)
        Text("Portals show “New from $saved”.", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(draft, { if (it.length <= SenderName.MAX) draft = it }, Modifier.fillMaxWidth().padding(top = 8.dp),
            singleLine = true, label = { Text("Phone name") })
        TextButton(onClick = { SenderName.set(context, draft); saved = SenderName.get(context); draft = saved },
            enabled = SenderName.clean(draft) != saved) { Text("Save name") }
    }
}

/**
 * Toolkit → Home: recent shares with their honest state; tapping one reopens its sheet.
 * Also resumes queued sends when the app opens, and refreshes note/link states from the Portals.
 */
@Composable
fun RecentSharesCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val hub = remember { ShareHub.get(context) }
    val jobs by hub.runner.jobs.collectAsState()
    LaunchedEffect(Unit) { hub.runner.resumeHeld(); hub.refreshRemoteStates() }
    if (jobs.isEmpty()) return
    Column(modifier.fillMaxWidth()) {
        Text("Shared to Portals", style = MaterialTheme.typography.titleMedium)
        jobs.take(5).forEach { job ->
            Column(Modifier.fillMaxWidth().clickable { context.startActivity(ShareActivity.openJob(context, job.id)) }.padding(vertical = 10.dp)) {
                Text(when (job.kind) {
                    ShareKind.PHOTOS -> "${job.items.size} photo${if (job.items.size == 1) "" else "s"} to ${ShareRooms.destination(job.rooms)}"
                    ShareKind.NOTE -> "Note to ${ShareRooms.destination(job.rooms)}"
                    ShareKind.LINK -> "Link to ${ShareRooms.destination(job.rooms)}"
                }, style = MaterialTheme.typography.bodyLarge)
                val remote = job.cells().firstNotNullOfOrNull { (room, item, s) ->
                    s.remoteState?.takeIf { it in setOf("dismissed", "cleared", "canceled", "opened", "retracted") }?.let { ShareCopy.row(job, room, item) }
                }
                Text(remote ?: ShareCopy.line(job), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
