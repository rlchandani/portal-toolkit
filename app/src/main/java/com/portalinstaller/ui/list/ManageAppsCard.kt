/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Home's "Manage" section: Apps moved here when List took its tab (README Q2). */
@Composable
fun ManageAppsCard(onOpenApps: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Manage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        ListCard {
            Row(Modifier.fillMaxWidth().clickable(onClick = onOpenApps).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                ListGlyph("grid", MaterialTheme.colorScheme.primary, Modifier.size(24.dp))
                Spacer(Modifier.size(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Apps", fontWeight = FontWeight.Medium)
                    Text("Install and arrange apps on each Portal. Moved here from the tab bar.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ListGlyph("chevron", MaterialTheme.colorScheme.onSurfaceVariant, Modifier.size(18.dp))
            }
        }
    }
}
