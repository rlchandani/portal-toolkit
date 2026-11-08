/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.share

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Picker first (unless reopening a job), then the live per-file job sheet. */
@Composable
fun ShareSheetHost(hub: ShareHub, payload: SharePayload?, openJobId: String?, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var jobId by remember { mutableStateOf(openJobId) }
    var preparing by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    fun send(option: RoomOption, edited: SharePayload) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        LastShareRoom.set(context, option.key)
        preparing = true
        scope.launch {
            val id = hub.newJobId()
            val from = SenderName.get(context)
            val job = runCatching { withContext(Dispatchers.IO) { buildJob(context, hub, id, from, option.rooms, edited) } }
            preparing = false
            job.onSuccess { hub.runner.start(it); jobId = it.id }
                .onFailure { problem = it.message ?: "These files can’t be read. Nothing was sent." }
        }
    }

    SheetScaffold(onDismiss = onClose) {
        val current = jobId
        when {
            current != null -> ShareJobSheet(hub, current, onClose)
            payload != null -> SharePicker(hub, payload, preparing, problem, onCancel = onClose, onSend = ::send)
            else -> androidx.compose.runtime.LaunchedEffect(Unit) { onClose() }
        }
    }
}

private fun buildJob(context: android.content.Context, hub: ShareHub, id: String, from: String,
    rooms: List<ShareRoom>, payload: SharePayload): ShareJob = when (payload) {
    is SharePayload.Images -> ShareJob(id, ShareKind.PHOTOS, from, System.currentTimeMillis(), rooms,
        ShareStaging.stage(context, payload.uris.map(Uri::parse), hub.store, id))
    is SharePayload.Note -> ShareJob(id, ShareKind.NOTE, from, System.currentTimeMillis(), rooms,
        listOf(ShareItem("note", "Note", payload.text.length.toLong())), text = payload.text)
    is SharePayload.Link -> ShareJob(id, ShareKind.LINK, from, System.currentTimeMillis(), rooms,
        listOf(ShareItem("link", "Link", payload.url.length.toLong())), link = ShareLink(payload.url, payload.title))
}

@Composable
internal fun SheetScaffold(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter) {
        Surface(Modifier.fillMaxWidth().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), tonalElevation = 3.dp) {
            Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
                Box(Modifier.align(Alignment.CenterHorizontally).width(32.dp).height(4.dp)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(2.dp)))
                Spacer(Modifier.height(16.dp))
                content()
            }
        }
    }
}

/** The two bottom buttons every state of the sheet uses: a tonal secondary and a filled primary. */
@Composable
internal fun SheetActions(secondary: String, onSecondary: () -> Unit, primary: String?, onPrimary: () -> Unit,
    primaryEnabled: Boolean = true, outlinedPrimary: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilledTonalButton(onClick = onSecondary, modifier = Modifier.weight(if (primary == null) 1f else 0.4f).height(56.dp)) { Text(secondary) }
        if (primary != null) {
            if (outlinedPrimary) OutlinedButton(onClick = onPrimary, enabled = primaryEnabled, modifier = Modifier.weight(0.6f).height(56.dp)) { Text(primary) }
            else Button(onClick = onPrimary, enabled = primaryEnabled, modifier = Modifier.weight(0.6f).height(56.dp)) { Text(primary) }
        }
    }
}
