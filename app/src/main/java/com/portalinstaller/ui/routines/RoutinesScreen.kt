/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.routines

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.portalinstaller.data.routines.Routine
import com.portalinstaller.data.routines.RoutineDates
import com.portalinstaller.data.routines.RoutineDraft
import com.portalinstaller.data.routines.RoutineSnapshot
import com.portalinstaller.data.routines.RoutineTemplates
import com.portalinstaller.data.routines.RoutineText
import com.portalinstaller.viewmodel.RoutinesController
import kotlinx.coroutines.delay

/** Live refresh while a routines screen is visible: every 3 s during a run, else every 30 s. */
@Composable
internal fun RoutinesLiveRefresh(controller: RoutinesController) {
    val state by controller.state.collectAsState()
    val running = state.snapshot?.today?.any { it.status == "running" } == true
    LaunchedEffect(state.deviceKey, state.connected, running) {
        while (state.connected) { controller.refresh(); delay(if (running) 3_000 else 30_000) }
    }
}

/**
 * Home › Routines: the list (picture strip, days and times, on/off, today's status), the empty
 * state with templates, and New routine. Opens [RoutineEditor] in place. [room] is the Portal name.
 */
@Composable
fun RoutinesScreen(controller: RoutinesController, room: String, onBack: () -> Unit) {
    val state by controller.state.collectAsState()
    RoutinesLiveRefresh(controller)
    var editing by remember { mutableStateOf<RoutineDraft?>(null) }
    var closeAsk by rememberSaveable { mutableStateOf<String?>(null) }
    val snapshot = state.snapshot
    editing?.let { draft ->
        RoutineEditor(controller, room, draft, onChange = { editing = it }, onDone = { editing = null })
        return
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ Routines", style = MaterialTheme.typography.titleLarge) }
            }
            OfflineBanner(state.connected, state.stale, room)
            when {
                snapshot == null && state.unsupported -> Message("Update Immortal on $room", "This Portal doesn’t have picture routines yet.")
                snapshot == null -> Message("Loading routines", "Routines on $room will appear here.")
                snapshot.routines.isEmpty() -> EmptyState(room) { editing = RoutineDraft.new(RoutineTemplates.create(it)) }
                else -> {
                    Text("Picture cards on $room, ticked off by tapping.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
                    snapshot.routines.sortedBy { it.start }.forEach { r ->
                        RoutineListCard(r, snapshot, room, state.pendingToggle == r.id,
                            onEdit = { editing = RoutineDraft.edit(r) }, onToggle = { controller.toggle(r.id) },
                            onClose = { closeAsk = r.id })
                        Spacer(Modifier.height(12.dp))
                    }
                    KidsSection(controller, snapshot)
                    Spacer(Modifier.height(96.dp))
                }
            }
        }
        if (snapshot != null && snapshot.routines.isNotEmpty()) {
            ExtendedFloatingActionButton(onClick = { editing = RoutineDraft.new(RoutineTemplates.create("blank")) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) { Text("+  New routine") }
        }
        RoutineSnackbar(controller, Modifier.align(Alignment.BottomCenter))
    }
    closeAsk?.let { rid ->
        val name = snapshot?.routines?.firstOrNull { it.id == rid }?.name ?: "it"
        CloseForTodayDialog(name, room, onDismiss = { closeAsk = null }) { closeAsk = null; controller.close(rid) }
    }
}

