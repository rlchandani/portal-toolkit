/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.guests

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.portalinstaller.data.guests.GuestCard
import com.portalinstaller.data.guests.GuestDraft
import com.portalinstaller.data.guests.GuestNetworks
import com.portalinstaller.data.guests.NearbyNetwork

internal const val WIFI_PERMISSION_REASON = "Calder needs location permission to list nearby Wi-Fi networks"

private enum class Access { ASK, DENIED, BLOCKED, LOCATION_OFF, READY }

/** Step 1: pick the network from the phone's scan; "My network isn't listed" is the only typing path. */
@Composable
internal fun GuestNetworkStep(draft: GuestDraft, onChange: (GuestDraft) -> Unit) {
    var manual by rememberSaveable { mutableStateOf(false) }
    if (manual) {
        GuestManualNetwork(draft, onChange, onBack = { manual = false })
        return
    }
    GuestNetworkPicker(draft.card.ssid, onPick = { onChange(draft.withNetwork(it.ssid, it.security, hidden = false)) })
    TextButton(onClick = { manual = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("My network isn't listed") }
}

@Composable
private fun GuestNetworkPicker(selected: String, onPick: (NearbyNetwork) -> Unit) {
    val context = LocalContext.current
    val scanner = remember(context) { GuestWifiScanner(context) }
    var access by remember { mutableStateOf(accessOf(scanner, asked = false, context)) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        asked = true
        access = accessOf(scanner, asked = true, context)
    }
    LaunchedEffect(Unit) { if (access == Access.ASK && !asked) launcher.launch(scanner.permissions) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { access = accessOf(scanner, asked, context) }
    when (access) {
        Access.READY -> GuestNetworkList(scanner, selected, onPick)
        Access.ASK, Access.DENIED -> GuestAccessNote(
            if (access == Access.DENIED) "Without it, use “My network isn't listed” below." else null,
            "Allow", onClick = { launcher.launch(scanner.permissions) })
        Access.BLOCKED -> GuestAccessNote("Location permission is off for Calder Pocket.", "Open app settings") {
            context.startSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        }
        Access.LOCATION_OFF -> GuestAccessNote("Location is off. Turn it on to see nearby networks.", "Turn on location", reason = false) {
            context.startSafely(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
    }
}

private fun accessOf(scanner: GuestWifiScanner, asked: Boolean, context: Context): Access = when {
    !scanner.hasPermission() -> when {
        !asked -> Access.ASK
        context.findActivity()?.let { a -> scanner.permissions.any { ActivityCompat.shouldShowRequestPermissionRationale(a, it) } } == true ->
            Access.DENIED
        else -> Access.BLOCKED
    }
    !scanner.locationOn() -> Access.LOCATION_OFF
    else -> Access.READY
}

@Composable
private fun GuestAccessNote(detail: String?, action: String, reason: Boolean = true, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (reason) Text(WIFI_PERMISSION_REASON, style = MaterialTheme.typography.bodyMedium)
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        OutlinedButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) { Text(action) }
    }
}

@Composable
private fun GuestNetworkList(scanner: GuestWifiScanner, selected: String, onPick: (NearbyNetwork) -> Unit) {
    var networks by remember { mutableStateOf<List<NearbyNetwork>?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var throttled by remember { mutableStateOf(false) }
    DisposableEffect(scanner) { val stop = scanner.observeScans { revision++ }; onDispose(stop) }
    LaunchedEffect(scanner) { throttled = !scanner.startScan() }
    LaunchedEffect(revision) { networks = scanner.networks() }
    val list = networks
    when {
        list == null -> Text("Looking for networks…", style = MaterialTheme.typography.bodyMedium)
        list.isEmpty() -> Text("No networks found yet.", style = MaterialTheme.typography.bodyMedium)
        else -> list.forEach { GuestNetworkRow(it, it.ssid == selected) { onPick(it) } }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (throttled) Text("Showing the last scan.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        else Row(Modifier.weight(1f)) {}
        TextButton(onClick = { throttled = !scanner.startScan(); revision++ }, Modifier.heightIn(min = 48.dp)) { Text("Refresh") }
    }
}

@Composable
private fun GuestNetworkRow(network: NearbyNetwork, selected: Boolean, onClick: () -> Unit) {
    val detail = listOfNotNull("Connected now".takeIf { network.connected }, GuestCard.securityLabel(network.security))
        .joinToString(" · ")
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .clickable(role = Role.RadioButton, onClick = onClick)
        .semantics { this.selected = selected; contentDescription = "${network.ssid}, $detail" }
        .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(network.ssid, style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            Text(detail, style = MaterialTheme.typography.bodySmall,
                color = if (network.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(signal(network), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (selected) Text("  ✓", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
}

private fun signal(network: NearbyNetwork): String =
    if (network.level <= -100) "" else "▂▄▆".take(GuestNetworks.bars(network.level)).padEnd(3, '·')

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GuestManualNetwork(draft: GuestDraft, onChange: (GuestDraft) -> Unit, onBack: () -> Unit) {
    val card = draft.card
    OutlinedTextField(card.ssid, { onChange(draft.withNetwork(it.replace("\n", ""), card.security, card.hiddenNetwork)) },
        Modifier.fillMaxWidth(), singleLine = true, label = { Text("Network name") },
        supportingText = { Text("Exactly as it appears when joining.") },
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done))
    Text("Security", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GuestCard.SECURITY_OPTIONS.forEach { (value, label) ->
            FilterChip(selected = card.security == value, label = { Text(label) },
                onClick = { onChange(draft.withNetwork(card.ssid, value, card.hiddenNetwork)) })
        }
    }
    GuestSwitchRow("Network is hidden", card.hiddenNetwork) { onChange(draft.withNetwork(card.ssid, card.security, it)) }
    TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("Pick from nearby networks") }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

internal fun Context.startSafely(intent: Intent) {
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
