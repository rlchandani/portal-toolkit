/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.share.music

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.portalinstaller.share.ShareRoom
import com.portalinstaller.share.ShareRooms
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Drives "Play on a Portal" for one shared link. State is two immutable values ([screen], [pick])
 * replaced on the main thread; the sheet only renders them and calls back.
 */
class MusicShareController(
    private val link: MusicLink,
    private val subject: String?,
    private val scope: CoroutineScope,
    private val client: MusicShareClient,
    private val rooms: suspend () -> List<ShareRoom>,
    private val checker: MusicLinkChecker,
    private val from: () -> String,
    private val shareId: String,
    private val rememberRoom: (String) -> Unit,
) {
    var screen by mutableStateOf<MusicScreen>(MusicScreen.Checking)
        private set
    var pick by mutableStateOf(MusicPick(isMusic = link.isMusic))
        private set

    fun load(lastUsed: String?) {
        scope.launch {
            val options = ShareRooms.options(rooms())
            pick = pick.withRooms(options, lastUsed)
            options.flatMap { it.rooms }.distinctBy { it.id }.forEach { room ->
                launch { val status = client.status(room.id); pick = pick.withStatus(room.id, status) }
            }
        }
        scope.launch { screen = firstScreen() }
    }

    fun update(next: MusicPick) { pick = next }

    fun show(next: MusicScreen) { screen = next }

    fun songOnly(mix: MusicScreen.Mix) {
        mix.mix.seed()?.let { seed -> pick = pick.copy(item = MusicPick.itemOf(seed, mix.seed, null, fromMix = true)) }
        screen = MusicScreen.Pick
    }

    /** Asks first when the status call says a chosen room is in quiet hours. */
    fun startSend() {
        val quiet = pick.quietRooms
        if (quiet.isEmpty()) send(pick.rooms, playAnyway = false)
        else screen = MusicScreen.AskQuiet(quiet, quiet.firstNotNullOfOrNull { pick.statuses[it.id]?.quietUntil })
    }

    fun quietNo(ask: MusicScreen.AskQuiet) {
        screen = if (ask.played.isEmpty()) MusicScreen.Pick
        else MusicScreen.Done(MusicSendSummary.Done(ask.played, emptyList(), emptyList()), pick.action)
    }

    fun quietYes(ask: MusicScreen.AskQuiet) =
        send(if (ask.played.isEmpty()) pick.rooms else ask.rooms, playAnyway = true, earlier = ask.played)

    fun retry(rooms: List<ShareRoom>) = send(rooms, playAnyway = false)

    fun playElsewhere(room: ShareRoom) {
        pick = pick.select(room.id)
        startSend()
    }

    /** A single room that isn't one of [dead] and didn't report itself offline. */
    fun otherRoom(dead: List<ShareRoom>): ShareRoom? =
        pick.options.orEmpty().mapNotNull { it.rooms.singleOrNull() }
            .firstOrNull { room -> dead.none { it.id == room.id } && pick.statuses[room.id]?.state != RoomState.OFFLINE }

    private fun send(targets: List<ShareRoom>, playAnyway: Boolean, earlier: List<Pair<ShareRoom, SendResult.Played>> = emptyList()) {
        val request = pick.request(from(), shareId, playAnyway) ?: return
        pick.selected?.let(rememberRoom)
        screen = MusicScreen.Sending(targets)
        scope.launch {
            val results = MusicSendRules.sendAll(targets) { client.send(it.id, request) }
            screen = when (val summary = MusicSendRules.summarize(earlier + results)) {
                is MusicSendSummary.Done -> MusicScreen.Done(summary, request.action).also { followUp(summary.played.map { it.first }) }
                is MusicSendSummary.AskQuiet -> MusicScreen.AskQuiet(summary.rooms, summary.until, summary.played)
                is MusicSendSummary.NoAnswer -> MusicScreen.NoAnswer(summary.rooms)
                is MusicSendSummary.Refused -> MusicScreen.Refused(summary.message)
            }
        }
    }

    /** Playlist size, or the player's refusal (101/150/age), while the confirmation is open. */
    private fun followUp(played: List<ShareRoom>) = scope.launch {
        repeat(FOLLOW_UP_TRIES) {
            delay(FOLLOW_UP_MS)
            played.forEach { room ->
                val news = client.followUp(room.id, shareId) ?: return@forEach
                pick = pick.copy(count = news.count?.takeIf { it > 0 } ?: pick.count, refusal = news.skipped ?: pick.refusal)
            }
        }
    }

    /** Checks the link before anything can be sent. */
    private suspend fun firstScreen(): MusicScreen {
        if (link is MusicLink.Mix) {
            val meta = link.seed()?.let { (checker.check(it) as? LinkCheck.Ok)?.meta }
            return MusicScreen.Mix(link, meta)
        }
        return when (val check = checker.check(link)) {
            is LinkCheck.Blocked -> {
                val item = MusicPick.itemOf(link, null, subject)
                MusicScreen.Cant(check.reason, item?.title ?: "YouTube video", item?.artist.orEmpty(), item?.thumb)
            }
            is LinkCheck.Ok -> withItem(MusicPick.itemOf(link, check.meta, subject))
            LinkCheck.Unknown -> withItem(MusicPick.itemOf(link, null, subject))
        }
    }

    private fun withItem(item: MusicItem?): MusicScreen {
        item?.let { pick = pick.copy(item = it) }
        return MusicScreen.Pick
    }

    private companion object {
        const val FOLLOW_UP_TRIES = 4
        const val FOLLOW_UP_MS = 2_500L
    }
}
