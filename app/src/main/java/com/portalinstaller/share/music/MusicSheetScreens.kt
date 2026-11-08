/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.share.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.portalinstaller.share.FailedRed
import com.portalinstaller.share.SheetActions
import com.portalinstaller.share.ShareRoom

@Composable
internal fun MusicChecking() {
    MusicSheetHead("Play on a Portal", null)
    Row(Modifier.fillMaxWidth().padding(vertical = 32.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center) {
        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        Text("Checking the link…", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

/** Embedding blocked / private / age-restricted: the reason, "Nothing was sent", and only OK. */
@Composable
internal fun MusicCant(screen: MusicScreen.Cant, onClose: () -> Unit) {
    MusicSheetHead("Play on a Portal", "Video from YouTube")
    MusicItemCard(screen.thumb, screen.title, screen.subtitle, dim = true)
    MusicNotice(MusicShareRules.cantTitle(screen.reason), MusicShareRules.cantDetail(screen.reason))
    SheetActions("OK", onClose, null, {})
}

@Composable
internal fun MusicMix(screen: MusicScreen.Mix, onClose: () -> Unit, onSongOnly: () -> Unit) {
    MusicSheetHead("Play on a Portal", "Mix from YouTube Music")
    val seed = screen.mix.seed()
    MusicNotice(MusicShareRules.MIX_TITLE, MusicShareRules.MIX_DETAIL + if (seed != null) MusicShareRules.MIX_SEED else "")
    if (seed != null) {
        MusicItemCard(screen.seed?.thumbnail ?: "https://i.ytimg.com/vi/${seed.videoId}/hqdefault.jpg",
            screen.seed?.title ?: "The song this mix started from", screen.seed?.author)
        SheetActions("Cancel", onClose, "Play the song only", onSongOnly)
    } else SheetActions("OK", onClose, null, {})
}

/** The main sheet: item, Room, When, the hint line and a button that names the result. */
@Composable
internal fun MusicPicker(pick: MusicPick, lastUsed: String?, onPick: (MusicPick) -> Unit, onCancel: () -> Unit,
    onSend: () -> Unit, onSendAsLink: (() -> Unit)?) {
    val item = pick.item ?: return
    MusicSheetHead("Play on a Portal", if (pick.isMusic) MusicShareRules.sheetSubtitle(item) else "Video from YouTube")
    MusicItemCard(item.thumb, item.title, MusicShareRules.cardSubtitle(item, pick.count),
        note = if (item.fromMix) "The song only, not the mix" else null)
    if (item.kind == MusicItem.Kind.PLAYLIST) ShuffleRow(pick.shuffle) { onPick(pick.copy(shuffle = it)) }
    SectionLabel("Room")
    val options = pick.options
    when {
        options == null -> Text("Looking for your Portals…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        options.isEmpty() -> Text("Pair a Portal in Portal Toolkit first. Nothing was sent.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> MusicRoomList(options, pick.selected, pick.statuses, lastUsed) { onPick(pick.select(it)) }
    }
    SectionLabel("When")
    MusicActionPicker(pick.action) { onPick(pick.choose(it)) }
    if (pick.rooms.isNotEmpty()) {
        Text(MusicShareRules.hint(pick.action, pick.targets), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
    }
    SheetActions("Cancel", onCancel, MusicShareRules.sendLabel(pick.action, pick.rooms), onSend,
        primaryEnabled = pick.rooms.isNotEmpty())
    onSendAsLink?.let { TextButton(it, Modifier.fillMaxWidth()) { Text(MusicShareRules.SEND_AS_LINK) } }
}

@Composable
internal fun MusicSending(pick: MusicPick, rooms: List<ShareRoom>) {
    val item = pick.item ?: return
    MusicSheetHead("Play on a Portal", MusicShareRules.sheetSubtitle(item))
    MusicItemCard(item.thumb, item.title, MusicShareRules.cardSubtitle(item, pick.count))
    Row(Modifier.fillMaxWidth().padding(top = 24.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center) {
        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        Text(MusicShareRules.sendingLabel(rooms), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleMedium)
    }
}

/** Quiet hours ask first, and the default (filled) answer is Don't play. */
@Composable
internal fun MusicQuietAsk(pick: MusicPick, screen: MusicScreen.AskQuiet, onNo: () -> Unit, onYes: () -> Unit) {
    val item = pick.item ?: return
    MusicSheetHead("Play on a Portal", null)
    MusicItemCard(item.thumb, item.title, MusicShareRules.cardSubtitle(item, pick.count))
    MusicNotice(MusicShareRules.quietQuestion(screen.rooms), MusicShareRules.quietDetail(screen.until))
    StackedActions(primary = "Don't play" to onNo, secondary = "Play anyway, quietly" to onYes)
}

@Composable
internal fun MusicNoAnswer(pick: MusicPick, rooms: List<ShareRoom>, other: ShareRoom?, onRetry: () -> Unit,
    onOther: (ShareRoom) -> Unit, onCancel: () -> Unit) {
    val item = pick.item ?: return
    MusicSheetHead("Play on a Portal", null)
    MusicItemCard(item.thumb, item.title, MusicShareRules.cardSubtitle(item, pick.count))
    MusicNotice(MusicShareRules.offlineTitle(rooms), MusicShareRules.OFFLINE_DETAIL)
    StackedActions(primary = "Try again" to onRetry, secondary = other?.let { "Play on ${it.name} instead" to { onOther(it) } },
        tertiary = "Cancel" to onCancel)
}

@Composable
internal fun MusicRefused(message: String, onBack: () -> Unit, onClose: () -> Unit) {
    MusicSheetHead("Play on a Portal", null)
    MusicNotice("Nothing was played", message)
    SheetActions("Close", onClose, "Back", onBack)
}

/** "Playing on Living Area", one line, the item chip, any partial failure, Open remote and Done. */
@Composable
internal fun MusicDone(pick: MusicPick, screen: MusicScreen.Done, onRemote: () -> Unit, onDone: () -> Unit) {
    val item = pick.item ?: return
    val copy = MusicSendRules.done(item, screen.summary, screen.requested, pick.shuffle, pick.count)
    MusicDoneHead(copy.title, copy.body)
    MusicItemCard(item.thumb, item.title, null)
    (pick.refusal?.let(MusicShareRules::refusedAfterSend) ?: copy.deadLine)?.let {
        Text(it, color = FailedRed, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
    }
    SheetActions("Open remote", onRemote, "Done", onDone)
}

@Composable
private fun StackedActions(primary: Pair<String, () -> Unit>, secondary: Pair<String, () -> Unit>?,
    tertiary: Pair<String, () -> Unit>? = null) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(primary.second, Modifier.fillMaxWidth().height(56.dp)) { Text(primary.first) }
        secondary?.let { FilledTonalButton(it.second, Modifier.fillMaxWidth().height(56.dp)) { Text(it.first) } }
        tertiary?.let { TextButton(it.second, Modifier.fillMaxWidth()) { Text(it.first) } }
    }
}
