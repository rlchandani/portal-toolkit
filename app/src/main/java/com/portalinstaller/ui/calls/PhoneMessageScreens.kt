/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.calls

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.portalinstaller.data.calls.ComposeEvent
import com.portalinstaller.data.calls.ComposePhase
import com.portalinstaller.data.calls.PhoneMessage
import com.portalinstaller.data.calls.PhoneMessageCopy
import com.portalinstaller.data.calls.PhoneMessageList

/**
 * The phone's "Leave a message" (the Portal's `leave` / `leaverec` / `leavereview` scenes, phone
 * sized): the self view while ready and recording, a REC chip and Stop · 0:42 of 2:00, then
 * "Only you have seen this" with Record again, Watch it and Send to {name}.
 */
@Composable
internal fun MessageComposeScreen(c: PhoneMessageComposer) {
    val s = c.state
    BackHandler { c.dispatch(ComposeEvent.Discard) }
    Box(Modifier.fillMaxSize().background(CallColors.VideoBg)) {
        val live = s.cameraOn && (s.phase == ComposePhase.READY || s.phase == ComposePhase.RECORDING)
        if (live) SelfPreview(onTexture = c.recorder::preview, modifier = Modifier.fillMaxSize())
        else if (s.phase == ComposePhase.REVIEW || s.phase == ComposePhase.FAILED) PlayerSurface(c.review, Modifier.fillMaxSize())
        else Box(Modifier.fillMaxSize()) { CallRingBackground(c.portal.name) }
        if (!s.cameraOn && s.phase != ComposePhase.REVIEW) GlassChip("Camera off · audio message", "videoOff", Modifier.align(Alignment.Center))
        Column(Modifier.safeDrawingPadding().padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(44.dp).background(Color(0x73000000), CircleShape).callClick("Close") { c.dispatch(ComposeEvent.Discard) },
                    contentAlignment = Alignment.Center) { CallIcon("back", Color.White, 22.dp) }
                RoomAvatar(c.portal.name, 44.dp)
                Column(Modifier.weight(1f)) {
                    Text(PhoneMessageCopy.title(c.portal.name), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Text(PhoneMessageCopy.sub(s.phase, s.failure), color = Color.White.copy(alpha = 0.78f), fontSize = 13.sp, maxLines = 2)
                }
                if (s.phase == ComposePhase.RECORDING) Row(Modifier.background(CallColors.Bad, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(8.dp).background(Color.White, CircleShape))
                    Text("REC", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            GlassChip("End-to-end encrypted")
        }
        Column(Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 36.dp, start = 20.dp, end = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) { Actions(c) }
    }
}

@Composable
private fun Actions(c: PhoneMessageComposer) {
    val s = c.state
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.phase) { while (s.phase == ComposePhase.RECORDING) { now = System.currentTimeMillis(); delay(CLOCK_MS) } }
    when (s.phase) {
        ComposePhase.READY -> Row(horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(if (s.cameraOn) "Camera off" else "Camera on", if (s.cameraOn) "videoOff" else "video") { c.dispatch(ComposeEvent.ToggleCamera) }
            BigCallButton("rec", "Record", CallColors.Bad) { c.dispatch(ComposeEvent.Record(System.currentTimeMillis())) }
        }
        ComposePhase.RECORDING -> BigCallButton("rec", PhoneMessageCopy.stopLabel(s.elapsed(now)), CallColors.Bad) {
            c.dispatch(ComposeEvent.Stop(System.currentTimeMillis()))
        }
        ComposePhase.REVIEW, ComposePhase.FAILED -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Pill("Record again", "back") { c.dispatch(ComposeEvent.RecordAgain) }
                Pill(if (c.review.playing) "Pause" else "Watch it", "play") { if (c.review.playing) c.review.pause() else c.review.play() }
            }
            Pill("Send to ${c.portal.name}", "msg", fill = CallColors.Ok) { c.dispatch(ComposeEvent.Send) }
        }
        ComposePhase.SENDING -> GlassChip("Sending over your home Wi-Fi…", "lock")
        ComposePhase.SENT -> {
            GlassChip("Message sent to ${c.portal.name} · end-to-end encrypted", "lock")
            Pill("Done", null) { c.dispatch(ComposeEvent.Discard) }
        }
    }
}

/** Watching a message a Portal left (mock `msgplay`): the picture, play/pause, call back, delete. */
@Composable
internal fun MessageWatchScreen(m: PhoneMessage, player: MessagePlayer, onClose: () -> Unit, onDelete: () -> Unit) {
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(CallColors.VideoBg)) {
        if (m.audioOnly) Box(Modifier.fillMaxSize()) { CallRingBackground(m.fromName) } else PlayerSurface(player, Modifier.fillMaxSize())
        Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(44.dp).background(Color(0x73000000), CircleShape).callClick("Close", onClose),
                    contentAlignment = Alignment.Center) { CallIcon("back", Color.White, 22.dp) }
                Column {
                    Text(PhoneMessageCopy.notifyTitle(m), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text("${PhoneMessageCopy.clock(m.durationMs)} · ${PhoneMessageList.expiresLine(m, System.currentTimeMillis())}",
                        color = Color.White.copy(alpha = 0.78f), fontSize = 13.sp)
                }
            }
            GlassChip("Only on this phone · encrypted")
        }
        Row(Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 36.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Pill(if (player.playing) "Pause" else if (player.done) "Watch again" else "Play", "play") { if (player.playing) player.pause() else player.play() }
            Pill("Delete", "trash", onClick = onDelete)
        }
    }
}

@Composable
private fun Pill(label: String, icon: String?, fill: Color = CallColors.Glass, onClick: () -> Unit) {
    Row(Modifier.background(fill, RoundedCornerShape(50)).callClick(label, onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        icon?.let { CallIcon(it, Color.White, 20.dp) }
        Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** The front camera's self view, mirrored like a mirror. */
@Composable
private fun SelfPreview(onTexture: (SurfaceTexture?) -> Unit, modifier: Modifier) {
    AndroidView(factory = { ctx ->
        TextureView(ctx).apply {
            scaleX = -1f
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(t: SurfaceTexture, w: Int, h: Int) = onTexture(t)
                override fun onSurfaceTextureSizeChanged(t: SurfaceTexture, w: Int, h: Int) = Unit
                override fun onSurfaceTextureDestroyed(t: SurfaceTexture): Boolean { onTexture(null); return true }
                override fun onSurfaceTextureUpdated(t: SurfaceTexture) = Unit
            }
        }
    }, modifier = modifier)
}

@Composable
private fun PlayerSurface(player: MessagePlayer, modifier: Modifier) {
    AndroidView(factory = { ctx ->
        TextureView(ctx).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(t: SurfaceTexture, w: Int, h: Int) = player.surface(Surface(t))
                override fun onSurfaceTextureSizeChanged(t: SurfaceTexture, w: Int, h: Int) = Unit
                override fun onSurfaceTextureDestroyed(t: SurfaceTexture): Boolean { player.surface(null); return true }
                override fun onSurfaceTextureUpdated(t: SurfaceTexture) = Unit
            }
        }
    }, modifier = modifier)
}

private const val CLOCK_MS = 250L
