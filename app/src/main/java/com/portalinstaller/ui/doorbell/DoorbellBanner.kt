/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.doorbell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.portalinstaller.data.doorbell.DoorbellAlerts
import com.portalinstaller.data.doorbell.DoorbellBannerPolicy
import com.portalinstaller.data.doorbell.DoorbellEvent
import com.portalinstaller.data.doorbell.doorbellRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** The Portals are asked for doorbell events this often while the app is open. */
private const val POLL_MS = 5_000L
private val ButtonHeight = 48.dp

/**
 * "Someone's at the door" across the top of the app. While the app is in the foreground it asks
 * the paired Portals every [POLL_MS]; a new press (or motion) shows here and as a notification.
 * Closed, the app hears nothing (background push is out of scope). The banner hides itself once
 * the event is [DoorbellBannerPolicy.FRESH_MS] old, or on Dismiss.
 */
@Composable
fun DoorbellBannerHost(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repository = remember { doorbellRepository(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var shown by remember { mutableStateOf<DoorbellEvent?>(null) }
    LaunchedEffect(repository, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                val fresh = DoorbellAlerts.announce(context, repository.read())
                fresh.firstOrNull()?.let { shown = it }
                fresh.forEach { DoorbellAlerts.notify(context, it) }
                delay(POLL_MS)
            }
        }
    }
    val event = shown
    LaunchedEffect(event?.id) {
        if (event != null) {
            delay((DoorbellBannerPolicy.FRESH_MS - event.ageMs).coerceAtLeast(0L))
            if (shown?.id == event.id) shown = null
        }
    }
    AnimatedVisibility(visible = event != null, modifier = modifier) {
        if (event != null) DoorbellBanner(event, onDismiss = { shown = null })
    }
}

@Composable
private fun DoorbellBanner(event: DoorbellEvent, onDismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(event.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(event.detail, style = MaterialTheme.typography.bodyMedium)
                Text("Every Portal is showing the camera.", style = MaterialTheme.typography.bodySmall)
            }
            FilledTonalButton(onClick = onDismiss, modifier = Modifier.heightIn(min = ButtonHeight)) { Text("Dismiss") }
        }
    }
}