@Composable
internal fun OfflineBanner(connected: Boolean, stale: Boolean, room: String, seenAt: String? = null) {
    val text = when {
        !connected -> "Your phone can’t reach $room. Connect to home Wi-Fi to reach it."
        stale -> "$room is offline. Showing what it last sent${seenAt?.let { ", at $it" } ?: ""}."
        else -> return
    }
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(text, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun Message(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyState(room: String, onNew: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { listOf("shirt", "bowl", "backpack").forEach { RoutinePicture(it, 72.dp) } }
        Text("No routines on $room", style = MaterialTheme.typography.titleLarge)
        Text("Make a morning or bedtime routine. At its start time, $room shows big picture cards your kids tick off.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { onNew("blank") }) { Text("+  New routine") }
        Text("Or start from", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = { onNew("morning") }) { Text("School morning") }
            FilledTonalButton(onClick = { onNew("bedtime") }) { Text("Bedtime") }
        }
    }
}

@Composable
internal fun PictureStrip(r: Routine, size: androidx.compose.ui.unit.Dp = 36.dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { r.cards.take(8).forEach { RoutinePicture(it.pic, size) } }
}

@Composable
private fun RoutineListCard(r: Routine, s: RoutineSnapshot, room: String, pending: Boolean,
                            onEdit: () -> Unit, onToggle: () -> Unit, onClose: () -> Unit) {
    val st = RoutineText.status(s, r, room)
    val kids = r.kids.mapNotNull { id -> s.kids.firstOrNull { it.id == id }?.name }
    Card(Modifier.fillMaxWidth().clickable(onClick = onEdit), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PictureStrip(r)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${RoutineText.daysText(r.days)}, ${RoutineDates.time12(r.start)} to ${RoutineDates.time12(r.end)}" +
                        (if (kids.isEmpty()) "" else " · ${kids.joinToString(" and ")}"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = r.enabled, onCheckedChange = { onToggle() }, enabled = !pending,
                    modifier = Modifier.semantics { contentDescription = "${r.name} on or off" })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (st.kind == RoutineText.Kind.RUNNING) Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                Spacer(Modifier.width(if (st.kind == RoutineText.Kind.RUNNING) 8.dp else 0.dp))
                Text(if (st.kind == RoutineText.Kind.RUNNING) st.text.replace("On $room now", "On now") else st.text,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                    color = if (st.kind == RoutineText.Kind.DONE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                if (st.kind == RoutineText.Kind.RUNNING) FilledTonalButton(onClick = onClose) { Text("Close for today") }
            }
        }
    }
}

/**
 * Toolkit Home cards: "On <room> now" with Close for today, and Today's results. Wire into the
 * control-center Home above the tool rows.
 */
@Composable
fun RoutinesHomeCards(controller: RoutinesController, room: String, onOpen: () -> Unit) {
    val state by controller.state.collectAsState()
    RoutinesLiveRefresh(controller)
    val s = state.snapshot ?: return
    var closeAsk by remember { mutableStateOf<Routine?>(null) }
    val statuses = s.routines.map { it to RoutineText.status(s, it, room) }
    statuses.firstOrNull { it.second.kind == RoutineText.Kind.RUNNING }?.let { (r, st) ->
        Card(Modifier.fillMaxWidth().padding(vertical = 8.dp), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("● On $room now", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                PictureStrip(r)
                val left = st.run?.leftMin ?: 0
                Text(r.name, style = MaterialTheme.typography.titleMedium)
                Text("${st.run?.done} of ${st.run?.total} done, ${if (left > 0) "$left min left" else "past Ready by"}",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalButton(onClick = { closeAsk = r }) { Text("Close for today") }
            }
        }
    }
    val today = statuses.filter { it.second.kind in setOf(RoutineText.Kind.DONE, RoutineText.Kind.CLOSED, RoutineText.Kind.SKIP) }
    if (today.isNotEmpty()) {
        Text("Today", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
            today.forEach { (r, st) ->
                Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RoutinePicture(r.cards.firstOrNull()?.pic ?: "shirt", 36.dp)
                    Column(Modifier.weight(1f)) {
                        Text(r.name, fontWeight = FontWeight.Medium)
                        Text(st.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    closeAsk?.let { r -> CloseForTodayDialog(r.name, room, onDismiss = { closeAsk = null }) { closeAsk = null; controller.close(r.id) } }
}
