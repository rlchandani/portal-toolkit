/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.guests

import android.app.TimePickerDialog
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.text.format.DateFormat
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.InputChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.portalinstaller.data.guests.GuestBasics
import com.portalinstaller.data.guests.GuestCard
import com.portalinstaller.data.guests.GuestCheckout
import com.portalinstaller.data.guests.GuestDoorCode
import com.portalinstaller.data.guests.GuestDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Step 3: house basics as choices — check-out, good to know, door code and contact. */
@Composable
internal fun GuestBasicsStep(draft: GuestDraft, onChange: (GuestDraft) -> Unit) {
    fun card(update: (GuestCard) -> GuestCard) = onChange(draft.copy(card = update(draft.card)))
    GuestCheckoutChoice(draft.card.checkout) { value -> card { it.copy(checkout = value) } }
    GuestGoodToKnow(draft.card) { update -> card(update) }
    GuestDoorCodeChoice(draft, onChange)
    GuestContactChoice(draft.card) { update -> card(update) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GuestCheckoutChoice(value: String, onChange: (String) -> Unit) {
    val context = LocalContext.current
    val use24 = DateFormat.is24HourFormat(context)
    val custom = value.isNotEmpty() && value !in GuestCheckout.PRESETS
    GuestLabel("Check-out")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = value.isEmpty(), onClick = { onChange("") }, label = { Text("No check-out") })
        GuestCheckout.PRESETS.forEach { time ->
            FilterChip(selected = value == time, onClick = { onChange(time) },
                label = { Text(GuestCheckout.display(time, use24)) })
        }
        FilterChip(selected = custom, onClick = { pickTime(context, value, use24, onChange) },
            label = { Text(if (custom) GuestCheckout.display(value, use24) else "Custom…") })
    }
}

private fun pickTime(context: Context, value: String, use24: Boolean, onChange: (String) -> Unit) {
    val (hour, minute) = GuestCheckout.parts(value) ?: (11 to 0)
    TimePickerDialog(context, { _, h, m -> onChange(GuestCheckout.of(h, m)) }, hour, minute, use24).show()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GuestGoodToKnow(card: GuestCard, onChange: ((GuestCard) -> GuestCard) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    GuestLabel("Good to know")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GuestBasics.PRESETS.forEach { preset ->
            FilterChip(selected = preset.id in card.goodToKnow, label = { Text("${preset.glyph}  ${preset.label}") },
                onClick = { onChange { it.copy(goodToKnow = GuestBasics.toggle(it.goodToKnow, preset.id)) } })
        }
        card.customNotes.forEach { note ->
            InputChip(selected = true, label = { Text(note) }, trailingIcon = { Text("✕") },
                modifier = Modifier.semantics { contentDescription = "$note. Tap to remove" },
                onClick = { onChange { it.copy(customNotes = it.customNotes - note) } })
        }
        if (!adding && card.customNotes.size < GuestBasics.MAX_NOTES) {
            FilterChip(selected = false, onClick = { adding = true }, label = { Text("Add your own…") })
        }
    }
    if (adding) Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(text, { text = it.replace('\n', ' ').replace('|', '/').take(GuestBasics.MAX_NOTE) },
            Modifier.weight(1f), singleLine = true, label = { Text("Your note") },
            supportingText = { Text("${text.trim().length} / ${GuestBasics.MAX_NOTE}") })
        TextButton(onClick = {
            onChange { it.copy(customNotes = GuestBasics.addNote(it.customNotes, text)) }
            text = ""; adding = false
        }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Add") }
    }
}

@Composable
private fun GuestDoorCodeChoice(draft: GuestDraft, onChange: (GuestDraft) -> Unit) {
    GuestSwitchRow("Show a door code", draft.showDoorCode) { onChange(draft.copy(showDoorCode = it, doorCodeInput = "")) }
    if (!draft.showDoorCode) return
    OutlinedTextField(draft.doorCodeInput, { onChange(draft.copy(doorCodeInput = GuestDoorCode.clean(it))) },
        Modifier.fillMaxWidth(), singleLine = true, label = { Text("Door code") },
        placeholder = { Text(if (draft.hasDoorCode) "Saved on the Portal. Type to replace it" else "4 to 8 digits") },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
    GuestNote("Hidden on the card until a guest taps it. Stored encrypted like the Wi-Fi password.")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GuestContactChoice(card: GuestCard, onChange: ((GuestCard) -> GuestCard) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val name = withContext(Dispatchers.IO) { contactName(context, uri) }
            if (name.isNotEmpty()) onChange { it.copy(contactName = name) }
        }
    }
    GuestLabel("Contact")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GuestBasics.CONTACTS.forEach { option ->
            FilterChip(selected = card.contact == option.id, label = { Text(option.label) }, onClick = {
                onChange { it.copy(contact = if (it.contact == option.id) GuestBasics.CONTACT_NONE else option.id) }
            })
        }
    }
    if (card.contactName.isEmpty()) {
        TextButton(onClick = { runCatching { picker.launch(null) } }, Modifier.heightIn(min = 48.dp)) { Text("Pick a contact to ask") }
    } else Row(verticalAlignment = Alignment.CenterVertically) {
        Text(GuestBasics.nameLine(card.contactName).orEmpty(), Modifier.weight(1f))
        TextButton(onClick = { onChange { it.copy(contactName = "") } }, Modifier.heightIn(min = 48.dp)) { Text("Remove") }
    }
}

/** The picked contact's display name only, read through the one-off URI grant (no READ_CONTACTS). */
private fun contactName(context: Context, uri: Uri): String = try {
    context.contentResolver.query(uri, arrayOf(ContactsContract.Contacts.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) GuestBasics.cleanContactName(c.getString(0).orEmpty()) else ""
    }.orEmpty()
} catch (e: Exception) {
    Log.w("ToolkitGuests", "contact name unavailable: ${e.javaClass.simpleName}")
    ""
}
