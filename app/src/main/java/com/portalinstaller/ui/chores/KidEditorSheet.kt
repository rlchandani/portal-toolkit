/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.chores

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.portalinstaller.data.chores.ChoreAvatars
import com.portalinstaller.data.chores.ChoreLimits
import com.portalinstaller.data.chores.KidDraft

/** Add or edit a kid: name, avatar, colour, weekly goal and reward. Removing asks first. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun KidEditorSheet(initial: KidDraft, sending: Boolean, onDismiss: () -> Unit, onSave: (KidDraft) -> Unit,
                            onRemove: (() -> Unit)?) {
    var draft by remember { mutableStateOf(initial) }
    var goalText by remember { mutableStateOf(if (initial.goal > 0) initial.goal.toString() else "") }
    var confirmRemove by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                KidAvatar(draft.avatar, draft.color, ChoreUi.AvatarLarge)
                OutlinedTextField(draft.name, { draft = draft.copy(name = it.take(ChoreLimits.NAME_MAX)) }, Modifier.weight(1f),
                    singleLine = true, label = { Text("Name") })
            }
            Text("Avatar", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoreAvatars.all.forEach { a ->
                    val on = a == draft.avatar
                    KidAvatar(a, draft.color, ChoreUi.MinTouch, Modifier.border(3.dp, if (on) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
                        .semantics { contentDescription = "Avatar $a" }.clickable { draft = draft.copy(avatar = a) })
                }
            }
            Text("Colour", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (0 until ChoreLimits.COLORS).forEach { c ->
                    val on = c == draft.color
                    KidAvatar("", c, ChoreUi.MinTouch, Modifier.border(3.dp, if (on) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
                        .semantics { contentDescription = "Colour ${c + 1}" }.clickable { draft = draft.copy(color = c) })
                }
            }
            Text("Weekly goal", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(goalText, { text ->
                    goalText = text.filter(Char::isDigit).take(3)
                    draft = draft.copy(goal = goalText.toIntOrNull() ?: 0)
                }, Modifier.weight(0.4f), singleLine = true, label = { Text("Stars") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(draft.reward, { draft = draft.copy(reward = it.take(ChoreLimits.REWARD_MAX)) }, Modifier.weight(0.6f),
                    singleLine = true, label = { Text("Reward") }, placeholder = { Text("Movie night") })
            }
            if (draft.goal > ChoreLimits.MAX_GOAL) Text("Pick a goal up to ${ChoreLimits.MAX_GOAL} stars.", color = MaterialTheme.colorScheme.error)
            Text("Leave the goal empty for no goal. Stars start fresh every Monday.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                onRemove?.let { TextButton(onClick = { confirmRemove = true }, enabled = !sending) { Text("Remove", color = MaterialTheme.colorScheme.error) } }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = { onSave(draft) }, enabled = draft.valid && !sending) { Text("Save") }
            }
        }
    }
    if (confirmRemove && onRemove != null) ConfirmRemoveDialog("Remove ${draft.name.trim()}?",
        "Their own chores go too. Stars already earned stay in the history.", onDismiss = { confirmRemove = false }) {
        confirmRemove = false
        onRemove()
    }
}
