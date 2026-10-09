/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

internal data class JoinedAction(
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val emphasized: Boolean = false,
)

/** One surface, equal sections, but each section remains an independent momentary button. */
@Composable
internal fun JoinedActionGroup(actions: List<JoinedAction>, modifier: Modifier = Modifier) {
    if (actions.isEmpty()) return
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEachIndexed { index, action ->
            if (index > 0) Box(Modifier.width(1.dp).fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant))
            val strong = action.emphasized && action.enabled
            Box(
                Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp)
                    .background(if (strong) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(enabled = action.enabled, role = Role.Button, onClick = action.onClick)
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                val color = if (strong) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                Text(action.label, style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    color = if (action.enabled) color else color.copy(alpha = .38f))
            }
        }
    }
}
