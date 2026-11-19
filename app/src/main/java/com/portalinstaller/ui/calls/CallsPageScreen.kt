/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.calls

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.portalinstaller.data.calls.CallContact
import com.portalinstaller.data.calls.CallContacts
import com.portalinstaller.data.calls.ContactPresence
import com.portalinstaller.data.calls.PhoneCalls
import com.portalinstaller.ui.controlcenter.EmptyMessage
import com.portalinstaller.ui.controlcenter.GroupCard
import com.portalinstaller.ui.controlcenter.PageTitle
import com.portalinstaller.ui.controlcenter.SectionTitle
import kotlinx.coroutines.delay

/**
 * Home › Calls (`phContacts`): this home's Portals with live presence, each with a video and an
 * audio call. Phones call rooms; only Portals drop in. Honest about reach: a Portal can ring this
 * phone only while Portal Toolkit is open.
 */
@Composable
fun CallsPageScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val contacts by PhoneCalls.contacts.collectAsStateWithLifecycle()
    val ready by PhoneCalls.ready.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(PRESENCE_REDRAW_MS) } }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && !has(context, Manifest.permission.POST_NOTIFICATIONS)) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val dial = rememberDial()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { TextButton(onClick = onBack) { Text("‹ Back") } }
        item { PageTitle("Calls", "Video calls with the Portals in your home, on the home Wi-Fi.") }
        when {
            !ready -> item { EmptyMessage("Set up your household first", "Calls are locked with your household key. Open Settings and pair your Portals so they share it.") }
            contacts.isEmpty() -> item { EmptyMessage("No Portals yet", "Pair a Portal to call it from this phone.") }
            else -> {
                item { SectionTitle("This home") }
                item { GroupCard { contacts.forEach { ContactRow(it, CallContacts.presence(it, now), dial) } } }
            }
        }
        item { Note("Phones call rooms. Only Portals can drop in.") }
        item { Note("Portals can ring this phone while Portal Toolkit is open. Ringing a phone in your pocket comes in a later update.") }
    }
}

@Composable
private fun ContactRow(contact: CallContact, presence: ContactPresence, dial: (CallContact, Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box { RoomAvatar(contact.name, AVATAR); PresenceDot(presence, Modifier.align(Alignment.BottomEnd)) }
        Column(Modifier.weight(1f)) {
            Text(contact.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(presence.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        CallButton("phone", "Audio call ${contact.name}", presence.callable, MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurface) { dial(contact, false) }
        CallButton("video", "Video call ${contact.name}", presence.callable, CallColors.Ok, Color.White) { dial(contact, true) }
    }
}

@Composable
private fun CallButton(icon: String, label: String, enabled: Boolean, fill: Color, tint: Color, onClick: () -> Unit) {
    Box(Modifier.size(BUTTON).alpha(if (enabled) 1f else DISABLED_ALPHA).background(fill, CircleShape)
        .let { if (enabled) it.callClick(label, onClick) else it }, contentAlignment = Alignment.Center) { CallIcon(icon, tint, 22.dp) }
}

@Composable
private fun PresenceDot(p: ContactPresence, modifier: Modifier) {
    val color = when (p) {
        ContactPresence.AVAILABLE -> CallColors.Ok
        ContactPresence.IN_CALL -> CallColors.Busy
        ContactPresence.QUIET -> CallColors.Quiet
        else -> CallColors.Off
    }
    Box(modifier.size(DOT).background(MaterialTheme.colorScheme.surface, CircleShape).padding(2.dp).background(color, CircleShape))
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 6.dp))
}

/** Dial after the mic (and camera for video) are allowed; opens the call screen. */
@Composable
private fun rememberDial(): (CallContact, Boolean) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Pair<CallContact, Boolean>?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val (contact, video) = pending ?: return@rememberLauncherForActivityResult
        pending = null
        val mic = granted[Manifest.permission.RECORD_AUDIO] ?: has(context, Manifest.permission.RECORD_AUDIO)
        val camera = granted[Manifest.permission.CAMERA] ?: has(context, Manifest.permission.CAMERA)
        if (mic && PhoneCalls.dial(contact, video && camera)) PhoneCallActivity.open(context)
    }
    return { contact, video ->
        val wanted = callPermissions(video).filterNot { has(context, it) }
        if (wanted.isEmpty()) { if (PhoneCalls.dial(contact, video)) PhoneCallActivity.open(context) }
        else { pending = contact to video; launcher.launch(wanted.toTypedArray()) }
    }
}

private val AVATAR = 48.dp
private val BUTTON = 44.dp
private val DOT = 14.dp
private const val DISABLED_ALPHA = 0.4f
private const val PRESENCE_REDRAW_MS = 2_000L
