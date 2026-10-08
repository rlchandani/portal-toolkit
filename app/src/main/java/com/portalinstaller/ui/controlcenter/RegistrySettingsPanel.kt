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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
internal fun RegistrySettingsPanel(
    schema: JSONObject,
    working: Boolean,
    query: String,
    advanced: Boolean,
    onlyDomain: String? = null,
    onApply: (String, JSONObject) -> Unit,
) {
    val domains = schema.optJSONArray("domains").objects()
    var count = 0
    for (domain in domains) {
        val id = domain.optString("id")
        if (onlyDomain != null && onlyDomain != id) continue
        val controls = domain.optJSONArray("controls").objects().filter { control ->
            val matches = query.isBlank() || listOf(domain.optString("title"),
                control.optString("title"), control.optString("help"),
                control.optString("key")).any { it.contains(query, ignoreCase = true) }
            matches && (advanced || control.optString("tier") != "advanced")
        }
        if (controls.isEmpty()) continue
        count += controls.size
        SectionTitle(domain.optString("title", id))
        GroupCard {
            controls.forEachIndexed { index, control ->
                key(id, control.optString("key")) {
                    RegistryControl(control, working, advanced) { value ->
                        onApply(id, JSONObject().put(control.getString("key"), value))
                    }
                }
                if (index != controls.lastIndex) HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
            }
        }
    }
    if (count == 0) EmptyMessage(
        if (query.isBlank()) "No settings available" else "No matching settings",
        if (query.isBlank()) "Connect to an Immortal Portal to load its controls."
        else "Try another term or include Advanced settings.")
}

@Composable
private fun RegistryControl(
    control: JSONObject,
    working: Boolean,
    advanced: Boolean,
    onApply: (Any) -> Unit,
) {
    val title = if (control.optString("key") == "advancedSettings") "Advanced settings on Portal"
        else control.optString("title", control.optString("key"))
    val help = control.optString("shortHelp", control.optString("help"))
    val type = control.optString("type")
    val value = control.opt("value")
    val enabled = !working && !control.optBoolean("readOnly")
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (type == "bool") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.Medium)
                    if (help.isNotBlank()) Text(help, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = value == true, onCheckedChange = { onApply(it) }, enabled = enabled)
            }
        } else {
            Text(title, fontWeight = FontWeight.Medium)
            if (help.isNotBlank()) Text(help, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (type) {
                "enum" -> {
                    var expanded by remember { mutableStateOf(false) }
                    val options = control.optJSONArray("options").objects()
                    val current = options.firstOrNull { it.optString("value") == value?.toString() }
                    Column {
                        TextButton(onClick = { expanded = true }, enabled = enabled) {
                            Text(current?.optString("label") ?: value?.toString().orEmpty())
                            Spacer(Modifier.width(8.dp)); Text("⌄")
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            options.filter { advanced || it.optString("tier") != "advanced" ||
                                it.optString("value") == value?.toString() }.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.optString("label", option.optString("value"))) },
                                    onClick = { expanded = false; onApply(option.getString("value")) })
                            }
                        }
                    }
                }
                "int" -> {
                    val current = (value as? Number)?.toInt() ?: control.optInt("min")
                    val min = control.optInt("min", Int.MIN_VALUE)
                    val max = control.optInt("max", Int.MAX_VALUE)
                    val step = control.optInt("step", 1).coerceAtLeast(1)
                    if (control.optBoolean("asText")) {
                        var draft by remember(control.optString("key"), current) {
                            mutableStateOf(current.toString())
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(value = draft, onValueChange = { draft = it },
                                modifier = Modifier.weight(1f), singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                enabled = enabled)
                            TextButton(onClick = { draft.toIntOrNull()?.let(onApply) },
                                enabled = enabled && draft.toIntOrNull()?.let { it in min..max } == true) {
                                Text("Save")
                            }
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = {
                                onApply(if (current.toLong() - step < min && control.optBoolean("wrap"))
                                    max else (current.toLong() - step).coerceIn(min.toLong(), max.toLong()).toInt())
                            }, enabled = enabled && (current > min || control.optBoolean("wrap"))) { Text("−") }
                            Text(control.optString("display", current.toString()),
                                modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            TextButton(onClick = {
                                onApply(if (current.toLong() + step > max && control.optBoolean("wrap"))
                                    min else (current.toLong() + step).coerceIn(min.toLong(), max.toLong()).toInt())
                            }, enabled = enabled && (current < max || control.optBoolean("wrap"))) { Text("+") }
                        }
                    }
                }
                "string" -> {
                    val secret = control.optBoolean("secret")
                    var draft by remember(control.optString("key"), value?.toString()) {
                        mutableStateOf(if (secret) "" else value?.toString().orEmpty())
                    }
                    OutlinedTextField(
                        value = draft, onValueChange = { draft = it }, modifier = Modifier.fillMaxWidth(),
                        enabled = enabled, singleLine = true,
                        placeholder = { if (secret && control.optBoolean("hasValue")) Text("Configured") },
                        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                        keyboardOptions = KeyboardOptions(keyboardType = when (control.optString("entry")) {
                            "url" -> KeyboardType.Uri
                            "password" -> KeyboardType.Password
                            else -> KeyboardType.Text
                        }))
                    TextButton(onClick = { onApply(draft) },
                        enabled = enabled && (!secret || draft.isNotBlank())) { Text("Save changes") }
                }
                else -> Text(value?.takeUnless { it == JSONObject.NULL }?.toString().orEmpty(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
