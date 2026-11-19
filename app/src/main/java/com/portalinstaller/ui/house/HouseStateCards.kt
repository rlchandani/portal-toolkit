/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.house

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portalinstaller.data.house.HouseStatus

/** The whole-screen states where there is nothing (yet) to show: loading, no Portal, offline, not set up, update. */
@Composable
internal fun HouseStateCard(status: HouseStatus, portal: String, step: String, onRetry: () -> Unit) {
    val c = houseColors
    val (glyph, title, body) = words(status, portal, step)
    val tint = when (status) {
        HouseStatus.PORTAL_OFFLINE, HouseStatus.HA_UNREACHABLE -> c.red
        HouseStatus.TOKEN_REFUSED, HouseStatus.NEEDS_UPDATE, HouseStatus.NOT_SET_UP -> c.amber
        else -> MaterialTheme.colorScheme.onSurface
    }
    HouseCard(Modifier.padding(top = 4.dp), padding = 20) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(52.dp).background(MaterialTheme.colorScheme.surfaceVariant, HouseShapes.tile), contentAlignment = Alignment.Center) {
                HouseGlyph(glyph, tint = tint, size = 28.dp)
            }
            Text(title, fontFamily = FontFamily.Serif, fontSize = 22.sp, fontWeight = FontWeight.Medium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (status) {
                HouseStatus.LOADING, HouseStatus.CONNECTING -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                HouseStatus.PORTAL_OFFLINE, HouseStatus.HA_UNREACHABLE, HouseStatus.TOKEN_REFUSED ->
                    Button(onClick = onRetry, modifier = Modifier.height(44.dp)) { Text("Try again") }
                else -> Unit
            }
        }
    }
}

private fun words(status: HouseStatus, portal: String, step: String): Triple<String, String, String> {
    val p = portal.ifBlank { "your Portal" }
    return when (status) {
        HouseStatus.LOADING -> Triple("home", "Loading your home", "Asking $p for the house.")
        HouseStatus.CONNECTING -> Triple("home", "Connecting to Home Assistant", step.ifBlank { "$p is connecting." })
        HouseStatus.NO_PORTAL -> Triple("home", "Pair a Portal first", "Control Center on the phone goes through a paired Portal, so your Home Assistant token never leaves the house.")
        HouseStatus.PORTAL_OFFLINE -> Triple("warn", "Can't reach your Portals", "Check the phone is on the home Wi-Fi. Control Center works through a Portal, not over the Internet.")
        HouseStatus.HA_UNREACHABLE -> Triple("warn", "Home Assistant isn't answering", "$p can't reach Home Assistant. It retries on its own.")
        HouseStatus.TOKEN_REFUSED -> Triple("lock", "Home Assistant needs a new token", "The Portal's long-lived token was refused. Send a new one from Hearthpane Settings › Home Assistant on the Portal.")
        HouseStatus.NOT_SET_UP -> Triple("home", "Home Assistant isn't set up", "Connect Home Assistant on a Portal (Settings › Home Assistant) and the house appears here.")
        HouseStatus.NEEDS_UPDATE -> Triple("refresh", "Update Hearthpane", "Your Portals need the latest Hearthpane to show Control Center on the phone.")
        HouseStatus.LIVE, HouseStatus.STALE -> Triple("home", "", "")
    }
}
