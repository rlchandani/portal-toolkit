/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.reminders

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.portalinstaller.data.reminders.Reminder
import com.portalinstaller.data.reminders.ReminderCopy
import com.portalinstaller.ui.controlcenter.GroupCard
import java.time.ZoneId

private val MinTouch = 48.dp

/** A titled list of reminders: tick to finish (or bring back), tap to edit, ⋯ to delete. */
@Composable
internal fun ReminderListCard(title: String, items: List<Reminder>, nowMs: Long, zone: ZoneId, enabled: Boolean,
                              onToggle: (Reminder) -> Unit, onEdit: (Reminder) -> Unit, onDelete: (Reminder) -> Unit) {
    GroupCard {
        Text(title, style = MaterialTheme.typography.titleMedium)
        items.forEach { r -> ReminderRow(r, nowMs, zone, enabled, onToggle, onEdit, onDelete) }
    }
}

@Composable
private fun ReminderRow(r: Reminder, nowMs: Long, zone: ZoneId, enabled: Boolean,
                        onToggle: (Reminder) -> Unit, onEdit: (Reminder) -> Unit, onDelete: (Reminder) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val late = ReminderCopy.overdue(r, nowMs)
    Row(Modifier.fillMaxWidth().heightIn(min = MinTouch), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(r.done, { onToggle(r) }, enabled = enabled,
            modifier = Modifier.semantics { contentDescription = if (r.done) "Mark ${r.title} not done" else "Mark ${r.title} done" })
        Column(Modifier.weight(1f).clickable(enabled = enabled) { onEdit(r) }.padding(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(r.title, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (r.done) muted else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (r.done) TextDecoration.LineThrough else null)
            Text(ReminderCopy.line(r, nowMs, zone), style = MaterialTheme.typography.bodySmall,
                color = if (late) MaterialTheme.colorScheme.error else muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        RowMenu(r, enabled, onEdit, onDelete)
    }
}

@Composable
private fun RowMenu(r: Reminder, enabled: Boolean, onEdit: (Reminder) -> Unit, onDelete: (Reminder) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled,
            modifier = Modifier.semantics { contentDescription = "More for ${r.title}" }) {
            Text("⋯", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Edit") }, onClick = { open = false; onEdit(r) })
            DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                onClick = { open = false; onDelete(r) })
        }
    }
}

/** Muted lines for reminders only that person's Home shows. */
@Composable
internal fun PrivateCountsCard(counts: Map<String, Int>) {
    GroupCard {
        counts.forEach { (name, n) ->
            Text(ReminderCopy.privateLine(name, n), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
