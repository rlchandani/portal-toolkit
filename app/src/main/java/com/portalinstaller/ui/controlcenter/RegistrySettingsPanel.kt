/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.controlcenter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.portalinstaller.ui.common.InlineSettingRow
import com.portalinstaller.ui.common.ChoiceDropdown
import com.portalinstaller.ui.common.ChoiceOption
import com.portalinstaller.ui.common.SettingStepper
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import com.portalinstaller.data.companion.CompanionGroupSettings

private val urlDraftSaver = Saver<String, String>(
    save = { nonSecretUrlDraft(it) },
    restore = { nonSecretUrlDraft(it) },
)

@Composable
internal fun RegistrySettingsPanel(
    schema: JSONObject,
    working: Boolean,
    query: String,
    advanced: Boolean,
    onlyDomain: String? = null,
    onApply: (String, JSONObject) -> Unit,
    activePortalName: String? = null,
    activeWorking: Boolean = working,
    onActiveApply: (String, JSONObject) -> Unit = onApply,
) {
    PageSections {
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
            SectionTitle(domain.optString("title", id), Modifier.testTag("settings-domain-$id-title"))
            GroupCard(Modifier.testTag("settings-domain-$id-card")) {
                controls.forEachIndexed { index, control ->
                    key(id, control.optString("key")) {
                        val activeOnly = CompanionGroupSettings.activeOnly(id, control.optString("key"), control)
                        if (activeOnly && activePortalName != null) Text("Active Portal only: $activePortalName", style = MaterialTheme.typography.labelMedium)
                        RegistryControl(control, if (activeOnly) activeWorking else working, advanced) { value ->
                            (if (activeOnly) onActiveApply else onApply)(id, JSONObject().put(control.getString("key"), value))
                        }
                    }
                    if (index != controls.lastIndex) HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                }
            }
        }
        if (count == 0) EmptyMessage(
            if (query.isBlank()) "No settings available" else "No matching settings",
            if (query.isBlank()) "Connect to a Hearthpane Portal to load its controls."
            else "Try another term or include Advanced settings.")
    }
}

@Composable
private fun RegistryControl(
    control: JSONObject,
    working: Boolean,
    advanced: Boolean,
    onApply: (Any) -> Unit,
) {
    val key = control.optString("key")
    val title = if (key == "advancedSettings") "Advanced settings on Portal"
        else control.optString("title", key)
    val help = control.optString("shortHelp", control.optString("help"))
    val value = control.opt("value")
    val enabled = !working && !control.optBoolean("readOnly")
    val rowModifier = Modifier.testTag("registry-$key-row")
    when (control.optString("type")) {
        "bool" -> InlineSettingRow(title, help, rowModifier) {
            Switch(checked = value == true, onCheckedChange = { onApply(it) }, enabled = enabled)
        }
        "enum" -> {
            val options = control.optJSONArray("options").objects()
            InlineSettingRow(title, help, rowModifier) {
                ChoiceDropdown(value = value?.toString().orEmpty(),
                    options = options.filter { advanced || it.optString("tier") != "advanced" ||
                        it.optString("value") == value?.toString() }.map { option ->
                        ChoiceOption(option.getString("value"),
                            option.optString("label", option.optString("value")))
                    },
                    onSelect = onApply,
                    enabled = enabled,
                    accessibilityLabel = title,
                    modifier = Modifier.testTag("registry-$key-value"))
            }
        }
        "int" -> {
            val current = (value as? Number)?.toInt() ?: control.optInt("min")
            val min = control.optInt("min", Int.MIN_VALUE)
            val max = control.optInt("max", Int.MAX_VALUE)
            val step = control.optInt("step", 1).coerceAtLeast(1)
            InlineSettingRow(title, help, rowModifier) {
                if (control.optBoolean("asText")) {
                    var draft by rememberSaveable(key, current) { mutableStateOf(current.toString()) }
                    Column(horizontalAlignment = Alignment.End) {
                        OutlinedTextField(value = draft, onValueChange = { draft = it },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            enabled = enabled)
                        TextButton(onClick = { draft.toIntOrNull()?.let(onApply) },
                            enabled = enabled && draft.toIntOrNull()?.let { it in min..max } == true) {
                            Text("Save")
                        }
                    }
                } else {
                    SettingStepper(control.optString("display", current.toString()),
                        onDecrease = {
                            onApply(if (current.toLong() - step < min && control.optBoolean("wrap"))
                                max else (current.toLong() - step).coerceIn(min.toLong(), max.toLong()).toInt())
                        },
                        onIncrease = {
                            onApply(if (current.toLong() + step > max && control.optBoolean("wrap"))
                                min else (current.toLong() + step).coerceIn(min.toLong(), max.toLong()).toInt())
                        },
                        decreaseEnabled = enabled && (current > min || control.optBoolean("wrap")),
                        increaseEnabled = enabled && (current < max || control.optBoolean("wrap")),
                        modifier = Modifier.testTag("registry-$key-value"))
                }
            }
        }
        "string" -> {
            val secret = control.optBoolean("secret")
            val draftState = if (secret) remember(key, value?.toString()) {
                mutableStateOf("")
            } else if (control.optString("entry") == "url") rememberSaveable(
                key, value?.toString(), stateSaver = urlDraftSaver,
            ) {
                mutableStateOf(value?.toString().orEmpty())
            } else rememberSaveable(key, value?.toString()) {
                mutableStateOf(value?.toString().orEmpty())
            }
            var draft by draftState
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, fontWeight = FontWeight.Medium)
                if (help.isNotBlank()) Text(help, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onApply(draft) },
                        enabled = enabled && (!secret || draft.isNotBlank())) { Text("Save changes") }
                }
            }
        }
        else -> InlineSettingRow(title, help, rowModifier) {
            Text(value?.takeUnless { it == JSONObject.NULL }?.toString().orEmpty(),
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
        }
    }
}
