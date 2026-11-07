/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.share

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val UNDO_WINDOW_MS = 6_000L

/** Live per-file progress. A row turns green only when the Portal acknowledged that file. */
@Composable
internal fun ShareJobSheet(hub: ShareHub, jobId: String, onClose: () -> Unit) {
    val jobs by hub.runner.jobs.collectAsState()
    val job = jobs.firstOrNull { it.id == jobId } ?: run { LaunchedEffect(Unit) { onClose() }; return }
    val scope = rememberCoroutineScope()
    var confirmStop by remember { mutableStateOf(false) }
    var undoOpen by remember { mutableStateOf(true) }
    DisposableEffect(jobId) {
        hub.notifier.visibleJobId = jobId
        onDispose { if (hub.notifier.visibleJobId == jobId) hub.notifier.visibleJobId = null }
    }
    LaunchedEffect(jobId) { if (job.kind != ShareKind.PHOTOS) hub.refreshRemoteStates() }

    val sm = job.summary()
    val head = ShareCopy.headline(job)
    Text(head.title, style = MaterialTheme.typography.headlineSmall)
    Text(head.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))

    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
            job.rooms.forEach { room ->
                if (job.rooms.size > 1) {
                    val (ok, total) = job.roomSummary(room.id)
                    Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp, 16.dp, 4.dp)) {
                        Text(room.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text("$ok of $total confirmed", style = MaterialTheme.typography.bodySmall)
                    }
                }
                job.items.forEachIndexed { index, item ->
                    if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    FileRow(hub, job, room, item)
                }
            }
        }
    }

    when {
        job.cancelled || sm.allConfirmed && job.kind == ShareKind.PHOTOS -> SheetActions("Done", onClose, null, {})
        sm.active -> SheetActions("Hide", onClose, "Stop sending", { confirmStop = true }, outlinedPrimary = true)
        sm.held -> SheetActions("Cancel send", { hub.runner.cancelWaiting(jobId); hub.notifier.cancel(ShareNotifier.waitingId(jobId)) }, "OK", onClose)
        sm.allConfirmed && job.kind == ShareKind.NOTE && undoOpen -> {
            LaunchedEffect(jobId) { delay(UNDO_WINDOW_MS); undoOpen = false }
            SheetActions("Undo", {
                scope.launch {
                    job.rooms.forEach { hub.sender.retract(it.id, jobId) }
                    hub.refreshRemoteStates()
                    undoOpen = false
                }
            }, "Done", onClose)
        }
        sm.allConfirmed -> SheetActions("Done", onClose, null, {})
        job.retryCount() > 0 -> SheetActions("Done", onClose, ShareCopy.retryLabel(job), { hub.runner.retryFailed(jobId) })
        job.hasUnconfirmed() -> SheetActions("Done", onClose, "Check again", { hub.runner.checkAgain(jobId) })
        else -> SheetActions("Done", onClose, null, {})
    }

    if (confirmStop) AlertDialog(onDismissRequest = { confirmStop = false },
        title = { Text("Stop sending?") },
        text = { Text(if (sm.confirmed > 0) "${sm.confirmed} already on the Portal stay there. The rest won’t be sent." else "Nothing more will be sent.") },
        confirmButton = { TextButton(onClick = { hub.runner.stop(jobId); confirmStop = false }) { Text("Stop sending") } },
        dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Keep sending") } })
}

@Composable
private fun FileRow(hub: ShareHub, job: ShareJob, room: ShareRoom, item: ShareItem) {
    val s = job.state(room.id, item.id)
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Thumb(hub, job, item)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(if (job.kind == ShareKind.PHOTOS) item.name else job.text ?: job.link?.title ?: job.link?.url.orEmpty(),
                style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(ShareCopy.row(job, room, item), style = MaterialTheme.typography.bodyMedium, color = when (s.status) {
                ItemStatus.CONFIRMED -> ConfirmedGreen
                ItemStatus.UNCONFIRMED -> UnconfirmedAmber
                ItemStatus.FAILED -> FailedRed
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            })
            if (s.status == ItemStatus.SENDING) LinearProgressIndicator(progress = { ShareCopy.percent(s, item) / 100f },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        }
        Text(when (s.status) {
            ItemStatus.CONFIRMED -> "✓"
            ItemStatus.FAILED -> "!"
            ItemStatus.UNCONFIRMED -> "i"
            ItemStatus.HELD -> "…"
            else -> ""
        }, style = MaterialTheme.typography.titleLarge, color = when (s.status) {
            ItemStatus.CONFIRMED -> ConfirmedGreen
            ItemStatus.FAILED -> FailedRed
            else -> UnconfirmedAmber
        })
        if (s.status == ItemStatus.SENDING || s.status == ItemStatus.CONFIRMING) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
    }
}

@Composable
private fun Thumb(hub: ShareHub, job: ShareJob, item: ShareItem) {
    val bitmap by produceState<ImageBitmap?>(null, job.id, item.id) {
        if (job.kind != ShareKind.PHOTOS) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val file = hub.store.itemFile(job.id, item.id)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 96 && bounds.outHeight / (sample * 2) >= 96) sample *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
    }
    val shape = RoundedCornerShape(12.dp)
    val image = bitmap
    if (image != null) Image(image, null, Modifier.size(48.dp).clip(shape), contentScale = ContentScale.Crop)
    else Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.surfaceVariant, shape), contentAlignment = Alignment.Center) {
        Text(if (job.kind == ShareKind.LINK) "↗" else if (job.kind == ShareKind.NOTE) "✎" else "", style = MaterialTheme.typography.titleMedium)
    }
}
