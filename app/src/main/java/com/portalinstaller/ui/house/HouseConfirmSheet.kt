/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.house

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portalinstaller.data.house.HouseConfirm
import com.portalinstaller.data.house.HouseRepository

/**
 * The phone flow from the mock: "Close the garage?", the live garage camera, and the
 * fingerprint. Biometric where the phone has it (it opens straight away), otherwise a plain
 * confirm button; an alarm code gets a PIN pad instead. [onConfirmed] gets the code (or null).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HouseConfirmSheet(confirm: HouseConfirm, repository: HouseRepository, onConfirmed: (String?) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val biometric = remember { !confirm.code && HouseBiometric.available(context) }
    var fallback by remember { mutableStateOf(!biometric) }
    val ask = {
        HouseBiometric.prompt(context, confirm.title, confirm.body.ifBlank { "Confirm it's you" }, onOk = { onConfirmed(null) },
            onCancel = {}, onUnavailable = { fallback = true })
    }
    DisposableEffect(confirm.ticket) {
        val signal = if (biometric && !confirm.withCamera) ask() else null
        onDispose { signal?.cancel() }
    }
    ModalBottomSheet(onDismissRequest = onCancel, sheetState = sheet, shape = HouseShapes.sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(confirm.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            if (confirm.body.isNotBlank()) Text(confirm.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
            confirm.camera?.takeIf { confirm.withCamera }?.let { cam ->
                HouseCameraPicture(cam, repository, Modifier.fillMaxWidth().height(170.dp), fast = true)
            }
            when {
                confirm.code -> HousePinPad(confirm.button) { onConfirmed(it) }
                fallback -> Button(onClick = { onConfirmed(null) }, Modifier.fillMaxWidth().height(48.dp)) { Text(confirm.button) }
                else -> FingerprintButton { ask() }
            }
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Cancel") }
        }
    }
}

@Composable
private fun FingerprintButton(onTap: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(76.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
            .clickable(role = Role.Button, onClick = onTap).semantics { contentDescription = "Confirm with fingerprint" },
            contentAlignment = Alignment.Center) { HouseGlyph("fingerprint", size = 40.dp) }
        Spacer(Modifier.height(6.dp))
        Text("Touch the sensor to confirm", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The alarm code: dots and a 3 × 4 keypad. The code goes to the Portal once, on the confirmed resend. */
@Composable
internal fun HousePinPad(button: String, onDone: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    Text("•".repeat(code.length).ifEmpty { "Enter the code" }, Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
        fontSize = if (code.isEmpty()) 15.sp else 28.sp, letterSpacing = 6.sp)
    val keys = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "✓"))
    keys.forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { k ->
                val enabled = k != "✓" || code.length >= MIN_CODE
                OutlinedButton(onClick = {
                    when (k) {
                        "⌫" -> code = code.dropLast(1)
                        "✓" -> onDone(code)
                        else -> if (code.length < MAX_CODE) code += k
                    }
                }, enabled = enabled, modifier = Modifier.weight(1f).height(52.dp)
                    .semantics { contentDescription = if (k == "✓") button else if (k == "⌫") "Delete" else k }) {
                    Text(k, fontSize = 20.sp)
                }
            }
        }
    }
}

private const val MIN_CODE = 4
private const val MAX_CODE = 8
