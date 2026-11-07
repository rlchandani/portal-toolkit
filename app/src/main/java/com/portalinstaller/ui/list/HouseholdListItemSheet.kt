/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.portalinstaller.data.list.HouseholdListIds
import com.portalinstaller.data.list.ListItem
import com.portalinstaller.data.list.NoteKind

/** Callbacks of the item sheet. */
internal class SheetActions(
    val onRename: (String, String) -> Unit,
    val onMoveTop: (String) -> Unit,
    val onRemove: (String) -> Unit,
    val onUseAlt: (String) -> Unit,
    val onDismissNote: (String) -> Unit,
    val onClose: () -> Unit,
)

/** Tap a row: who added/ticked/renamed it and when, Rename, Move to top, Remove, merge notes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ItemSheet(item: ListItem?, lines: List<String>, noteDismissed: Boolean, actions: SheetActions) {
    ModalBottomSheet(onDismissRequest = actions.onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (item == null) {
                Text("This item was removed", style = MaterialTheme.typography.headlineSmall)
                Text("Someone removed it on another device.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = actions.onClose, modifier = Modifier.fillMaxWidth()) { Text("OK") }
                return@Column
            }
            var renaming by remember(item.id) { mutableStateOf(false) }
            var draft by remember(item.id) { mutableStateOf(item.text) }
            if (renaming) {
                val save = { actions.onRename(item.id, draft); renaming = false }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(draft, { draft = it.take(HouseholdListIds.MAX_TEXT) }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { save() }))
                    Spacer(Modifier.width(10.dp))
                    Button(onClick = save) { Text("Save") }
                }
            } else Text(item.text, style = MaterialTheme.typography.headlineSmall)
            lines.forEach { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item.note?.takeIf { !noteDismissed }?.let { note ->
                Text(note.text, style = MaterialTheme.typography.bodyMedium, color = WaitAmber)
                if (note.kind == NoteKind.EDITED && note.alt != null) {
                    FilledTonalButton(onClick = { actions.onUseAlt(item.id) }, modifier = Modifier.fillMaxWidth()) { Text("Use “${note.alt}” instead") }
                } else FilledTonalButton(onClick = { actions.onDismissNote(item.id) }) { Text("OK") }
            }
            if (!renaming) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { renaming = true }) { ListGlyph("edit"); Spacer(Modifier.width(6.dp)); Text("Rename") }
                if (!item.done) FilledTonalButton(onClick = { actions.onMoveTop(item.id) }) { Text("Move to top") }
                FilledTonalButton(onClick = { actions.onRemove(item.id) }) {
                    ListGlyph("trash", MaterialTheme.colorScheme.error); Spacer(Modifier.width(6.dp))
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
