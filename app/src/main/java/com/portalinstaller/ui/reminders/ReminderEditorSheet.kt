/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.reminders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.portalinstaller.data.reminders.ReminderBoard
import com.portalinstaller.data.reminders.ReminderCopy
import com.portalinstaller.data.reminders.ReminderDraft
import com.portalinstaller.data.reminders.ReminderDue
import com.portalinstaller.data.reminders.ReminderLimits
import java.time.ZoneId

/** Add or edit a reminder: title, who it's for, an optional time, and (for grown-ups) Private. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ReminderEditorSheet(initial: ReminderDraft, board: ReminderBoard, nowMs: Long, zone: ZoneId, sending: Boolean,
                                 onDismiss: () -> Unit, onSave: (ReminderDraft) -> Unit) {
    var draft by remember { mutableStateOf(initial) }
    var picking by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (initial.id == null) "Add reminder" else "Edit reminder", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(draft.title, { draft = draft.copy(title = it.take(ReminderLimits.TITLE_MAX)) },
                Modifier.fillMaxWidth(), singleLine = true, label = { Text("Reminder") })
            Label("For")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(draft.person.isEmpty(), { draft = draft.forPerson(null) }, label = { Text(ReminderLimits.HOME) })
                board.people.forEach { p ->
                    FilterChip(draft.person == p.id, { draft = draft.forPerson(p) }, label = { Text(p.name) })
                }
            }
            Label("When")
            DueChips(draft.due, nowMs, zone, onPick = { picking = true }) { draft = draft.copy(due = it) }
            if (draft.due > 0) Text(ReminderCopy.due(draft.due, nowMs, zone), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (draft.showsPrivate(board)) PrivateSwitch(draft.private ?: false, board.person(draft.person)?.name.orEmpty()) {
                draft = draft.copy(private = it)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = { onSave(draft) }, enabled = draft.valid && !sending) { Text("Save") }
            }
        }
    }
    if (picking) DueTimePicker(draft.due.takeIf { it > 0 } ?: ReminderDue.tomorrowMorning(nowMs, zone), zone,
        onDismiss = { picking = false }) { draft = draft.copy(due = it); picking = false }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DueChips(due: Long, nowMs: Long, zone: ZoneId, onPick: () -> Unit, onDue: (Long) -> Unit) {
    val evening = ReminderDue.thisEvening(nowMs, zone)
    val morning = ReminderDue.tomorrowMorning(nowMs, zone)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(due == 0L, { onDue(0L) }, label = { Text("No time") })
        evening?.let { FilterChip(due == it, { onDue(it) }, label = { Text("Today 6 pm") }) }
        FilterChip(due == morning, { onDue(morning) }, label = { Text("Tomorrow 9 am") })
        FilterChip(due > 0 && due != evening && due != morning, onPick, label = { Text("Pick…") })
    }
}

@Composable
private fun PrivateSwitch(on: Boolean, name: String, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Private")
            Text(if (on) "Only $name’s Home shows it." else "Every Portal shows it.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(on, onChange)
    }
}

/** A date, then a time, on this phone's clock. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DueTimePicker(seed: Long, zone: ZoneId, onDismiss: () -> Unit, onPicked: (Long) -> Unit) {
    var dateMs by remember { mutableStateOf<Long?>(null) }
    val seedTime = remember(seed) { ReminderDue.localTime(seed, zone) }
    val date = rememberDatePickerState(initialSelectedDateMillis = ReminderDue.pickerDate(seed, zone))
    val time = rememberTimePickerState(initialHour = seedTime.hour, initialMinute = seedTime.minute, is24Hour = false)
    val chosen = dateMs
    if (chosen == null) {
        DatePickerDialog(onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = { dateMs = date.selectedDateMillis }, enabled = date.selectedDateMillis != null) { Text("Next") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }) { DatePicker(date) }
    } else {
        AlertDialog(onDismissRequest = onDismiss, title = { Text("Time") }, text = { TimePicker(time) },
            confirmButton = { TextButton(onClick = { onPicked(ReminderDue.combine(chosen, time.hour, time.minute, zone)) }) { Text("Done") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
    }
}

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.labelLarge)
