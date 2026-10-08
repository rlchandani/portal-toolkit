/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.portalinstaller.data.store.ThemeMode

/** Keep setting controls at the trailing edge without letting them consume the label. */
@Composable
internal fun InlineSettingRow(
    title: String,
    help: String = "",
    modifier: Modifier = Modifier,
    control: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val controlMaxWidth = maxWidth * .6f
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, fontWeight = FontWeight.Medium)
                if (help.isNotBlank()) Text(help, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.widthIn(max = controlMaxWidth), contentAlignment = Alignment.CenterEnd) {
                control()
            }
        }
    }
}

@Composable
internal fun SettingValueButton(
    value: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Text(value, modifier = Modifier.weight(1f, fill = false), maxLines = 2,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
        Spacer(Modifier.width(6.dp))
        Text("⌄")
    }
}

@Composable
internal fun SettingStepper(
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    decreaseEnabled: Boolean,
    increaseEnabled: Boolean,
    modifier: Modifier = Modifier,
    title: String? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onDecrease, enabled = decreaseEnabled,
            modifier = Modifier.size(48.dp).semantics {
                if (title != null) contentDescription = "Decrease ${title.lowercase()}"
            }) { Text("−") }
        Text(value, Modifier.weight(1f), textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = onIncrease, enabled = increaseEnabled,
            modifier = Modifier.size(48.dp).semantics {
                if (title != null) contentDescription = "Increase ${title.lowercase()}"
            }) { Text("+") }
    }
}

@Composable
internal fun ThemeModeSelector(
    theme: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
    optionTagPrefix: String = "phone-theme",
    label: @Composable (ThemeMode) -> String = {
        it.name.lowercase().replaceFirstChar(Char::titlecase)
    },
) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThemeMode.entries.forEachIndexed { index, mode ->
            if (index > 0) Box(Modifier.width(1.dp).fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant))
            val selected = theme == mode
            Box(
                Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp)
                    .background(if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant)
                    .selectable(selected, role = Role.RadioButton, onClick = { onSelect(mode) })
                    .testTag("$optionTagPrefix-${mode.name}")
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label(mode), style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
