/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.controlcenter

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.portalinstaller.ui.common.JoinedAction
import com.portalinstaller.ui.common.JoinedActionGroup
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.portalinstaller.viewmodel.RemoteTextController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemoteControlsPanel(
    deviceKey: String,
    enabled: Boolean,
    onNavigate: (String) -> Unit,
    onSlideshow: () -> Unit,
    onMove: (Float, Float) -> Unit,
    onTap: () -> Unit,
    onScroll: (String) -> Unit,
    textController: RemoteTextController,
    playback: @Composable () -> Unit,
) {
    val text by textController.state.collectAsStateWithLifecycle()
    PageSections {
        GroupCard {
            JoinedActionGroup(listOf(
                JoinedAction("Back", { onNavigate("back") }, enabled),
                JoinedAction("Home", { onNavigate("home") }, enabled, emphasized = true),
                JoinedAction("Slideshow", onSlideshow, enabled),
            ))
        }
        playback()
        GroupCard {
            Box(Modifier.fillMaxWidth().height(168.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp))
                .testTag("remote-touchpad")
                .semantics(mergeDescendants = true) {
                    contentDescription = "Portal touchpad"
                    if (enabled) onClick("Select on Portal") { onTap(); true } else disabled()
                }
                .pointerInput(enabled, deviceKey) {
                    if (enabled) detectDragGestures { change, drag ->
                        change.consume(); onMove(drag.x, drag.y)
                    }
                }
                .pointerInput(enabled, deviceKey) { if (enabled) detectTapGestures(onTap = { onTap() }) },
                contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ControlGlyph("remote", modifier = Modifier.size(28.dp))
                    Text("Touchpad")
                    Text("Move to point · Tap to select", style = MaterialTheme.typography.bodySmall)
                }
            }
            JoinedActionGroup(listOf(
                JoinedAction("Scroll up", { onScroll("up") }, enabled),
                JoinedAction("Scroll down", { onScroll("down") }, enabled),
            ))
            OutlinedButton(onClick = { textController.open(deviceKey) }, enabled = enabled,
                modifier = Modifier.fillMaxWidth()) { Text("Type on Portal") }
        }
    }
    if (text.deviceKey == deviceKey && text.keyboardOpen && enabled) {
        val focus = remember { FocusRequester() }
        fun send() { if (enabled) textController.send(deviceKey) }
        ModalBottomSheet(
            onDismissRequest = { textController.dismiss(deviceKey) },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Type on Portal", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { textController.dismiss(deviceKey) },
                        modifier = Modifier.semantics { contentDescription = "Close keyboard" }) { Text("×") }
                }
                Text("Select a text field on the Portal first.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(text.draft, onValueChange = { textController.edit(deviceKey, it) },
                    label = { Text("Text to send") }, enabled = enabled && !text.sending,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("remote-text-input"),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }))
                if (text.error) Text("Couldn’t confirm sending. Your draft is kept.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Button(onClick = { send() }, enabled = enabled && !text.sending && text.draft.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()) { Text(if (text.sending) "Sending…" else "Send text") }
                Spacer(Modifier.height(8.dp))
                // The sheet owns a separate window; request focus after its content is attached.
                LaunchedEffect(Unit) { focus.requestFocus() }
            }
        }
    }
}
