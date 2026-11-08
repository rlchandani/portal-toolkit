/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.portalinstaller.data.music.MusicTimeText
import com.portalinstaller.data.music.PortalPlayer
import com.portalinstaller.data.music.SleepChoice
import kotlinx.coroutines.delay

/** "Stops in N min" changes once a minute; re-check a few times a minute so it never lags long. */
private const val SLEEP_TICK_MS = 15_000L

/** The room card's sleep timer: Off / 15 / 30 / 60 min / End of song, and how long is left. */
@Composable
internal fun SleepTimerControl(player: PortalPlayer, enabled: Boolean, wallClock: () -> Long, onChoose: (SleepChoice) -> Unit) {
    val now by produceState(wallClock(), player.sleepUntilMs, player.sleepEndOfSong) {
        while (true) { value = wallClock(); delay(SLEEP_TICK_MS) }
    }
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Sleep timer", style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { menu = true }, enabled = enabled, modifier = Modifier.testTag("music-sleep")) {
                Text(MusicTimeText.sleepLine(player, now) ?: SleepChoice.OFF.title)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                SleepChoice.entries.forEach { choice ->
                    DropdownMenuItem(text = { Text(choice.title) }, onClick = { menu = false; onChoose(choice) })
                }
            }
        }
    }
}
