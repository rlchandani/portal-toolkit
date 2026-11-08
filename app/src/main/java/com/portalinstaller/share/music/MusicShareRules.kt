/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.share.music

import com.portalinstaller.share.ShareRoom

/** The thing being shared, as the sheet shows it. */
data class MusicItem(
    val kind: Kind,
    val id: String,
    val title: String,
    val artist: String,
    val thumb: String?,
    /** Picked from a mix with "Play the song only". */
    val fromMix: Boolean = false,
) {
    enum class Kind(val wire: String) { SONG("video"), PLAYLIST("playlist") }
}

/** A room in the picker with its live status. */
data class MusicTarget(val room: ShareRoom, val status: RoomStatus?)

/** Copy and defaults from the approved prototype; pure so every sentence is unit-tested. */
object MusicShareRules {
    /** Busy room → Play next (never interrupt by default); idle → Play now. Remembered: room only. */
    fun defaultAction(targets: List<MusicTarget>): MusicAction =
        if (targets.any { it.status?.busy == true }) MusicAction.NEXT else MusicAction.NOW

    fun roomSubtitle(status: RoomStatus?): String = when (status?.state) {
        null -> "Checking…"
        RoomState.IDLE -> "Nothing playing"
        RoomState.PLAYING -> status.nowPlaying?.let { "Playing “$it”" } ?: "Playing music"
        RoomState.QUIET -> status.quietUntil?.let { "Quiet hours until $it" } ?: "Quiet hours"
        RoomState.ROUTINE -> "Busy with a routine or call"
        RoomState.OFFLINE -> "Not answering"
        RoomState.UNSUPPORTED -> "Update Hearthpane to play shared music"
    }

    fun hint(action: MusicAction, targets: List<MusicTarget>): String {
        val busy = targets.firstOrNull { it.status?.busy == true }
        if (busy != null) {
            val name = busy.room.name
            val cur = "“${busy.status?.nowPlaying ?: "the current song"}”"
            return when (action) {
                MusicAction.NOW -> "$name is playing $cur. Play now jumps in, and $cur carries on after."
                MusicAction.NEXT -> "Plays on $name after $cur."
                MusicAction.END -> {
                    val n = (busy.status?.upNext ?: 0) + 1
                    "Joins the end of $name's queue, after $n ${if (n == 1) "song" else "songs"}."
                }
            }
        }
        val single = targets.singleOrNull()
        if (single?.status?.state == RoomState.OFFLINE) return "${single.room.name} isn't answering right now. You can still try."
        if (single?.status?.state == RoomState.QUIET) {
            return "${single.room.name} is in quiet hours${single.status.quietUntil?.let { " until $it" } ?: ""}."
        }
        return if (targets.size > 1) "Nothing is playing there. Both Portals wake and start right away."
        else "Nothing is playing there. ${single?.room?.name ?: "The Portal"} wakes and starts right away."
    }

    fun sendLabel(action: MusicAction, rooms: List<ShareRoom>): String {
        val both = rooms.size > 1
        val name = rooms.firstOrNull()?.name ?: "the Portal"
        val where = if (both) "in both rooms" else "on $name"
        return when (action) {
            MusicAction.NOW -> "Play $where"
            MusicAction.NEXT -> "Play next $where"
            MusicAction.END -> if (both) "Add in both rooms" else "Add to $name"
        }
    }

    fun sendingLabel(rooms: List<ShareRoom>): String =
        "Waking ${if (rooms.size > 1) "both rooms" else rooms.firstOrNull()?.name ?: "the Portal"}…"

    fun sheetSubtitle(item: MusicItem): String = when {
        item.kind == MusicItem.Kind.PLAYLIST -> "Playlist from YouTube Music"
        item.fromMix -> "From a YouTube Music mix"
        else -> "Song from YouTube Music"
    }

    fun cardSubtitle(item: MusicItem, count: Int?): String = when (item.kind) {
        MusicItem.Kind.PLAYLIST -> if (count != null) "Playlist · $count songs" else "Playlist"
        MusicItem.Kind.SONG -> item.artist
    }

    fun quietQuestion(rooms: List<ShareRoom>): String =
        "${rooms.joinToString(" and ") { it.name }} ${if (rooms.size > 1) "are" else "is"} in quiet hours. Play anyway?"

    fun quietDetail(until: String?): String =
        "${until?.let { "Quiet hours end at $it. " } ?: ""}Someone may be asleep. If you play anyway, it starts at low volume."

    fun offlineTitle(rooms: List<ShareRoom>): String =
        if (rooms.size > 1) "Neither Portal is answering" else "${rooms.firstOrNull()?.name ?: "The Portal"} isn't answering"

    const val OFFLINE_DETAIL = "Nothing was played. Check that it's switched on and connected to Wi-Fi, then try again."

    fun cantTitle(reason: Unplayable): String = when (reason) {
        Unplayable.EMBED -> "This video can't play outside YouTube"
        Unplayable.PRIVATE -> "This video is private or was removed"
        Unplayable.AGE -> "This video is age-restricted"
    }

    fun cantDetail(reason: Unplayable): String = when (reason) {
        Unplayable.EMBED -> "The artist doesn't allow playing this outside YouTube, so a Portal can’t play it. Nothing was sent."
        Unplayable.PRIVATE -> "Only the person who uploaded it can watch it, or it's gone, so a Portal can’t play it. Nothing was sent."
        Unplayable.AGE -> "Age-restricted videos only play in the YouTube app, so a Portal can’t play it. Nothing was sent."
    }

    /** The Portal's player refused it after Send (error 101/150 or age-restricted). */
    fun refusedAfterSend(reason: String): String = "The Portal couldn't play it: $reason. It was skipped."

    const val MIX_TITLE = "Mixes can't play on a Portal"
    const val MIX_DETAIL = "YouTube Music makes mixes for each listener inside its own app, so there's nothing a Portal can open."
    const val MIX_SEED = " You can play the song this mix started from."
    const val SEND_AS_LINK = "Send as a link instead"
}
