/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.portalinstaller.ui.controlcenter

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import com.portalinstaller.data.store.ThemeMode
import com.portalinstaller.ui.common.ThemeModeSelector
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.MaterialTheme

@Composable
internal fun SettingsPageContent(
    query: String,
    onQuery: (String) -> Unit,
    advanced: Boolean,
    onAdvanced: (Boolean) -> Unit,
    domainPicker: @Composable () -> Unit,
    portalSettings: @Composable () -> Unit,
    phonePreferences: @Composable () -> Unit,
    onTools: () -> Unit,
    onPair: () -> Unit,
) {
    PageSections {
        PageTitle("Settings", "Simple everyday controls, with more when you need them.")
        OutlinedTextField(query, onValueChange = onQuery, label = { Text("Search settings") },
            singleLine = true, modifier = Modifier.fillMaxWidth().testTag("settings-search"))
        GroupCard(Modifier.testTag("settings-advanced")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Advanced settings", fontWeight = FontWeight.Medium)
                    Text("Show expert controls on this phone.", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.width(12.dp))
                Switch(advanced, onCheckedChange = onAdvanced)
            }
        }
        if (query.isBlank()) domainPicker()
        portalSettings()
        SectionTitle("This phone")
        phonePreferences()
        GroupCard(Modifier.testTag("settings-device-tools")) {
            ActionRow("Device tools", "ADB setup, screen mirror, APKs, files, backups, and diagnostics.", "settings", onClick = onTools)
            ActionRow("Add a Portal", "Local Wi-Fi or your existing private VPN endpoint.", "remote", onClick = onPair)
        }
    }
}

@Composable
internal fun PhoneAppearanceSection(theme: ThemeMode, onTheme: (ThemeMode) -> Unit) {
    GroupCard(Modifier.testTag("phone-appearance")) {
        Text("Appearance", fontWeight = FontWeight.Medium)
        ThemeModeSelector(theme, onTheme, Modifier.testTag("phone-theme-choices"))
    }
}
