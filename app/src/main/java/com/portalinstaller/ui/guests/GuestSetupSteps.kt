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
import com.portalinstaller.data.guests.GuestDraft
import com.portalinstaller.data.guests.GuestWelcome

/** Step 2: the password, masked with show/hide. Blank keeps the one the Portal already has. */
@Composable
internal fun GuestPasswordStep(draft: GuestDraft, many: Boolean, onChange: (GuestDraft) -> Unit) {
    var reveal by remember { mutableStateOf(false) }
    Text("Password for ${draft.card.ssid}", style = MaterialTheme.typography.bodyLarge)
    OutlinedTextField(draft.password, { onChange(draft.copy(password = it.take(GuestCard.MAX_PASSWORD))) },
        Modifier.fillMaxWidth(), singleLine = true, label = { Text("Wi-Fi password") },
        placeholder = { Text(if (draft.hasPassword) "Saved on the Portal. Type to replace it" else "Type the password") },
        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = { TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "Hide" else "Show") } })
    GuestSwitchRow("Hide password on the card until tapped", draft.card.hidePassword) {
        onChange(draft.copy(card = draft.card.copy(hidePassword = it)))
    }
    GuestNote("Calder stores it encrypted and never shares it." +
        if (many) " It’s sent to each Portal; Portal Toolkit doesn’t keep it." else " Portal Toolkit doesn’t keep it.")
}

/** Step 4: the only free text, prefilled, with samples to tap. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GuestWelcomeStep(draft: GuestDraft, onChange: (GuestDraft) -> Unit) {
    val welcome = draft.card.welcome
    fun set(text: String) = onChange(draft.copy(card = draft.card.copy(welcome = GuestWelcome.clean(text))))
    OutlinedTextField(welcome, ::set, Modifier.fillMaxWidth(), label = { Text("Welcome message") },
        supportingText = { Text("${welcome.length} / ${GuestWelcome.MAX}") }, maxLines = 3)
    Text("Or tap a sample", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        GuestWelcome.SAMPLES.forEach { sample ->
            FilterChip(selected = welcome == sample, onClick = { set(sample) }, label = { Text(sample) })
        }
    }
}

@Composable
internal fun GuestSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked, onChange, Modifier.semantics { contentDescription = label })
    }
}

@Composable
internal fun GuestNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun GuestLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge)
}
