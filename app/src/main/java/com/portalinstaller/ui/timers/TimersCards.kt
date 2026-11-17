/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.timers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.portalinstaller.data.timers.PhoneTimer
import com.portalinstaller.data.timers.TimerCommand
import com.portalinstaller.data.timers.TimerState
import com.portalinstaller.data.timers.TimerText
import com.portalinstaller.ui.common.JoinedAction
import com.portalinstaller.ui.common.JoinedActionGroup
import com.portalinstaller.ui.controlcenter.GroupCard

private val ChipHeight = 48.dp

/** Name (optional) plus one-tap 1/5/10/15/30 min starts and "Custom…". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StartTimerCard(enabled: Boolean, onStart: (String, Long) -> Unit, onCustom: () -> Unit) {
    var name by remember { mutableStateOf("") }
    GroupCard {
        Text("Start a timer", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(name, { name = limit(it) }, label = { Text("Name (optional)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TimerText.PRESET_MINUTES.forEach { minutes ->
                AssistChip(onClick = { onStart(TimerText.clean(name) ?: TimerText.presetName(minutes), minutes * MINUTE_MS); name = "" },
                    enabled = enabled, label = { Text(TimerText.presetName(minutes)) }, modifier = Modifier.heightIn(min = ChipHeight))
            }
            AssistChip(onClick = onCustom, enabled = enabled, label = { Text("Custom…") }, modifier = Modifier.heightIn(min = ChipHeight))
        }
    }
}

/** One household timer: name, room, time left and its controls (Stop while it rings). */
@Composable
internal fun TimerCard(timer: PhoneTimer, nowMs: Long, pending: Boolean, onCommand: (TimerCommand) -> Unit) {
    val ringing = timer.state == TimerState.RINGING
    GroupCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(timer.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(TimerText.subtitle(timer), style = MaterialTheme.typography.bodyMedium,
                    color = if (ringing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(if (ringing) "Time’s up" else TimerText.clock(timer.remaining(nowMs)), style = MaterialTheme.typography.headlineMedium)
        }
        val enabled = !pending
        JoinedActionGroup(when (timer.state) {
            TimerState.RINGING -> listOf(
                JoinedAction("Stop", { onCommand(TimerCommand.Stop(timer.id)) }, enabled, emphasized = true),
                JoinedAction("+1 min", { onCommand(TimerCommand.AddMinute(timer.id)) }, enabled),
            )
            else -> listOf(
                if (timer.paused) JoinedAction("Resume", { onCommand(TimerCommand.Resume(timer.id)) }, enabled, emphasized = true)
                else JoinedAction("Pause", { onCommand(TimerCommand.Pause(timer.id)) }, enabled),
                JoinedAction("+1 min", { onCommand(TimerCommand.AddMinute(timer.id)) }, enabled),
                JoinedAction("Cancel", { onCommand(TimerCommand.Cancel(timer.id)) }, enabled),
            )
        })
    }
}

/** Hours + minutes and an optional name. */
@Composable
internal fun CustomTimerDialog(onStart: (String, Long) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var hours by remember { mutableStateOf("0") }
    var minutes by remember { mutableStateOf("20") }
    val duration = TimerText.duration(hours, minutes)
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Custom timer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = limit(it) }, label = { Text("Name (optional)") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(hours, { hours = it.filter(Char::isDigit).take(2) }, label = { Text("Hours") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(2) }, label = { Text("Minutes") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                }
                if (duration == null) Text("Hours 0–23, minutes 0–59, more than zero.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = { duration?.let { onStart(TimerText.clean(name) ?: "Timer", it) } }, enabled = duration != null) { Text("Start") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

private const val MINUTE_MS = 60_000L

private fun limit(text: String): String =
    if (text.codePointCount(0, text.length) <= PhoneTimer.MAX_NAME) text else text.substring(0, text.offsetByCodePoints(0, PhoneTimer.MAX_NAME))
