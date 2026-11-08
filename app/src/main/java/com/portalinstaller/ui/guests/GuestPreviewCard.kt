/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.guests

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.portalinstaller.data.guests.GuestPreview

/** A compact live preview of the Portal's guest card: QR placeholder on the left, the lines on the right. */
@Composable
internal fun GuestPreviewCard(preview: GuestPreview, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp)
    Row(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, shape).padding(14.dp)
        .semantics(mergeDescendants = true) { contentDescription = "Guest card preview" },
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(84.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center) {
            Text(if (preview.ssid.isBlank()) "QR" else "QR\ncode", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (preview.welcome.isNotEmpty()) Text(preview.welcome, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            PreviewLine("Wi-Fi network", preview.ssid.ifBlank { "Not chosen yet" })
            if (preview.password.isNotEmpty()) PreviewLine("Password", preview.password)
            preview.checkout?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (preview.notes.isNotEmpty()) PreviewLine("Good to know", preview.notes.joinToString(" · "))
            preview.doorCode?.let { PreviewLine("Door code", it) }
            preview.contact.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun PreviewLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
