/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.routines

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.portalinstaller.data.routines.RoutineDates
import com.portalinstaller.data.routines.RoutineDraft
import com.portalinstaller.data.routines.RoutineText
import com.portalinstaller.data.routines.RoutineValidation
import com.portalinstaller.viewmodel.RoutinesController
import kotlin.math.roundToInt

private enum class EditorSheet { NONE, PICK, START, END, SKIP, PREVIEW }

/**
 * The routine editor: name, who it's for, up to 8 cards (picker + label, drag to reorder), Starts
 * and Ready by, weekdays, skip days, preview, and Save that waits for the Portal's ack.
 */
@Composable
internal fun RoutineEditor(controller: RoutinesController, room: String, draft: RoutineDraft,
                           onChange: (RoutineDraft) -> Unit, onDone: () -> Unit) {
    val state by controller.state.collectAsState()
    val r = draft.routine
    val others = state.snapshot?.routines.orEmpty()
    val invalid = RoutineValidation.check(r, others)
    var sheet by remember { mutableStateOf(EditorSheet.NONE) }
    var pickIndex by remember { mutableStateOf<Int?>(null) } // null = new card
    var discardAsk by remember { mutableStateOf(false) }
    var deleteAsk by remember { mutableStateOf(false) }
    val back = { if (draft.dirty) discardAsk = true else { controller.clearFailure(); onDone() } }
    BackHandler(onBack = back)
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = back) {
                    Text("‹ " + if (draft.isNew) "New routine" else r.name.ifBlank { "Routine" },
                        style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.weight(1f))
                if (!draft.isNew) TextButton(onClick = { deleteAsk = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
            Label("Name")
            OutlinedTextField(r.name, { onChange(draft.withName(it)) }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("For example, School morning") },
                supportingText = { Text("${r.name.length} of ${RoutineValidation.NAME_MAX}") })
            Label("Who it’s for")
            KidChips(state.snapshot?.kids.orEmpty(), r.kids) { onChange(draft.toggleKid(it)) }
            Label("Cards", "${r.cards.size} of ${RoutineValidation.MAX_CARDS}")
            CardList(draft, onChange, onPick = { pickIndex = it; sheet = EditorSheet.PICK },
                onMoved = { label, to -> controller.showSnack("Moved $label to position ${to + 1}") })
            Label("Time")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TimeTile("Starts", r.start, Modifier.weight(1f)) { sheet = EditorSheet.START }
                TimeTile("Ready by", r.end, Modifier.weight(1f)) { sheet = EditorSheet.END }
            }
            Label("Days")
            DayChips(r.days) { onChange(draft.toggleDay(it)) }
            Text(RoutineText.skipHint(r.days), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(16.dp))
            Card(shape = RoundedCornerShape(20.dp)) {
                val today = state.snapshot?.day ?: 0
                val upcoming = r.skips.mapNotNull(RoutineDates::epochDayOf).filter { it >= today }.sorted()
                NavRow("Skip days", if (upcoming.isEmpty()) "Holidays, sick days. None skipped." else upcoming.joinToString(", ", transform = RoutineText::shortDate)) { sheet = EditorSheet.SKIP }
                HorizontalDivider()
                NavRow("Preview", "See it as the kids will") { sheet = EditorSheet.PREVIEW }
            }
            Spacer(Modifier.height(16.dp))
            state.failure?.let { f ->
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(f.title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                        Text(f.body, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
            if (invalid != null && state.failure == null) Text("ⓘ $invalid", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { controller.save(draft) { ok -> if (ok) onDone() } }, enabled = invalid == null && !state.sending,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(when { state.sending -> "Sending to $room…"; state.failure != null -> "Try again"; else -> "Save to $room" })
            }
            Spacer(Modifier.height(48.dp))
        }
        RoutineSnackbar(controller, Modifier.align(Alignment.BottomCenter)) { cards -> onChange(draft.withCards(cards)) }
    }
    when (sheet) {
        EditorSheet.PICK -> PicturePickerSheet(
            initial = pickIndex?.let { r.cards.getOrNull(it) }, fallbackPic = RoutineDraft.firstUnusedPicture(r),
            onDismiss = { sheet = EditorSheet.NONE },
            onRemove = pickIndex?.let { i -> {
                val before = r.cards
                onChange(draft.removeCard(i)); sheet = EditorSheet.NONE
                controller.showSnack("Removed ${before[i].label}", undo = com.portalinstaller.viewmodel.RoutineUndo.Cards(before))
            } },
            onDone = { pic, label ->
                onChange(pickIndex?.let { draft.updateCard(it, pic, label) } ?: draft.addCard(pic, label)); sheet = EditorSheet.NONE
            })
        EditorSheet.START, EditorSheet.END -> TimeSheet(if (sheet == EditorSheet.START) "Starts at" else "Ready by",
            if (sheet == EditorSheet.START) r.start else r.end, onDismiss = { sheet = EditorSheet.NONE }) { v ->
            onChange(if (sheet == EditorSheet.START) draft.withStart(v) else draft.withEnd(v)); sheet = EditorSheet.NONE
        }
        EditorSheet.SKIP -> SkipDaysSheet(r, state.snapshot?.day ?: 0, room, onToggle = { onChange(draft.toggleSkip(it)) }) { sheet = EditorSheet.NONE }
        EditorSheet.PREVIEW -> PreviewSheet(r, room, state.sending, onTry = { controller.preview(r); sheet = EditorSheet.NONE }) { sheet = EditorSheet.NONE }
        EditorSheet.NONE -> {}
    }
    if (discardAsk) ConfirmDialog("Discard changes?", "Your edits to this routine won’t be saved.", "Keep editing", "Discard",
        danger = false, onDismiss = { discardAsk = false }) { discardAsk = false; controller.clearFailure(); onDone() }
    if (deleteAsk) ConfirmDialog("Delete “${r.name}”?", "It disappears from $room too.", "Cancel", "Delete", danger = true,
        onDismiss = { deleteAsk = false }) { deleteAsk = false; controller.delete(r.id) { ok -> if (ok) onDone() } }
}

@Composable
private fun Label(text: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        trailing?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun KidChips(kids: List<com.portalinstaller.data.routines.RoutineKid>, selected: List<String>, onToggle: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected.isEmpty(), { onToggle(RoutineDraft.EVERYONE) }, { Text("Everyone") }, leadingIcon = { RoutineAvatar("everyone", 28.dp) })
        kids.forEach { k -> FilterChip(k.id in selected, { onToggle(k.id) }, { Text(k.name) }, leadingIcon = { RoutineAvatar(k.avatar, 28.dp) }) }
    }
}

