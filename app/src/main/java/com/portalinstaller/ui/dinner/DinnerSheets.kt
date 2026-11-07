/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.dinner

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.portalinstaller.data.dinner.DinnerCopy
import com.portalinstaller.data.dinner.DinnerResult
import com.portalinstaller.data.dinner.DinnerRowStatus
import com.portalinstaller.data.dinner.PhoneMessage

internal object DinnerPalette {
    @Composable fun glyph(): Color = Color(0xFFC9A54E)
    val ok = Color(0xFF2E9E6E)
    val bad = Color(0xFFD9534F)
}

@Composable
internal fun DinnerNotice(title: String, detail: String) {
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** In-app heads-up while the screen is open ("Family Area replied · On my way"). */
@Composable
internal fun DinnerReplyBanner(roomName: String, reply: String, onOpen: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).clickable(onClick = onOpen)
        .semantics { liveRegion = LiveRegionMode.Polite }, shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.inverseSurface)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(dinnerIcon("reply")), null, tint = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("$roomName replied", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.inverseOnSurface)
                Text(reply, color = MaterialTheme.colorScheme.inverseOnSurface)
            }
            Text("now", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.inverseOnSurface)
        }
    }
}

@Composable
internal fun DinnerComposeDialog(onCancel: () -> Unit, onSend: (PhoneMessage) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val message = PhoneMessage.custom(text)
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Type a message") },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = PhoneMessage.limit(it.replace('\n', ' ')) }, singleLine = true,
                placeholder = { Text("Message for the Portals") },
                supportingText = { Text("${text.codePointCount(0, text.length)}/${PhoneMessage.MAX_CUSTOM}") },
                modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { Button(onClick = { message?.let(onSend) }, enabled = message != null) { Text("Send") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DinnerResultsSheet(result: DinnerResult, onRetry: (String) -> Unit, onDone: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDone, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(DinnerCopy.title(result), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            result.rows.forEachIndexed { i, row ->
                if (i > 0) HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(dinnerIcon("room")), null, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(row.name, style = MaterialTheme.typography.titleMedium)
                        Text(row.status.text, color = when {
                            row.status.failed -> DinnerPalette.bad
                            row.status == DinnerRowStatus.SENDING -> MaterialTheme.colorScheme.onSurfaceVariant
                            row.status == DinnerRowStatus.QUIET -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> DinnerPalette.ok
                        })
                        row.reply?.let { Text("Replied: $it", fontWeight = FontWeight.SemiBold) }
                    }
                    if (row.retry) FilledTonalButton(onClick = { onRetry(row.roomId) }) { Text("Try again") }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.End) {
                Button(onClick = onDone) { Text("Done") }
            }
        }
    }
}
