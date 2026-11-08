/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.portalinstaller.data.companion.PlaybackVolume
import com.portalinstaller.data.music.MusicCopy
import com.portalinstaller.data.music.VolumeChange
import com.portalinstaller.ui.common.JoinedAction
import com.portalinstaller.ui.common.JoinedActionGroup
import com.portalinstaller.ui.controlcenter.ReleaseOnlySlider
import kotlin.math.roundToInt

private const val PERCENT = 100
private val SLIDER_HEIGHT = 48.dp

/**
 * The Portal's real STREAM_MUSIC level: slider (sent on release), −/+ and mute. Without a reading
 * yet (an older Hearthpane, or a poll in flight) the relative buttons still work.
 */
@Composable
internal fun MusicVolumeControls(room: String, volume: PlaybackVolume?, enabled: Boolean, onVolume: (VolumeChange) -> Unit) {
    val settable = volume == null || volume.canSet
    if (volume != null && volume.canSet) {
        var drag by remember(room) { mutableStateOf<Float?>(null) }
        val shown = drag ?: volume.level.toFloat()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Volume", style = MaterialTheme.typography.labelLarge)
            Text(if (volume.muted && drag == null) "Muted" else "${(shown / volume.max * PERCENT).roundToInt()}%",
                style = MaterialTheme.typography.labelMedium)
        }
        ReleaseOnlySlider(
            value = shown.coerceIn(0f, volume.max.toFloat()),
            onValueChange = { drag = it },
            onCommit = {
                drag?.let { onVolume(VolumeChange.Set(it.roundToInt().coerceIn(0, volume.max))) }
                drag = null
            },
            onCancel = { drag = null },
            valueRange = 0f..volume.max.toFloat(),
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = SLIDER_HEIGHT).testTag("music-volume")
                .semantics { contentDescription = "$room volume" },
        )
    }
    JoinedActionGroup(listOf(
        JoinedAction("Volume −", { onVolume(VolumeChange.Down) }, enabled && settable),
        JoinedAction(if (volume?.muted == true) "Unmute" else "Mute", { onVolume(VolumeChange.ToggleMute) }, enabled && settable),
        JoinedAction("Volume +", { onVolume(VolumeChange.Up) }, enabled && settable),
    ))
    if (volume != null && !volume.canSet) Note(MusicCopy.volumeFixed(room))
}
