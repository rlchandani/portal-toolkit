/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.house

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.portalinstaller.data.house.HouseCamLiveness
import com.portalinstaller.data.house.HouseCamWatch
import com.portalinstaller.data.house.HouseRepository
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** What the phone saw from each camera this session (hash and since only, never the picture). */
private object HouseCamMemory {
    val seen = ConcurrentHashMap<String, HouseCamWatch>()
}

/**
 * A camera picture fetched through the paired Portal, refreshed while this is on screen and the
 * app is started: every second for the full view and the garage confirm ([fast]), every
 * [GRID_MS] in grids. Unchanged bytes are never decoded again; the tag says honestly whether
 * the picture is live or how old the snapshot is (see [HouseCamLiveness]).
 */
@Composable
internal fun HouseCameraPicture(cameraId: String, repository: HouseRepository, modifier: Modifier = Modifier, fast: Boolean = false,
                                captured: Long? = null, refreshKey: Int = 0, name: String? = null) {
    var image by remember(cameraId) { mutableStateOf<ImageBitmap?>(null) }
    var watch by remember(cameraId) { mutableStateOf(HouseCamMemory.seen[cameraId] ?: HouseCamWatch()) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var failed by remember(cameraId) { mutableStateOf(false) }
    val capturedNow by rememberUpdatedState(captured)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    BoxWithConstraints(modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xFF101216))) {
        val width = with(LocalDensity.current) { maxWidth.roundToPx() }.coerceIn(MIN_WIDTH, MAX_WIDTH)
        LaunchedEffect(cameraId, fast, refreshKey, lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val bytes = repository.camera(cameraId, width)
                    val at = System.currentTimeMillis()
                    if (bytes == null) failed = image == null else {
                        val hash = HouseCamLiveness.hash(bytes)
                        if (hash != watch.hash || image == null) withContext(Dispatchers.Default) { decode(bytes, width) }?.let { image = it }
                        watch = watch.frame(hash, at, capturedNow).also { HouseCamMemory.seen[cameraId] = it }
                        failed = false
                    }
                    now = at
                    delay(if (fast) FAST_MS else GRID_MS)
                }
            }
        }
        val label = HouseCamLiveness.label(watch, now)
        image?.let { Image(it, name ?: "Camera", Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
        if (image == null) Text(if (failed) "Picture unavailable" else "Loading picture…", Modifier.align(Alignment.Center),
            color = Color(0xFFB8B8B8), style = MaterialTheme.typography.bodySmall)
        CamTag(label.text, label.live, Modifier.align(Alignment.TopStart).padding(8.dp))
        name?.let { Text(it, Modifier.align(Alignment.BottomStart).padding(10.dp), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
    }
}

@Composable
private fun CamTag(text: String, live: Boolean, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(999.dp)).background(if (live) Color(0xFFD33D4C) else Color(0xB3000000))
        .padding(horizontal = 8.dp, vertical = 3.dp).semantics { contentDescription = text.lowercase() }) {
        Text(text, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/** Downsampled so a 1080p snapshot becomes a picture the size of its tile, prepared for drawing off the main thread. */
private fun decode(bytes: ByteArray, width: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= width) sample *= 2
    val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) }
        .getOrNull() ?: return null
    bitmap.prepareToDraw()
    return bitmap.asImageBitmap()
}

private const val FAST_MS = 1_000L
private const val GRID_MS = 5_000L
private const val MIN_WIDTH = 320
private const val MAX_WIDTH = 1280
