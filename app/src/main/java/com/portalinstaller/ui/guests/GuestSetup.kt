/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.guests

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.portalinstaller.data.guests.GuestDraft
import com.portalinstaller.data.guests.GuestSnapshot
import com.portalinstaller.data.guests.GuestStep
import com.portalinstaller.ui.controlcenter.GroupCard

/**
 * The step-by-step guest card setup (same steps and copy as the Portal): Wi-Fi network, password,
 * house basics, welcome message, then preview & finish. A live preview sits above the steps.
 */
@Composable
internal fun GuestSetup(
    shown: GuestSnapshot,
    target: String,
    busy: Boolean,
    many: Boolean,
    onSave: (GuestDraft) -> Unit,
) {
    var draft by remember(shown) { mutableStateOf(GuestDraft.from(shown)) }
    var chosen by rememberSaveable(target) { mutableStateOf(GuestStep.NETWORK) }
    var finished by rememberSaveable(target) { mutableStateOf(false) }
    val use24 = DateFormat.is24HourFormat(LocalContext.current)
    // The password step vanishes when an open network is picked.
    val step = if (chosen in draft.steps) chosen else GuestStep.BASICS
    GuestPreviewCard(draft.preview(use24))
    GroupCard {
        if (finished) {
            GuestFinished(target, onEdit = { finished = false; chosen = GuestStep.NETWORK })
            return@GroupCard
        }
        GuestStepHeader(draft, step)
        GuestStepBody(draft, step, many) { draft = it }
        val problem = draft.problem(step)
        problem?.takeIf { step == GuestStep.PREVIEW || draft.card.ssid.isNotBlank() }?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        GuestStepButtons(draft, step, problem == null, busy, target,
            onBack = { draft.previous(step)?.let { chosen = it } },
            onNext = { next -> chosen = next },
            onFinish = { onSave(draft); finished = true })
    }
}

@Composable
private fun GuestStepHeader(draft: GuestDraft, step: GuestStep) {
    val index = draft.steps.indexOf(step) + 1
    Text("Step $index of ${draft.steps.size}", style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(step.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.semantics { heading() })
}

@Composable
private fun GuestStepBody(draft: GuestDraft, step: GuestStep, many: Boolean, onChange: (GuestDraft) -> Unit) {
    when (step) {
        GuestStep.NETWORK -> GuestNetworkStep(draft, onChange)
        GuestStep.PASSWORD -> GuestPasswordStep(draft, many, onChange)
        GuestStep.BASICS -> GuestBasicsStep(draft, onChange)
        GuestStep.WELCOME -> GuestWelcomeStep(draft, onChange)
        GuestStep.PREVIEW -> GuestNote("This is how the card will look on the Portal. Guests scan the QR code with their phone camera to join.")
    }
}

@Composable
private fun GuestStepButtons(
    draft: GuestDraft,
    step: GuestStep,
    canContinue: Boolean,
    busy: Boolean,
    target: String,
    onBack: () -> Unit,
    onNext: (GuestStep) -> Unit,
    onFinish: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (draft.previous(step) != null) OutlinedButton(onClick = onBack, Modifier.weight(1f).heightIn(min = 56.dp)) { Text("Back") }
        val next = draft.next(step)
        if (next != null) {
            Button(onClick = { onNext(next) }, enabled = canContinue, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("Next") }
        } else {
            Button(onClick = onFinish, enabled = canContinue && !busy, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                Text(if (busy) "Sending to $target…" else "Looks good")
            }
        }
    }
}

@Composable
private fun GuestFinished(target: String, onEdit: () -> Unit) {
    Text("Guest card saved", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.semantics { heading() })
    GuestNote("When guests arrive, start Guest mode above, or on $target open Tools → Guests and tap Show guest card. " +
        "Guest mode hides photos, calendar, notes and your music library.")
    OutlinedButton(onClick = onEdit, Modifier.heightIn(min = 48.dp)) { Text("Edit the card") }
}
