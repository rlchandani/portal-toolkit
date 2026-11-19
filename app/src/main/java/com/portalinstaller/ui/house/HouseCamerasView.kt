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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.portalinstaller.data.house.HouseAct
import com.portalinstaller.data.house.HouseCamera
import com.portalinstaller.data.house.HouseRepository

/**
 * Cameras: two per row, each a snapshot through the Portal with an honest LIVE / "Snapshot · 3 h
 * ago" tag. Only rows on screen fetch (lazy list), and nothing fetches while the app is stopped.
 */
internal fun LazyListScope.houseCameras(cameras: List<HouseCamera>, repository: HouseRepository, onOpen: (String) -> Unit) {
    if (cameras.isEmpty()) item(key = "nocams") { HouseCard { Text("No cameras in Home Assistant yet.") } }
    items(cameras.chunked(PER_ROW), key = { row -> "cams:" + row.joinToString { it.id } }) { row ->
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { cam ->
                HouseCameraPicture(cam.id, repository, Modifier.weight(1f).aspectRatio(TILE_RATIO)
                    .clickable(role = Role.Button) { onOpen(cam.id) }, captured = cam.captured, name = cam.name)
            }
            if (row.size < PER_ROW) Spacer(Modifier.weight(1f))
        }
    }
}

/** One camera, full width, refreshing every second; says plainly when Home Assistant has only stills. */
@Composable
internal fun HouseCameraFull(cam: HouseCamera, repository: HouseRepository, enabled: Boolean, onAct: (HouseAct) -> Unit, onDismiss: () -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(cam.name, Modifier.weight(1f), fontFamily = FontFamily.Serif, fontSize = 24.sp, fontWeight = FontWeight.Medium)
                TextButton(onClick = onDismiss) { Text("Done") }
            }
            HouseCameraPicture(cam.id, repository, Modifier.fillMaxWidth().aspectRatio(FULL_RATIO), fast = true, captured = cam.captured,
                refreshKey = refresh)
            Text(if (cam.canStream) "Home Assistant can stream this camera; the phone shows it as fast pictures through your Portal."
                else listOfNotNull(cam.platform?.let { "$it camera" }, "Home Assistant only has still pictures for this camera").joinToString(" · "),
                fontWeight = FontWeight.SemiBold)
            if (!cam.canStream) Text(cam.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (cam.refresh) FilledTonalButton(onClick = { onAct(HouseAct.cameraRefresh(cam.id)); refresh++ }, enabled = enabled,
                modifier = Modifier.height(44.dp)) { Box(Modifier.padding(end = 8.dp)) { HouseGlyph("refresh", size = 18.dp) }; Text("Refresh") }
        }
    }
}

private const val PER_ROW = 2
private const val TILE_RATIO = 4f / 3f
private const val FULL_RATIO = 16f / 9f
