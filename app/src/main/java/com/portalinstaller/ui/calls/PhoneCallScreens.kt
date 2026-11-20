/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.calls

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portalinstaller.data.calls.EndedPhoneCall
import com.portalinstaller.data.calls.PhoneCall
import com.portalinstaller.data.calls.PhoneCallMedia
import com.portalinstaller.data.calls.PhoneCallCopy
import com.portalinstaller.data.calls.PhoneCalls
import com.portalinstaller.data.calls.PhoneEndReason
import com.portalinstaller.data.calls.PhoneGroupMachine
import com.portalinstaller.data.calls.PhonePhase
import kotlinx.coroutines.delay

/** `phOutgoing`: ripple avatar, "Calling …", Cancel. Nothing is open yet; the Portal decides. */
@Composable
internal fun OutgoingCallScreen(call: PhoneCall) {
    RingFrame(call.portal.name) {
        CallRipple(AVATAR) { RoomAvatar(call.portal.name, AVATAR) }
        Headline("Calling ${call.portal.name}",
            if (call.phase == PhonePhase.DIALING) "Reaching the Portal…" else "Ringing · rings this room only")
        GlassChip("End-to-end encrypted")
    }
    BottomActions { BigCallButton("end", "Cancel", CallColors.Bad, PhoneCalls::hangUp) }
}

/** `phIncoming`: the Portal is calling. Camera and mic stay off until Answer. */
@Composable
internal fun IncomingCallScreen(call: PhoneCall, onAnswer: (audioOnly: Boolean) -> Unit) {
    val ring = PhoneCallCopy.ring(call)
    RingFrame(call.portal.name) {
        // Mock `phgroup`: the people already in the call, overlapping; a 1:1 ring shows one rippling avatar.
        if (call.invited.isNotEmpty()) GroupAvatars(call.invited.map { it.name })
        else CallRipple(AVATAR) { RoomAvatar(call.portal.name, AVATAR) }
        Headline(ring.title, ring.sub)
        GlassChip("Camera and mic stay off until you answer")
    }
    BottomActions {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            BigCallButton("end", "Decline", CallColors.Bad, PhoneCalls::decline)
            BigCallButton(if (call.move != null) "handoff" else if (call.video) "video" else "phone", ring.answer, CallColors.Ok) { onAnswer(false) }
        }
        if (call.video) GlassChip("Answer with audio only", "phone", Modifier.padding(top = 18.dp)) { onAnswer(true) }
    }
}

/** `phIncall`: remote full-bleed, self view, the live bar, Mute / Camera / Speaker / End. */
@Composable
internal fun InCallScreen(call: PhoneCall, media: PhoneCallMedia?) {
    var picking by remember(call.id) { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(CallColors.VideoBg)) {
        if (call.group) GroupTiles(call, media, Modifier.fillMaxSize())
        else if (media != null && call.remoteCameraOn) CallVideo(media, local = false, modifier = Modifier.fillMaxSize())
        else RemoteCameraOff(call)
        Column(Modifier.safeDrawingPadding().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LiveBar(call.cameraOn, call.micOn)
            Text(PhoneCallCopy.title(call), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            CallTimer(call)
        }
        CallNotices(call, Modifier.align(Alignment.Center).padding(top = 140.dp))
        if (media != null && call.cameraOn) Box(Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(14.dp)
            .size(PIP_WIDTH, PIP_HEIGHT).clip(RoundedCornerShape(18.dp))) { CallVideo(media, local = true, modifier = Modifier.fillMaxSize()) }
        if (call.phase != PhonePhase.LIVE) GlassChip(if (call.phase == PhonePhase.RECONNECTING) "Reconnecting… the Wi-Fi dropped" else "Connecting securely…",
            modifier = Modifier.align(Alignment.Center))
        Controls(call, Modifier.align(Alignment.BottomCenter)) { picking = true }
        if (picking) MovePicker(call) { picking = false }
    }
}

@Composable
private fun RemoteCameraOff(call: PhoneCall) {
    Box(Modifier.fillMaxSize()) {
        CallRingBackground(call.portal.name)
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            RoomAvatar(call.portal.name, AVATAR)
            if (call.phase == PhonePhase.LIVE) GlassChip("${call.portal.name}’s camera is off", "videoOff")
        }
    }
}

