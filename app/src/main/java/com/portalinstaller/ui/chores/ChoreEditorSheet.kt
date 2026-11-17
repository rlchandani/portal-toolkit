/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.chores

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.portalinstaller.data.chores.ChoreCopy
import com.portalinstaller.data.chores.ChoreDays
import com.portalinstaller.data.chores.ChoreDraft
import com.portalinstaller.data.chores.ChoreKid
import com.portalinstaller.data.chores.ChoreLimits
import com.portalinstaller.data.chores.ChoreRepeat
import com.portalinstaller.data.routines.RoutinePictures
import com.portalinstaller.ui.routines.RoutinePicture

/** How far ahead "Just once" can be planned. */
private const val ONCE_DAYS_AHEAD = 7
private val PictureTile = 56.dp

/** Add or edit a chore: picture, title, who, stars, when, and whether it needs a parent's OK. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ChoreEditorSheet(initial: ChoreDraft, kids: List<ChoreKid>, today: Int, sending: Boolean,
                              onDismiss: () -> Unit, onSave: (ChoreDraft) -> Unit, onRemove: (() -> Unit)?) {
    var draft by remember { mutableStateOf(if (initial.day < today) initial.copy(day = today) else initial) }
    var confirmRemove by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                RoutinePicture(draft.pic, 72.dp)
                OutlinedTextField(draft.title, { draft = draft.copy(title = it.take(ChoreLimits.TITLE_MAX)) }, Modifier.weight(1f),
                    singleLine = true, label = { Text("Chore") })
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RoutinePictures.all.forEach { p ->
                    val on = p.id == draft.pic
                    Box(Modifier.size(PictureTile).clip(RoundedCornerShape(14.dp))
                        .background(if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                        .border(2.dp, if (on) MaterialTheme.colorScheme.onSurface else Color.Transparent, RoundedCornerShape(14.dp))
                        .semantics { contentDescription = p.label }
                        .clickable {
                            val keepTitle = draft.title.isNotBlank() && draft.title != RoutinePictures.label(draft.pic)
                            draft = draft.copy(pic = p.id, title = if (keepTitle) draft.title else p.label)
                        }, contentAlignment = Alignment.Center) { RoutinePicture(p.id, 40.dp) }
                }
            }
            Label("Who")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(draft.kid == null, { draft = draft.copy(kid = null) }, label = { Text("Anyone") })
                kids.forEach { k -> FilterChip(draft.kid == k.id, { draft = draft.copy(kid = k.id) }, label = { Text("${k.avatar} ${k.name}") }) }
            }
            Label("Stars")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (ChoreLimits.MIN_STARS..ChoreLimits.MAX_STARS).forEach { n ->
                    FilterChip(draft.stars == n, { draft = draft.copy(stars = n) }, label = { Text("★".repeat(n)) })
                }
            }
            Label("When")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoreRepeat.entries.forEach { r -> FilterChip(draft.repeat == r, { draft = draft.copy(repeat = r) }, label = { Text(r.label) }) }
            }
            when (draft.repeat) {
                ChoreRepeat.DAYS -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..7).forEach { d ->
                        FilterChip(d in draft.days, { draft = draft.copy(days = if (d in draft.days) draft.days - d else draft.days + d) },
                            label = { Text(ChoreCopy.weekday(d)) })
                    }
                }
                ChoreRepeat.ONCE -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (0 until ONCE_DAYS_AHEAD).forEach { ahead ->
                        val day = today + ahead
                        val label = when (ahead) { 0 -> "Today"; 1 -> "Tomorrow"; else -> ChoreCopy.weekday(ChoreDays.weekday(day)) }
                        FilterChip(draft.day == day, { draft = draft.copy(day = day) }, label = { Text(label) })
                    }
                }
                ChoreRepeat.DAILY -> Unit
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Needs a parent’s OK")
                    Text("Stars wait until you say OK here.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(draft.needsOk, { draft = draft.copy(needsOk = it) })
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                onRemove?.let { TextButton(onClick = { confirmRemove = true }, enabled = !sending) { Text("Remove", color = MaterialTheme.colorScheme.error) } }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = { onSave(draft) }, enabled = draft.valid && !sending) { Text("Save") }
            }
        }
    }
    if (confirmRemove && onRemove != null) ConfirmRemoveDialog("Remove ${draft.title.trim()}?",
        "It leaves every Portal. Stars already earned stay.", onDismiss = { confirmRemove = false }) {
        confirmRemove = false
        onRemove()
    }
}

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.labelLarge)

@Composable
internal fun ConfirmRemoveDialog(title: String, body: String, onDismiss: () -> Unit, onConfirm: () -> Unit) =
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep") } })
