/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.portalinstaller.ui.controlcenter.GroupCard
import com.portalinstaller.update.AppUpdateRow
import com.portalinstaller.update.AppUpdateState
import com.portalinstaller.update.AppUpdater
import com.portalinstaller.update.RowAction

/** Settings › This phone: "Portal Toolkit 1.8.9 · Up to date · Check for updates", with progress and errors. */
@Composable
fun AppUpdateSettingsRow(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by AppUpdater.state.collectAsStateWithLifecycle()
    val installed = remember(context) { runCatching { AppUpdater.installedName(context) }.getOrDefault("") }
    val row = AppUpdateRow.present(installed, state)
    GroupCard(modifier.testTag("settings-app-update")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(row.title, fontWeight = FontWeight.Medium)
                if (row.status.isNotBlank()) Text(row.status, style = MaterialTheme.typography.bodySmall,
                    color = if (row.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            when (row.action) {
                RowAction.UPDATE -> Button(onClick = { AppUpdater.start(context) }) { Text(row.actionLabel) }
                RowAction.CHECK -> OutlinedButton(onClick = { AppUpdater.checkInBackground(context) }) { Text(row.actionLabel) }
                RowAction.NONE -> Unit
            }
        }
        if (row.busy) {
            val progress = row.progress
            if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

/** Home: shown only while a Portal Toolkit update is ready to install. */
@Composable
fun AppUpdateBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by AppUpdater.state.collectAsStateWithLifecycle()
    val line = AppUpdateRow.banner(state) ?: return
    val notes = (state as? AppUpdateState.Available)?.manifest?.notes.orEmpty()
    GroupCard(modifier.testTag("home-app-update")) {
        Text(line, style = MaterialTheme.typography.titleMedium)
        if (state is AppUpdateState.NeedsPermission) {
            Text(AppUpdateRow.present("", state).status, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (notes.isNotBlank()) {
            Text(notes, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(onClick = { AppUpdater.start(context) }) { Text("Update") }
    }
}