@Composable
private fun CardList(draft: RoutineDraft, onChange: (RoutineDraft) -> Unit, onPick: (Int?) -> Unit, onMoved: (String, Int) -> Unit) {
    val cards = draft.routine.cards
    var dragging by remember { mutableStateOf<Int?>(null) }
    var dy by remember { mutableFloatStateOf(0f) }
    var rowPx by remember { mutableIntStateOf(1) }
    val target = dragging?.let { (it + (dy / rowPx).roundToInt()).coerceIn(0, cards.lastIndex) }
    Card(shape = RoundedCornerShape(24.dp)) {
        cards.forEachIndexed { i, c ->
            val offset = when {
                i == dragging -> dy
                dragging != null && target != null && dragging!! < target && i in (dragging!! + 1)..target -> -rowPx.toFloat()
                dragging != null && target != null && dragging!! > target && i in target until dragging!! -> rowPx.toFloat()
                else -> 0f
            }
            Row(Modifier.fillMaxWidth().onSizeChanged { rowPx = maxOf(1, it.height) }.graphicsLayer { translationY = offset }
                .background(if (i == dragging) MaterialTheme.colorScheme.surfaceVariant else androidx.compose.ui.graphics.Color.Transparent)
                .semantics { customActions = listOfNotNull(
                    if (i > 0) CustomAccessibilityAction("Move up") { onChange(draft.moveCard(i, i - 1)); true } else null,
                    if (i < cards.lastIndex) CustomAccessibilityAction("Move down") { onChange(draft.moveCard(i, i + 1)); true } else null) },
                verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).clickable { onPick(i) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center) { RoutinePicture(c.pic, 40.dp) }
                    Text(c.label.ifBlank { "No label" }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("⠿", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 20.dp)
                    .semantics { contentDescription = "Drag to reorder ${c.label}" }
                    .pointerInput(i, cards.size) {
                        detectDragGestures(
                            onDragStart = { dragging = i; dy = 0f },
                            onDrag = { change, amount -> change.consume(); dy += amount.y },
                            onDragCancel = { dragging = null; dy = 0f },
                            onDragEnd = {
                                val from = dragging; val to = target
                                dragging = null; dy = 0f
                                if (from != null && to != null && from != to) { onChange(draft.moveCard(from, to)); onMoved(cards[from].label, to) }
                            })
                    })
            }
            HorizontalDivider()
        }
        if (cards.size < RoutineValidation.MAX_CARDS) {
            Row(Modifier.fillMaxWidth().clickable { onPick(null) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(52.dp).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Text("+") }
                Text("Add card", style = MaterialTheme.typography.bodyLarge)
            }
        } else Text("8 cards is the most that stays readable across the room.", modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TimeTile(label: String, min: Int, modifier: Modifier, onClick: () -> Unit) {
    Card(modifier.clickable(onClick = onClick), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(RoutineDates.time12(min), style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@Composable
private fun DayChips(days: List<Boolean>, onToggle: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf("M", "T", "W", "T", "F", "S", "S").forEachIndexed { i, l ->
            val on = days[i]
            Box(Modifier.size(44.dp).clip(CircleShape)
                .background(if (on) MaterialTheme.colorScheme.onSurface else androidx.compose.ui.graphics.Color.Transparent)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                .semantics { contentDescription = RoutineDates.DAY_NAMES[i] + if (on) ", on" else ", off" }
                .clickable { onToggle(i) }, contentAlignment = Alignment.Center) {
                Text(l, color = if (on) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun NavRow(title: String, detail: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("›", style = MaterialTheme.typography.titleLarge)
    }
}
