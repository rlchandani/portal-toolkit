/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.controlcenter

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * Keep Material's gestures and rendering, but do not interpret cancellation as confirmation.
 * Compose converts Android CANCEL into a cancelled drag, without a regular pointer event.
 * Observe Material's own interactions so native hit areas, taps, and accessibility stay intact.
 */
@Composable
internal fun ReleaseOnlySlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onCommit: () -> Unit,
    onCancel: () -> Unit,
    enabled: Boolean,
    modifier: Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
) {
    val interactions = remember { CancelAwareInteractions() }
    Slider(
        value = value,
        onValueChange = {
            // A fresh tap/accessibility action also supersedes a cancelled old gesture.
            interactions.cancelled = false
            onValueChange(it)
        },
        onValueChangeFinished = {
            val cancelled = interactions.cancelled
            interactions.cancelled = false
            if (enabled && !cancelled) onCommit() else onCancel()
        },
        enabled = enabled,
        valueRange = valueRange,
        interactionSource = interactions,
        modifier = modifier,
    )
}

private class CancelAwareInteractions(
    private val delegate: MutableInteractionSource = MutableInteractionSource(),
) : MutableInteractionSource by delegate {
    var cancelled = false

    // Material emits Stop/Cancel before onValueChangeFinished. Observe synchronously
    // at that boundary so confirmation does not depend on a collector's scheduling.
    override suspend fun emit(interaction: Interaction) {
        observe(interaction)
        delegate.emit(interaction)
    }

    override fun tryEmit(interaction: Interaction): Boolean {
        observe(interaction)
        return delegate.tryEmit(interaction)
    }

    private fun observe(interaction: Interaction) {
        when (interaction) {
            is DragInteraction.Cancel -> cancelled = true
            is DragInteraction.Start, is DragInteraction.Stop -> cancelled = false
        }
    }
}
