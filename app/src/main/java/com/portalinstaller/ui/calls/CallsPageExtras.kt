/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.calls

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.portalinstaller.data.calls.CallStandby
import com.portalinstaller.data.calls.CallStandbyPolicy
import com.portalinstaller.data.calls.PhoneCalls
import com.portalinstaller.data.calls.PhoneMessage
import com.portalinstaller.data.calls.PhoneMessageCopy
import com.portalinstaller.data.calls.PhoneMessageList
import com.portalinstaller.data.calls.PhoneMessageStore
import com.portalinstaller.ui.controlcenter.GroupCard
import com.portalinstaller.ui.controlcenter.SectionTitle

/** Messages Portals left for this phone (mock `phnotifs` "Watch" / "Call back"). */
internal fun messagesSection(scope: LazyListScope, now: Long) {
    scope.item { SectionTitle("Messages") }
    scope.item { MessagesCard(now) }
}

@Composable
private fun MessagesCard(now: Long) {
    val context = LocalContext.current
    val messages by PhoneMessageStore.messages.collectAsStateWithLifecycle()
    GroupCard {
        if (messages.isEmpty()) Text("No messages. When a Portal leaves you one, it appears here and stays encrypted on this phone.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        messages.forEach { m -> MessageRow(m, now) { PhoneMessageActivity.watch(context, m.id).let(context::startActivity) } }
        Text(PhoneMessageCopy.STORED, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MessageRow(m: PhoneMessage, now: Long, onWatch: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        RoomAvatar(m.fromName, 44.dp)
        Column(Modifier.weight(1f)) {
            Text(m.fromName, style = MaterialTheme.typography.titleMedium, fontWeight = if (m.seen) FontWeight.Normal else FontWeight.Bold, maxLines = 1)
            Text("${PhoneMessageCopy.kind(m)} · ${PhoneMessageList.expiresLine(m, now)}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        TextButton(onClick = onWatch) { Text("Watch") }
    }
}

/** "Ring when Portal Toolkit is closed": off by default, with the trade-offs spelled out. */
@SuppressLint("BatteryLife")
@Composable
internal fun BackgroundRingingCard() {
    val context = LocalContext.current
    val on by CallStandby.on.collectAsStateWithLifecycle()
    val mode by PhoneCalls.standby.collectAsStateWithLifecycle()
    GroupCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Ring when Portal Toolkit is closed", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(when {
                    !on -> "Off · Portals ring this phone only while Portal Toolkit is open"
                    mode == CallStandbyPolicy.Mode.LISTENING -> "On · listening on the home Wi-Fi"
                    else -> "On · paused until this phone is on Wi-Fi"
                }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = on, onCheckedChange = { CallStandby.set(context, it) })
        }
        Text(CallStandbyPolicy.FOOTER, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (on && !CallStandby.ignoresBatteryOptimisations(context)) TextButton(onClick = {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
            }
        }) { Text("Let it ring when the phone is idle") }
    }
}
