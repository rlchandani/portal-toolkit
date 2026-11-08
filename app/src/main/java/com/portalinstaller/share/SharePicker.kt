/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.share

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal val ConfirmedGreen = Color(0xFF5CC8A0)
internal val UnconfirmedAmber = Color(0xFFE8C35A)
internal val FailedRed = Color(0xFFEF6F78)

/** Room picker: defaults to the last-used room, offers Both, and warns before sending somewhere unreachable. */
@Composable
internal fun SharePicker(hub: ShareHub, payload: SharePayload, preparing: Boolean, problem: String?,
    onCancel: () -> Unit, onSend: (RoomOption, SharePayload) -> Unit) {
    val context = LocalContext.current
    val kind = when (payload) { is SharePayload.Images -> ShareKind.PHOTOS; is SharePayload.Note -> ShareKind.NOTE; is SharePayload.Link -> ShareKind.LINK }
    var options by remember { mutableStateOf<List<RoomOption>?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    val reach = remember { mutableStateMapOf<String, Reach>() }
    var noteText by remember { mutableStateOf((payload as? SharePayload.Note)?.text.orEmpty()) }

    LaunchedEffect(Unit) {
        val loaded = ShareRooms.options(hub.rooms())
        options = loaded
        selected = ShareRooms.defaultKey(loaded, LastShareRoom.get(context))
        coroutineScope {
            loaded.flatMap { it.rooms }.distinctBy { it.id }
                .map { room -> async { reach[room.id] = hub.sender.reach(room.id, kind) } }.awaitAll()
        }
    }

    Text(when (payload) {
        is SharePayload.Images -> "Send to Portal"
        is SharePayload.Note -> "Leave a note on a Portal"
        is SharePayload.Link -> payload.title ?: "Open on a Portal"
    }, style = MaterialTheme.typography.headlineSmall)
    Text(when (payload) {
        is SharePayload.Images -> if (payload.uris.size == 1) "1 photo" else "${payload.uris.size} photos"
        is SharePayload.Note -> "From ${SenderName.get(context)}"
        is SharePayload.Link -> payload.url
    }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)

    if (payload is SharePayload.Note) {
        OutlinedTextField(noteText, { if (it.length <= ShareIntentParser.MAX_NOTE_CHARS) noteText = it },
            Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 96.dp), label = { Text("Note") })
    }

    val list = options
    Text("Send to", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
    when {
        list == null -> Text("Looking for your Portals…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        list.isEmpty() -> Text("Pair a Portal in Calder Pocket first. Nothing was sent.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> RoomList(list, selected, reach, LastShareRoom.get(context)) { selected = it }
    }

    val option = list?.firstOrNull { it.key == selected }
    val blocked = option?.rooms?.filter { reach[it.id] is Reach.Held || reach[it.id] is Reach.Refused }.orEmpty()
    if (option != null && blocked.isNotEmpty()) Banner(blocked, reach, option.rooms.size)
    problem?.let { Text(it, color = FailedRed, modifier = Modifier.padding(top = 12.dp)) }

    val edited = if (payload is SharePayload.Note) SharePayload.Note(noteText.trim()) else payload
    val canSend = option != null && !preparing && (edited !is SharePayload.Note || edited.text.isNotEmpty()) &&
        option.rooms.any { reach[it.id] !is Reach.Refused }
    SheetActions("Cancel", onCancel, if (preparing) "Preparing…" else option?.let { ShareRooms.sendLabel(kind, it.rooms) } ?: "Send",
        { option?.let { onSend(it, edited) } }, primaryEnabled = canSend)
}

@Composable
private fun RoomList(options: List<RoomOption>, selected: String?, reach: Map<String, Reach>, lastUsed: String?, onSelect: (String) -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Column {
            options.forEachIndexed { index, option ->
                if (index > 0) HorizontalDivider()
                val status = if (option.rooms.size == 1) reach[option.rooms.single().id] else null
                Row(Modifier.fillMaxWidth().selectable(option.key == selected, role = Role.RadioButton) { onSelect(option.key) }
                    .heightIn(min = 64.dp).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(option.key == selected, onClick = null)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(option.title, style = MaterialTheme.typography.titleMedium)
                        val sub = option.subtitle ?: status?.let(::statusLabel)
                        sub?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    if (option.key == lastUsed) Text("Last used", style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
                    if (option.rooms.size == 1) {
                        Spacer(Modifier.size(12.dp))
                        Box(Modifier.size(10.dp).background(if (status == Reach.Ready) ConfirmedGreen else MaterialTheme.colorScheme.outline, CircleShape))
                    }
                }
            }
        }
    }
}

private fun statusLabel(reach: Reach): String = when (reach) {
    Reach.Ready -> "Online"
    is Reach.Held -> when {
        reach.reason.contains("home Wi-Fi") -> "Away from home Wi-Fi"
        reach.reason.contains("offline", ignoreCase = true) && reach.reason.startsWith("Your phone") -> "Phone offline"
        else -> "Not responding"
    }
    is Reach.Refused -> if (reach.reason.startsWith("Sharing is turned off")) "Sharing is off" else "Can’t receive shares"
}

@Composable
private fun Banner(blocked: List<ShareRoom>, reach: Map<String, Reach>, total: Int) {
    val reasons = blocked.mapNotNull { (reach[it.id] as? Reach.Held)?.reason ?: (reach[it.id] as? Reach.Refused)?.reason }.distinct()
    val allHeld = blocked.all { reach[it.id] is Reach.Held }
    val tail = when {
        allHeld && blocked.size == total -> "Calder Pocket will wait and send when it can. Nothing is sent until then."
        allHeld -> "That room will get it when Calder Pocket can reach it."
        blocked.size == total -> "Nothing can be sent right now."
        else -> "Only the reachable room will get it."
    }
    Surface(Modifier.fillMaxWidth().padding(top = 12.dp), shape = RoundedCornerShape(16.dp), color = UnconfirmedAmber.copy(alpha = 0.16f)) {
        Text("${reasons.joinToString(" ")} $tail", Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

