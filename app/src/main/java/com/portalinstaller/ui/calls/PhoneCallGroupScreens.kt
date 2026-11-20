/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.calls

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portalinstaller.data.calls.EndedPhoneCall
import com.portalinstaller.data.calls.LegPhase
import com.portalinstaller.data.calls.PhoneCall
import com.portalinstaller.data.calls.PhoneCallCopy
import com.portalinstaller.data.calls.PhoneCallMedia
import com.portalinstaller.data.calls.PhoneCalls
import com.portalinstaller.data.calls.PhonePhase
import com.portalinstaller.data.calls.PortalRef
import kotlinx.coroutines.delay

/** Mock `phgroup`: the people already in the call, 92 dp each, overlapping. */
@Composable
internal fun GroupAvatars(names: List<String>) {
    Box(Modifier.size(width = (GROUP_AVATAR.value + (names.size - 1) * GROUP_STEP).dp, height = GROUP_AVATAR)) {
        names.forEachIndexed { i, name ->
            Box(Modifier.offset(x = (i * GROUP_STEP).dp).size(GROUP_AVATAR).border(3.dp, CallColors.VideoBg, RoundedCornerShape(26.dp))) {
                RoomAvatar(name, GROUP_AVATAR)
            }
        }
    }
}

/**
 * Phone group call: the Portals as stacked tiles (two: one above the other; three: one big, two
 * below), each with its name, its state while connecting, and its picture once live.
 */
@Composable
internal fun GroupTiles(call: PhoneCall, media: PhoneCallMedia?, modifier: Modifier) {
    val tiles = listOf(Tile(call.portal, tileState(call.phase), call.remoteCameraOn)) +
        call.legs.map { Tile(it.portal, tileState(it.phase), it.remoteCameraOn) }
    Column(modifier.safeDrawingPadding().padding(top = 96.dp, bottom = 120.dp, start = 8.dp, end = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TileView(tiles[0], media, Modifier.weight(if (tiles.size > 2) 1.25f else 1f).fillMaxWidth())
        if (tiles.size == 2) TileView(tiles[1], media, Modifier.weight(1f).fillMaxWidth())
        if (tiles.size > 2) Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tiles.drop(1).forEach { TileView(it, media, Modifier.weight(1f).fillMaxSize()) }
        }
    }
}

private data class Tile(val portal: PortalRef, val state: String?, val cameraOn: Boolean)

private fun tileState(phase: PhonePhase): String? = when (phase) {
    PhonePhase.LIVE -> null
    PhonePhase.RECONNECTING -> "Reconnecting…"
    else -> "Connecting…"
}

private fun tileState(phase: LegPhase): String? = when (phase) {
    LegPhase.LIVE -> null
    LegPhase.RECONNECTING -> "Reconnecting…"
    LegPhase.JOINING -> "Joining…"
}

@Composable
private fun TileView(tile: Tile, media: PhoneCallMedia?, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xFF1B1D22))) {
        if (media != null && tile.cameraOn && tile.state == null) CallVideo(media, local = false, modifier = Modifier.fillMaxSize(),
            peer = tile.portal.sessionId)
        else Box(Modifier.align(Alignment.Center)) { RoomAvatar(tile.portal.name, 84.dp) }
        Text(PhoneCallCopy.tile(tile.portal), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.BottomStart).padding(10.dp).background(CallColors.Bar, RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 4.dp))
        tile.state?.let { GlassChip(it, null, Modifier.align(Alignment.TopCenter).padding(top = 10.dp)) }
    }
}

/** One calm line for group and move events (moving now, someone left, the move didn't happen). */
@Composable
internal fun CallNotices(call: PhoneCall, modifier: Modifier) {
    val moving = PhoneCallCopy.movingLine(call)
    var shown by remember(call.notice) { mutableStateOf(call.notice != null) }
    LaunchedEffect(call.notice) { if (call.notice != null) { delay(NOTICE_MS); shown = false } }
    val text = moving ?: call.notice?.text?.takeIf { shown } ?: return
    GlassChip(text, if (moving != null) "handoff" else null, modifier)
}

/** "To Portal": which Portal takes the call. Only available Portals not already in the call. */
@Composable
internal fun BoxScope.MovePicker(call: PhoneCall, onClose: () -> Unit) {
    val contacts by PhoneCalls.contacts.collectAsState()
    val targets = remember(contacts, call) { PhoneCalls.moveTargets(call) }
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).callClick("Close", onClose))
    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding().padding(12.dp)
        .background(Color(0xFF1E2026), RoundedCornerShape(28.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Move this call to…", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(if (targets.isEmpty()) "No Portal is free right now. Portals in a call or in quiet hours can’t take it."
            else "It rings there. Pick up on the Portal, and this phone’s camera and mic turn off.",
            color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
        targets.forEach { c ->
            Row(Modifier.fillMaxWidth().background(CallColors.Glass, RoundedCornerShape(18.dp))
                .callClick("Move to ${c.name}") { PhoneCalls.moveTo(c); onClose() }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RoomAvatar(c.name, 44.dp)
                Text(c.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                CallIcon("house", Color.White, 22.dp)
            }
        }
    }
}

/** Mock scene `handoff`, the phone side: "Call moved to Kitchen", with "Move it back here" and Done. */
@Composable
internal fun MovedScreen(ended: EndedPhoneCall) {
    val state by PhoneCalls.state.collectAsState()
    val asked = state.pull?.callId == ended.id
    RingFrame(ended.movedTo?.name ?: ended.portal.name) {
        Box(Modifier.size(120.dp).background(CallColors.Glass, CircleShape), contentAlignment = Alignment.Center) {
            CallIcon("house", Color.White, 56.dp)
        }
        Headline(PhoneCallCopy.movedTitle(ended), PhoneCallCopy.MOVED_BODY)
    }
    BottomActions {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (ended.movedTo != null) Box(Modifier.background(if (asked) CallColors.Glass else CallColors.Ok, RoundedCornerShape(50))
                .callClick(PhoneCallCopy.MOVE_BACK) { if (!asked) PhoneCalls.pullBack() }.padding(horizontal = 26.dp, vertical = 14.dp)) {
                Text(if (asked) "Asking ${ended.movedTo.name}…" else PhoneCallCopy.MOVE_BACK, color = Color.White, fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            }
            GlassChip("Done", null, onClick = PhoneCalls::dismissEnded)
        }
    }
}

private val GROUP_AVATAR = 92.dp
private const val GROUP_STEP = 54f
private const val NOTICE_MS = 4_000L
