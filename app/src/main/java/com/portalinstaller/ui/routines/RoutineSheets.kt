/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.routines

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portalinstaller.data.routines.Routine
import com.portalinstaller.data.routines.RoutineCard
import com.portalinstaller.data.routines.RoutineDates
import com.portalinstaller.data.routines.RoutineDraft
import com.portalinstaller.data.routines.RoutineHolidays
import com.portalinstaller.data.routines.RoutinePictures
import com.portalinstaller.data.routines.RoutineText
import com.portalinstaller.data.routines.RoutineValidation
import com.portalinstaller.viewmodel.RoutineUndo
import com.portalinstaller.viewmodel.RoutinesController
import kotlinx.coroutines.delay

/** The picture picker: grouped bundled pictures plus the card's label (default label follows the picture). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PicturePickerSheet(initial: RoutineCard?, fallbackPic: String, onDismiss: () -> Unit,
                                onRemove: (() -> Unit)?, onDone: (String, String) -> Unit) {
    var pic by remember { mutableStateOf(initial?.pic ?: fallbackPic) }
    var label by remember { mutableStateOf(initial?.label ?: RoutinePictures.label(fallbackPic)) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                RoutinePicture(pic, 72.dp)
                Column(Modifier.weight(1f)) {
                    OutlinedTextField(label, { label = it.take(RoutineValidation.LABEL_MAX) }, singleLine = true,
                        placeholder = { Text("Label") }, modifier = Modifier.fillMaxWidth())
                    Text("${label.length} of ${RoutineValidation.LABEL_MAX}. Short words read best.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            LazyVerticalGrid(GridCells.Fixed(5), Modifier.heightIn(max = 420.dp).padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RoutinePictures.groups.forEach { g ->
                    item(span = { GridItemSpan(maxLineSpan) }) { Text(g, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp)) }
                    items(RoutinePictures.all.filter { it.group == g }, key = { it.id }) { p ->
                        val on = p.id == pic
                        Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(14.dp))
                            .background(if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                            .border(2.dp, if (on) MaterialTheme.colorScheme.onSurface else Color.Transparent, RoundedCornerShape(14.dp))
                            .semantics { contentDescription = p.label }
                            .clickable {
                                if (label.isBlank() || label == RoutinePictures.label(pic)) label = p.label
                                pic = p.id
                            }, contentAlignment = Alignment.Center) { RoutinePicture(p.id, 44.dp) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                onRemove?.let { TextButton(onClick = it) { Text("Remove card", color = MaterialTheme.colorScheme.error) } }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = { onDone(pic, label) }, enabled = label.isNotBlank()) { Text("Done") }
            }
        }
    }
}

/** Hour and 5-minute steppers with AM/PM, as in the prototype. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimeSheet(title: String, initial: Int, onDismiss: () -> Unit, onOk: (Int) -> Unit) {
    var v by remember { mutableIntStateOf(initial) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(vertical = 16.dp)) {
                Stepper("${(v / 60 + 11) % 12 + 1}", "hour", { v = RoutineDraft.stepTime(v, 60) }) { v = RoutineDraft.stepTime(v, -60) }
                Text(":", fontSize = 48.sp)
                Stepper("%02d".format(v % 60), "5 minutes", { v = RoutineDraft.stepTime(v, 5) }) { v = RoutineDraft.stepTime(v, -5) }
                Column {
                    FilterChip(v < 720, { v = RoutineDraft.setPm(v, false) }, { Text("AM") })
                    FilterChip(v >= 720, { v = RoutineDraft.setPm(v, true) }, { Text("PM") })
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = { onOk(v) }) { Text("OK") }
            }
        }
    }
}

@Composable
private fun Stepper(value: String, unit: String, onUp: () -> Unit, onDown: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onUp, modifier = Modifier.semantics { contentDescription = "$unit later" }) { Text("▲") }
        Text(value, fontSize = 48.sp)
        IconButton(onClick = onDown, modifier = Modifier.semantics { contentDescription = "$unit earlier" }) { Text("▼") }
    }
}

/** The next 14 days, one tick per date; US school holidays are labelled. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SkipDaysSheet(r: Routine, today: Int, room: String, onToggle: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("Skip days", style = MaterialTheme.typography.titleLarge)
            Text("Ticked days are skipped. $room shows Home as usual.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                (0 until 14).map { today + it }.forEach { day ->
                    val runs = r.days[RoutineDates.weekday(day)]
                    val iso = RoutineDates.isoOf(day)
                    val holiday = RoutineHolidays.name(day)?.let { " · $it" } ?: ""
                    Row(Modifier.fillMaxWidth().clickable(enabled = runs) { onToggle(iso) }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(RoutineText.dayRowLabel(day, today) + holiday,
                                color = if (runs) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!runs) Text("Not a routine day", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (runs) Checkbox(iso in r.skips, { onToggle(iso) })
                    }
                }
            }
            Button(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(vertical = 16.dp)) { Text("Done") }
        }
    }
}

/** A mini Portal on the phone, plus "Try it on <room>" for 90 s (ticks aren't saved). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PreviewSheet(r: Routine, room: String, sending: Boolean, onTry: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("Preview", style = MaterialTheme.typography.titleLarge)
            Text("How $room will look at ${RoutineDates.time12(r.start)}.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            val bg = Color(0xFF14161B)
            Column(Modifier.padding(vertical = 16.dp).fillMaxWidth().aspectRatio(1.5f).clip(RoundedCornerShape(16.dp)).background(bg).padding(10.dp)) {
                Row { Text(r.name.ifBlank { "Routine" }, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(RoutineDates.clock12(r.start), color = Color.White) }
                Box(Modifier.padding(vertical = 6.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF66C49E)))
                val rows = if (r.cards.size <= 5) listOf(r.cards) else r.cards.chunked((r.cards.size + 1) / 2)
                rows.forEach { row ->
                    Row(Modifier.weight(1f).fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { c ->
                            Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(Color(0xFF2B2B2B)).padding(4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                RoutinePicture(c.pic, 32.dp)
                                Text(c.label, color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Close") }
                Button(onClick = onTry, enabled = !sending && r.cards.isNotEmpty()) { Text(if (sending) "Sending…" else "Try it on $room") }
            }
        }
    }
}

@Composable
internal fun ConfirmDialog(title: String, body: String, cancel: String, confirm: String, danger: Boolean,
                           onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(body) },
        dismissButton = { TextButton(onClick = onDismiss) { Text(cancel) } },
        confirmButton = { TextButton(onClick = onConfirm) {
            Text(confirm, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) } })
}

@Composable
internal fun CloseForTodayDialog(name: String, room: String, onDismiss: () -> Unit, onConfirm: () -> Unit) =
    ConfirmDialog("Close $name for today?", "$room goes back to Home. Cards stay as they are.", "Cancel", "Close for today",
        danger = false, onDismiss = onDismiss, onConfirm = onConfirm)

/** The routines snackbar with Undo (8 s with Undo, else 4.5 s). [onCardsUndo] restores editor cards. */
@Composable
internal fun RoutineSnackbar(controller: RoutinesController, modifier: Modifier, onCardsUndo: ((List<RoutineCard>) -> Unit)? = null) {
    val state by controller.state.collectAsState()
    val snack = state.snack ?: return
    LaunchedEffect(snack.id) { delay(if (snack.undo != null) 8_000 else 4_500); controller.dismissSnack() }
    Snackbar(modifier.padding(16.dp), action = snack.undo?.let { u -> {
        TextButton(onClick = {
            when (u) {
                is RoutineUndo.Cards -> { onCardsUndo?.invoke(u.cards); controller.dismissSnack() }
                is RoutineUndo.Restore -> controller.undo()
            }
        }) { Text("Undo") }
    } }) { Text((if (snack.ok) "✓ " else "⚠ ") + snack.text) }
}
