/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.guests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.portalinstaller.data.guests.GuestCard
import com.portalinstaller.data.guests.GuestSnapshot
import com.portalinstaller.ui.controlcenter.GroupCard

/**
 * The editable card. The password field starts empty: leaving it blank keeps the password the
 * Portal already has (it never comes back to the phone), typing replaces it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GuestForm(
    shown: GuestSnapshot,
    target: String,
    busy: Boolean,
    many: Boolean,
    onSave: (GuestCard, String) -> Unit,
) {
    var draft by remember(shown.card) { mutableStateOf(shown.card) }
    var password by remember(shown.card) { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    val problem = draft.problem(password, shown.hasPassword)
    GroupCard {
        GuestField("Wi-Fi network name", draft.ssid) { draft = draft.copy(ssid = it) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GuestCard.SECURITY_OPTIONS.forEach { (value, label) ->
                FilterChip(selected = draft.security == value, onClick = { draft = draft.copy(security = value) },
                    label = { Text(label) })
            }
        }
        if (draft.security != GuestCard.SECURITY_NONE) {
            OutlinedTextField(password, { password = it.take(GuestCard.MAX_PASSWORD) }, Modifier.fillMaxWidth(),
                singleLine = true, label = { Text("Wi-Fi password") },
                placeholder = { Text(if (shown.hasPassword) "Saved on the Portal. Type to replace it" else "Type the password") },
                visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                trailingIcon = { TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "Hide" else "Show") } },
                supportingText = { Text(if (many) "Sent to each Portal. Calder Pocket doesn’t keep it." else "Calder Pocket doesn’t keep it.") })
        }
        GuestSwitch("Network is hidden", draft.hiddenNetwork) { draft = draft.copy(hiddenNetwork = it) }
        GuestSwitch("Hide password until tapped", draft.hidePassword) { draft = draft.copy(hidePassword = it) }
        GuestField("Welcome line", draft.welcome, "Make yourselves at home!") { draft = draft.copy(welcome = it) }
        GuestField("Door code", draft.doorCode) { draft = draft.copy(doorCode = it) }
        GuestField("Checkout time", draft.checkout, "11:00 on Sunday") { draft = draft.copy(checkout = it) }
        GuestField("Good to know", draft.houseNote, "Bins go out Tuesday") { draft = draft.copy(houseNote = it) }
        GuestField("Emergency contact", draft.emergency) { draft = draft.copy(emergency = it) }
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Button(onClick = { onSave(draft, password) }, enabled = problem == null && !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(if (busy) "Sending to $target…" else "Save to $target")
        }
    }
}

@Composable
private fun GuestField(label: String, value: String, example: String? = null, onChange: (String) -> Unit) {
    OutlinedTextField(value, { onChange(it.replace("\n", " ").take(GuestCard.MAX_TEXT)) }, Modifier.fillMaxWidth(),
        singleLine = true, label = { Text(label) }, placeholder = example?.let { { Text(it) } })
}

@Composable
private fun GuestSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange, Modifier.semantics { contentDescription = label })
    }
}
