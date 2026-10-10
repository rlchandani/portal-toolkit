/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.controlcenter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.portalinstaller.data.companion.ControlCenterState
import com.portalinstaller.data.companion.GroupOperation
import com.portalinstaller.data.companion.GroupWorkStatus

/** Passive destination summary; the top Portal menu owns all selection controls. */
@Composable
internal fun ManagePortalsBar(state: ControlCenterState, photos: Boolean) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
        .testTag("group-target-summary"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${state.selectedTargets.size} selected", style = MaterialTheme.typography.labelLarge)
        Text(if (state.selectedTargets.isEmpty()) "No targets selected. Choose Portals in the top menu before applying changes."
            else "${if (photos) "Uploads to" else "Settings apply to"}: ${state.selectedTargets.joinToString { it.name }}",
            style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("Values from: ${state.activeDevice?.name ?: "no active Portal"}",
            style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (state.selectedTargets.size > 1) Text("Other rooms may differ.",
            style = MaterialTheme.typography.bodySmall)
        if (state.targetSelectionSaving) Text("Saving selection…",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun GroupProgressPanel(operations: List<GroupOperation>, onRetry: (Long) -> Unit,
    onCancel: (Long) -> Unit, onDismiss: (Long) -> Unit) {
    for (op in operations) GroupCard(Modifier.testTag("group-operation-${op.id}")) {
        Text(op.title, style = MaterialTheme.typography.titleSmall)
        Text("${op.items.count { it.status == GroupWorkStatus.SUCCEEDED }} confirmed · ${op.items.count { it.status == GroupWorkStatus.FAILED }} failed · ${op.items.count { it.status == GroupWorkStatus.CANCELLED }} cancelled",
            style = MaterialTheme.typography.bodySmall)
        for ((targetId, targetItems) in op.items.groupBy { it.targetId }) {
            if (targetItems.size > 1) Text("${targetItems.first().targetName}: ${targetItems.count { it.status == GroupWorkStatus.SUCCEEDED }}/${targetItems.size} confirmed, ${targetItems.count { it.status == GroupWorkStatus.FAILED }} failed, ${targetItems.count { it.status == GroupWorkStatus.QUEUED }} queued",
                style = MaterialTheme.typography.labelLarge)
            // Eight metadata rows per room; failure-first paging retains all outcomes without
            // composing an entire imported collection inside a single LazyColumn item.
            var page by remember(op.id, targetId) { mutableIntStateOf(0) }
            val ordered = targetItems.sortedBy { when (it.status) {
                GroupWorkStatus.FAILED -> 0
                GroupWorkStatus.SENDING, GroupWorkStatus.CHECKING -> 1
                else -> 2
            } }
            val pages = ((ordered.size + 7) / 8).coerceAtLeast(1)
            val shownPage = page.coerceIn(0, pages - 1)
            for (item in ordered.drop(shownPage * 8).take(8)) {
            Text("${item.targetName} · ${item.name}: ${when (item.status) {
                GroupWorkStatus.QUEUED -> "Queued"
                GroupWorkStatus.CHECKING -> "Checking support"
                GroupWorkStatus.SENDING -> "Sending"
                GroupWorkStatus.SUCCEEDED -> "Confirmed"
                GroupWorkStatus.FAILED -> "Failed"
                GroupWorkStatus.CANCELLED -> "Cancelled"
            }}${if (item.bytesSent > 0) " · ${item.bytesSent / 1024} KB" else ""}",
                style = MaterialTheme.typography.bodyMedium)
            item.folder?.let { Text("Destination: $it", style = MaterialTheme.typography.bodySmall) }
            item.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                color = if (item.status == GroupWorkStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (pages > 1) {
                Text("Image results ${shownPage + 1} of $pages · failures shown first", style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = { page = shownPage - 1 }, enabled = shownPage > 0) { Text("Previous results") }
                    TextButton(onClick = { page = shownPage + 1 }, enabled = shownPage + 1 < pages) { Text("Next results") }
                }
            }
        }
        if (op.cancelRequested) Text("Queued work cancelled. Requests already sent may finish; posted changes and photos are not undone.")
        if (op.running) TextButton(onClick = { onCancel(op.id) }, enabled = !op.cancelRequested) { Text("Cancel queued work") }
        else {
            if (op.retryable) TextButton(onClick = { onRetry(op.id) }) { Text("Retry failed only") }
            TextButton(onClick = { onDismiss(op.id) }) { Text("Dismiss results") }
        }
    }
}
