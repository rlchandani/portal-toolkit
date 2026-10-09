/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Immutable
internal data class ChoiceOption(val value: String, val label: String, val enabled: Boolean = true)

/** A native Material menu with a non-editable anchor, avoiding text-input focus/layout work. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChoiceDropdown(
    value: String,
    options: List<ChoiceOption>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String = "Choose",
    accessibilityLabel: String? = null,
    matchAnchorWidth: Boolean = false,
    menuActions: (@Composable ColumnScope.(dismiss: () -> Unit) -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val usable = enabled && (options.isNotEmpty() || menuActions != null)
    LaunchedEffect(usable) { if (!usable) expanded = false }
    val label = options.firstOrNull { it.value == value }?.label
        ?: value.ifBlank { placeholder }
    var anchorWidth by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    Box {
        OutlinedButton(
            onClick = { expanded = !expanded },
            enabled = usable,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            modifier = modifier.widthIn(min = 120.dp, max = 176.dp)
                .heightIn(min = 56.dp)
                .onSizeChanged { anchorWidth = it.width }
                .semantics {
                    role = Role.DropdownList
                    stateDescription = if (expanded && usable) "Expanded" else "Collapsed"
                    if (accessibilityLabel != null) contentDescription = accessibilityLabel
                },
        ) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp))
            ExposedDropdownMenuDefaults.TrailingIcon(expanded && usable)
        }
        DropdownMenu(
            expanded = expanded && usable,
            onDismissRequest = { expanded = false },
            modifier = (if (matchAnchorWidth && anchorWidth > 0)
                Modifier.width(with(density) { anchorWidth.toDp() })
            else Modifier.widthIn(min = 196.dp, max = 360.dp))
                .heightIn(max = menuMaxHeight()),
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
        ) {
            options.forEach { option ->
                val current = option.value == value
                DropdownMenuItem(
                    text = { Text(option.label, style = MaterialTheme.typography.bodyLarge) },
                    enabled = enabled && option.enabled,
                    onClick = {
                        if (enabled && option.enabled) {
                            expanded = false
                            onSelect(option.value)
                        }
                    },
                    leadingIcon = {
                        if (current) ChoiceCheck() else Spacer(Modifier.size(20.dp))
                    },
                    modifier = Modifier.background(
                        if (current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    ).semantics { selected = current },
                )
            }
            menuActions?.invoke(this) { expanded = false }
        }
    }
}

/** Action menus use the same surface as choice menus without claiming selection state. */
@Composable
internal fun PremiumDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.widthIn(min = 196.dp, max = 360.dp)
            .heightIn(max = menuMaxHeight()),
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        content = content,
    )
}

/** Leave room for popup positioning/insets; long lists scroll instead of filling the window. */
@Composable
private fun menuMaxHeight() = minOf(
    360.dp, (LocalConfiguration.current.screenHeightDp.dp - 96.dp).coerceAtLeast(48.dp),
)

@Composable
private fun ChoiceCheck() {
    val tint = MaterialTheme.colorScheme.onSecondaryContainer
    Canvas(Modifier.size(20.dp)) {
        val stroke = 1.8.dp.toPx()
        drawLine(tint, Offset(size.width * .16f, size.height * .52f),
            Offset(size.width * .4f, size.height * .76f), stroke)
        drawLine(tint, Offset(size.width * .4f, size.height * .76f),
            Offset(size.width * .88f, size.height * .24f), stroke)
    }
}
