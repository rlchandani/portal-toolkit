/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.portalinstaller.data.list.HouseholdListIds

/**
 * "Add an item" bar. While focused it shows "Used before" chips (or matching suggestions as you
 * type); Enter or Add adds and keeps the field open so several items go in a row.
 */
@Composable
internal fun ListAddBar(usedBefore: List<String>, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    val submit = { if (text.isNotBlank()) { onAdd(text); text = "" } else onAdd(text) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (focused) {
            val chips = suggestions(text, usedBefore)
            if (chips.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                chips.forEach { chip ->
                    AssistChip(onClick = { onAdd(chip); text = "" }, label = { Text(chip) },
                        leadingIcon = { ListGlyph("plus", MaterialTheme.colorScheme.onSurface) })
                }
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(30.dp))
            .padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ListGlyph("plus", MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(14.dp))
            BasicTextField(text, { text = it.take(HouseholdListIds.MAX_TEXT) }, singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused },
                decorationBox = { inner ->
                    if (text.isEmpty()) Text("Add an item", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    inner()
                })
            if (text.isNotBlank()) Button(onClick = { submit() }) { Text("Add") }
        }
    }
}

private fun suggestions(text: String, usedBefore: List<String>): List<String> {
    val typed = HouseholdListIds.norm(text)
    if (typed.isEmpty()) return usedBefore.take(5)
    return usedBefore.filter { HouseholdListIds.norm(it).startsWith(typed) && HouseholdListIds.norm(it) != typed }.take(3)
}
