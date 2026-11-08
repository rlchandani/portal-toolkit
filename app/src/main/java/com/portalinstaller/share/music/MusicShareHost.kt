/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.share.music

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.portalinstaller.share.LastShareRoom
import com.portalinstaller.share.SenderName
import com.portalinstaller.share.ShareHub
import com.portalinstaller.share.SheetScaffold
import com.portalinstaller.ui.controlcenter.RemoteLaunch

/**
 * "Play on a Portal": checks the link (oEmbed), then the picker, quiet-hours ask, sending,
 * "isn't answering" and the confirmation. [onSendAsLink] hands a non-music video back to the
 * ordinary link share ("opens in the Portal's browser").
 */
@Composable
fun MusicShareHost(hub: ShareHub, link: MusicLink, subject: String?, onSendAsLink: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lastUsed = remember { LastShareRoom.get(context) }
    val flow = remember {
        MusicShareController(link, subject, scope, hub.music, hub::rooms, MusicLinkChecker(),
            from = { SenderName.get(context) }, shareId = hub.newJobId(), rememberRoom = { LastShareRoom.set(context, it) })
    }
    LaunchedEffect(flow) { flow.load(lastUsed) }
    val pick = flow.pick
    SheetScaffold(onDismiss = onClose) {
        when (val s = flow.screen) {
            MusicScreen.Checking -> MusicChecking()
            is MusicScreen.Cant -> MusicCant(s, onClose)
            is MusicScreen.Mix -> MusicMix(s, onClose) { flow.songOnly(s) }
            MusicScreen.Pick -> MusicPicker(pick, lastUsed, onPick = flow::update, onCancel = onClose, onSend = flow::startSend,
                onSendAsLink = if (pick.isMusic) null else onSendAsLink)
            is MusicScreen.Sending -> MusicSending(pick, s.rooms)
            is MusicScreen.AskQuiet -> MusicQuietAsk(pick, s, onNo = { flow.quietNo(s) }, onYes = { flow.quietYes(s) })
            is MusicScreen.NoAnswer -> MusicNoAnswer(pick, s.rooms, flow.otherRoom(s.rooms), onRetry = { flow.retry(s.rooms) },
                onOther = flow::playElsewhere, onCancel = onClose)
            is MusicScreen.Refused -> MusicRefused(s.message, onBack = { flow.show(MusicScreen.Pick) }, onClose = onClose)
            is MusicScreen.Done -> MusicDone(pick, s, onRemote = {
                s.summary.played.firstOrNull()?.let { (room, _) -> context.startActivity(RemoteLaunch.intent(context, room.id)) }
                onClose()
            }, onDone = onClose)
        }
    }
}
