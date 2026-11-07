/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.dinner

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.portalinstaller.R
import com.portalinstaller.data.dinner.DinnerPresets
import com.portalinstaller.data.dinner.DinnerRepository
import com.portalinstaller.data.dinner.PhoneMessage
import com.portalinstaller.ui.controlcenter.EmptyMessage
import com.portalinstaller.ui.controlcenter.PageSections
import com.portalinstaller.ui.controlcenter.PageTitle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun dinnerIcon(name: String): Int = when (name) {
    "dinner" -> R.drawable.dinner_icon_plate
    "down" -> R.drawable.dinner_icon_stairs
    "leave" -> R.drawable.dinner_icon_door
    "bed" -> R.drawable.dinner_icon_moon
    "lights" -> R.drawable.dinner_icon_lamp
    "home10" -> R.drawable.dinner_icon_homeby
    "keyboard" -> R.drawable.dinner_icon_keyboard
    "reply" -> R.drawable.dinner_icon_reply
    "room" -> R.drawable.dinner_icon_room
    else -> R.drawable.dinner_icon_bell
}

private const val POLL_PENDING_MS = 800L
private const val POLL_REPLIES_MS = 3_000L
private const val POLL_WINDOW_MS = 120_000L
private const val BANNER_MS = 6_000L

/**
 * Toolkit's Call everyone screen: room chips (Everyone or one Portal), the six presets, Type a
 * message (40 characters), Ping, live per-room results with Try again, a Last result shortcut,
 * and a heads-up when someone replies. Integrator: place on the control-center Home tab, e.g.
 * `DinnerScreen(remember { DinnerRepository.create(context) })`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DinnerScreen(repository: DinnerRepository, modifier: Modifier = Modifier) {
    val state by repository.state.collectAsState()
    val scope = rememberCoroutineScope()
    var composing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(repository) { repository.refresh() }
    // Results and replies are read only while this screen shows a send; nothing polls in the background.
    LaunchedEffect(state.lastResult?.id) {
        if (state.lastResult == null) return@LaunchedEffect
        val until = System.currentTimeMillis() + POLL_WINDOW_MS
        while (System.currentTimeMillis() < until) {
            delay(if (repository.state.value.lastResult?.pending == true) POLL_PENDING_MS else POLL_REPLIES_MS)
            repository.poll()
        }
    }
    LaunchedEffect(state.replyBanner) { if (state.replyBanner != null) { delay(BANNER_MS); repository.dismissBanner() } }
    val blocked = state.notOnHomeWifi || state.sending || state.targets.isEmpty()
    fun send(message: PhoneMessage) { scope.launch { repository.send(message) } }

    Column(modifier.fillMaxWidth()) {
        state.replyBanner?.let { banner ->
            DinnerReplyBanner(banner.roomName, banner.reply) { repository.openLast(); repository.dismissBanner() }
        }
        PageSections {
            PageTitle("Call everyone", repository.subtitle())
            if (state.notOnHomeWifi) DinnerNotice("Not on home Wi-Fi",
                "Call everyone reaches the Portals over home Wi-Fi. Connect to your home network to send.")
            if (state.rooms.isEmpty() && !state.loading) {
                EmptyMessage("No Portals paired yet", "Pair a Portal from Settings, then call everyone from here.")
                return@PageSections
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = state.target == null, onClick = { repository.selectTarget(null) }, label = { Text("Everyone") })
                state.rooms.forEach { room ->
                    FilterChip(selected = state.target == room.id, onClick = { repository.selectTarget(room.id) },
                        label = { Text(if (room.online) room.name else "${room.name} (offline)") })
                }
            }
            DinnerPresets.all.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { preset ->
                        PresetCard(preset.text, dinnerIcon(preset.id), enabled = !blocked, Modifier.weight(1f)) {
                            send(PhoneMessage.Preset(preset.id))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = { composing = true }, enabled = !blocked, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                    Icon(painterResource(dinnerIcon("keyboard")), null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Type a message")
                }
                OutlinedButton(onClick = { send(PhoneMessage.Ping) }, enabled = !blocked, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                    Icon(painterResource(dinnerIcon("bell")), null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Ping")
                }
            }
            state.lastResult?.takeIf { state.result == null }?.let { last ->
                Row(Modifier.fillMaxWidth().clickable { repository.openLast() }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Last: “${last.text}”", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Results", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
    if (composing) DinnerComposeDialog(onCancel = { composing = false }) { message -> composing = false; send(message) }
    state.result?.let { result ->
        DinnerResultsSheet(result, onRetry = { room -> scope.launch { repository.retry(room) } }, onDone = repository::closeResult)
    }
}

@Composable
private fun PresetCard(text: String, icon: Int, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 104.dp), shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(painterResource(icon), contentDescription = null, tint = DinnerPalette.glyph(), modifier = Modifier.size(30.dp))
            Text(text, style = MaterialTheme.typography.titleMedium)
        }
    }
}