@Composable
private fun CallTimer(call: PhoneCall) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call.id) { while (true) { now = System.currentTimeMillis(); delay(TIMER_STEP_MS) } }
    val start = call.connectedMs ?: return
    Text("${clock(now - start)} · ${if (call.remoteMicOn) "Portal in your home" else "${call.portal.name} is muted"}",
        color = Color.White.copy(alpha = 0.78f), fontSize = 14.sp)
}

@Composable
private fun Controls(call: PhoneCall, modifier: Modifier, onMove: () -> Unit) {
    Row(modifier.safeDrawingPadding().padding(horizontal = 12.dp, vertical = 24.dp).fillMaxWidth()
        .background(CallColors.Bar, RoundedCornerShape(30.dp)).padding(start = 10.dp, end = 10.dp, top = 14.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween) {
        Control(if (call.micOn) "mic" else "micOff", if (call.micOn) "Mute" else "Unmute", !call.micOn, PhoneCalls::toggleMic)
        Control(if (call.cameraOn) "video" else "videoOff", "Camera", !call.cameraOn, PhoneCalls::toggleCamera)
        Control("speaker", "Speaker", !call.speakerOn, PhoneCalls::toggleSpeaker)
        // Mock `phIncall`: "To Portal" moves the call onto the big screen (dimmed while it can't).
        Control("house", "To Portal", false, onMove, dim = !PhoneGroupMachine.canMoveAtAll(call))
        Control("end", "End", false, PhoneCalls::hangUp, fill = CallColors.Bad)
    }
}

@Composable
private fun Control(icon: String, label: String, off: Boolean, onClick: () -> Unit, fill: Color? = null, dim: Boolean = false) {
    Column(Modifier.width(CONTROL_COLUMN).alpha(if (dim) DIM_ALPHA else 1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(CONTROL).background(fill ?: if (off) Color.White else CallColors.Glass, CircleShape).callClick(label, onClick),
            contentAlignment = Alignment.Center) { CallIcon(icon, if (off && fill == null) Color(0xFF16181D) else Color.White, 26.dp) }
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** The calm ended card: why, how long, then it closes by itself. */
@Composable
internal fun EndedCallScreen(ended: EndedPhoneCall) {
    if (ended.reason == PhoneEndReason.MOVED) { MovedScreen(ended); return }
    RingFrame(ended.portal.name) {
        RoomAvatar(ended.portal.name, AVATAR)
        val length = ended.connectedMs?.let { clock(ended.endedMs - it) }
        val quiet = ended.quietEndMin?.let { "Their Portals ring again from %d:%02d.".format(it / MINUTES, it % MINUTES) }
        Headline(ended.reason.title, listOfNotNull(length, quiet, ended.portal.name.takeIf { length == null && quiet == null }).joinToString(" · "))
    }
    BottomActions { GlassChip("Done", null, onClick = PhoneCalls::dismissEnded) }
}

@Composable
internal fun RingFrame(name: String, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        CallRingBackground(name)
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)) {
            content()
            Spacer(Modifier.height(BOTTOM_ROOM))
        }
    }
}

@Composable
internal fun BottomActions(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 30.dp, vertical = 44.dp),
            horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}

@Composable
internal fun Headline(title: String, sub: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2)
        if (sub.isNotBlank()) Text(sub, color = Color.White.copy(alpha = 0.78f), fontSize = 16.sp, textAlign = TextAlign.Center)
    }
}

private val AVATAR = 120.dp
private val PIP_WIDTH = 110.dp
private val PIP_HEIGHT = 150.dp
private val CONTROL = 56.dp
private val CONTROL_COLUMN = 72.dp
private val BOTTOM_ROOM = 160.dp
private const val TIMER_STEP_MS = 1_000L
private const val DIM_ALPHA = 0.4f
private const val MINUTES = 60
