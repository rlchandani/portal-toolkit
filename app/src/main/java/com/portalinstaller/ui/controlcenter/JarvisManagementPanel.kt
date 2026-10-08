/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.controlcenter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
internal fun JarvisManagementPanel(
    status: JSONObject,
    working: Boolean,
    onConfigure: (JSONObject) -> Unit,
    onLaunch: () -> Unit,
) {
    val installed = status.optBoolean("installed")
    val supported = status.optBoolean("supported")
    val wakeSupported = status.optBoolean("wakeSupported")
    val currentModel = (status.opt("model") as? String).orEmpty()
    val currentWake = status.optBoolean("wakeEnabled")
    // This panel reads only public capabilities. Credentials and arbitrary response fields
    // are never rendered, persisted as UI state, or forwarded in configuration requests.
    val modelArray = status.optJSONArray("availableModels")
    val models = remember(modelArray?.toString()) {
        if (modelArray == null) emptyList() else (0 until modelArray.length()).mapNotNull { index ->
            (modelArray.opt(index) as? String)?.takeIf { Regex("[A-Za-z0-9._/-]{1,128}").matches(it) }
        }.distinct().take(32)
    }
    var model by rememberSaveable(currentModel) { mutableStateOf(currentModel) }
    var wake by rememberSaveable(currentWake, wakeSupported) { mutableStateOf(currentWake) }
    var expanded by remember { mutableStateOf(false) }
    val modelChanged = model != currentModel && model in models
    val wakeChanged = wakeSupported && status.opt("wakeEnabled") is Boolean && wake != currentWake
    val enabled = !working && installed && supported

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GroupCard {
            Text("Jarvis", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            when {
                !installed -> Text("Jarvis is not installed on this Portal. Install it from Apps.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                !supported -> Text("This Jarvis version does not offer companion controls. Open it on the Portal for setup or update it from Apps.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    JarvisStatusRow("Assistant", jarvisActivity(status))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    JarvisStatusRow("Microphone", jarvisMicrophone(status))
                    JarvisStatusRow("Background wake", if (wakeSupported) "Supported" else "Unavailable on this Portal")
                    if (wakeSupported) JarvisStatusRow("Wake listener", when (status.opt("wakeListening")) {
                        true -> "Listening locally"
                        false -> if (currentWake) "Waiting to resume" else "Off"
                        else -> "Status unavailable"
                    })
                }
            }
            OutlinedButton(onClick = onLaunch, enabled = installed && !working,
                modifier = Modifier.fillMaxWidth()) { Text("Open Jarvis on Portal") }
        }
        if (installed && supported) {
            SectionTitle("Model")
            GroupCard {
                Text("Conversation model", fontWeight = FontWeight.Medium)
                Column {
                    OutlinedButton(onClick = { expanded = true }, enabled = enabled && models.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()) {
                        Text(model.ifBlank { "Model unavailable" })
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        models.forEach { available ->
                            DropdownMenuItem(text = { Text(available) }, enabled = enabled, onClick = {
                                model = available; expanded = false
                            })
                        }
                    }
                }
                Text(if (models.isEmpty()) "Jarvis has not reported an available model list."
                    else "Choices come from the Jarvis version installed on this Portal.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (wakeSupported) {
                SectionTitle("Wake policy")
                GroupCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Allow background wake", fontWeight = FontWeight.Medium)
                            Text("Opt in to local wake-word listening while Jarvis is in the background.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = wake, onCheckedChange = { wake = it },
                            enabled = enabled && status.opt("wakeEnabled") is Boolean)
                    }
                    Text("Save to apply. Microphone permissions and competing audio can pause the listener.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            GroupCard {
                if (modelChanged || wakeChanged) Text("Unsaved changes",
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Button(onClick = {
                    val changes = JSONObject()
                    if (modelChanged) changes.put("model", model)
                    if (wakeChanged) changes.put("wakeEnabled", wake)
                    if (changes.length() > 0) onConfigure(changes)
                }, enabled = enabled && (modelChanged || wakeChanged), modifier = Modifier.fillMaxWidth()) {
                    Text(if (working) "Working…" else "Save Jarvis settings")
                }
                if (modelChanged || wakeChanged) TextButton(onClick = {
                    model = currentModel; wake = currentWake
                }, enabled = !working) { Text("Discard changes") }
                Text("Account credentials and microphone permission are managed in Jarvis on the Portal.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun JarvisStatusRow(title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(detail, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun jarvisActivity(status: JSONObject): String = when {
    status.optBoolean("running") -> "Active"
    status.optBoolean("foreground") -> "Open on Portal"
    status.optBoolean("serviceRunning") -> "Service running"
    status.opt("serviceRunning") == false -> "Service stopped"
    else -> when (status.optString("status")) {
        "idle", "ready" -> "Ready"
        "listening" -> "Listening"
        "speaking" -> "Speaking"
        "running", "active" -> "Active"
        "stopped", "offline" -> "Stopped"
        else -> "Status unavailable"
    }
}

private fun jarvisMicrophone(status: JSONObject): String = when {
    status.opt("microphoneMuted") == true -> "Muted on Portal"
    status.opt("microphoneGranted") == false -> "Permission required on Portal"
    status.opt("microphoneGranted") == true -> "Permission granted"
    else -> when (status.optString("microphone")) {
        "granted", "ready", "available" -> "Available"
        "denied", "permission_required" -> "Permission required on Portal"
        "muted" -> "Muted on Portal"
        "busy", "in_use" -> "In use"
        "unavailable" -> "Unavailable"
        else -> "Status unavailable"
    }
}
