/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.share.music

import com.portalinstaller.share.RoomOption
import com.portalinstaller.share.ShareRoom

/** Which step of "Play on a Portal" is showing. */
sealed interface MusicScreen {
    data object Checking : MusicScreen
    data class Cant(val reason: Unplayable, val title: String, val subtitle: String, val thumb: String?) : MusicScreen
    data class Mix(val mix: MusicLink.Mix, val seed: MusicMeta?) : MusicScreen
    data object Pick : MusicScreen
    data class AskQuiet(val rooms: List<ShareRoom>, val until: String?,
        val played: List<Pair<ShareRoom, SendResult.Played>> = emptyList()) : MusicScreen
    data class Sending(val rooms: List<ShareRoom>) : MusicScreen
    data class NoAnswer(val rooms: List<ShareRoom>) : MusicScreen
    data class Refused(val message: String) : MusicScreen
    data class Done(val summary: MusicSendSummary.Done, val requested: MusicAction) : MusicScreen
}

/**
 * The picker's immutable state. The action follows [MusicShareRules.defaultAction] for the chosen
 * room until the user picks one ([touched]); only the room is remembered between shares.
 */
data class MusicPick(
    val item: MusicItem? = null,
    val isMusic: Boolean = true,
    val options: List<RoomOption>? = null,
    val selected: String? = null,
    val statuses: Map<String, RoomStatus> = emptyMap(),
    val action: MusicAction = MusicAction.NOW,
    val touched: Boolean = false,
    val shuffle: Boolean = false,
    val count: Int? = null,
    /** The Portal's later word: its player refused the shared song (101/150/age). */
    val refusal: String? = null,
) {
    val option: RoomOption? get() = options?.firstOrNull { it.key == selected }
    val rooms: List<ShareRoom> get() = option?.rooms.orEmpty()
    val targets: List<MusicTarget> get() = rooms.map { MusicTarget(it, statuses[it.id]) }
    /** Rooms the status call says are in quiet hours: ask before sending there. */
    val quietRooms: List<ShareRoom> get() = rooms.filter { statuses[it.id]?.state == RoomState.QUIET }

    fun withRooms(loaded: List<RoomOption>, lastUsed: String?): MusicPick =
        copy(options = loaded, selected = loaded.firstOrNull { it.key == lastUsed }?.key ?: loaded.firstOrNull()?.key).defaulted()

    fun withStatus(roomId: String, status: RoomStatus): MusicPick = copy(statuses = statuses + (roomId to status)).defaulted()

    fun select(key: String): MusicPick = copy(selected = key).defaulted()

    fun choose(next: MusicAction): MusicPick = copy(action = next, touched = true)

    private fun defaulted(): MusicPick = if (touched) this else copy(action = MusicShareRules.defaultAction(targets))

    companion object {
        /** The item the sheet shows, from the link, oEmbed's answer and the share's subject line. */
        fun itemOf(link: MusicLink, meta: MusicMeta?, subject: String?, fromMix: Boolean = false): MusicItem? {
            val title = meta?.title ?: subject?.let { MusicOEmbed.clean(it, MusicOEmbed.MAX_TITLE) }?.ifBlank { null }
            return when (link) {
                is MusicLink.Video -> MusicItem(MusicItem.Kind.SONG, link.videoId, title ?: "YouTube video", meta?.author.orEmpty(),
                    meta?.thumbnail ?: "https://i.ytimg.com/vi/${link.videoId}/hqdefault.jpg", fromMix)
                is MusicLink.Playlist -> MusicItem(MusicItem.Kind.PLAYLIST, link.listId, title ?: "Playlist", meta?.author.orEmpty(), meta?.thumbnail)
                is MusicLink.Mix -> null
            }
        }
    }
}

/** The request for [pick] (null until an item and rooms exist). */
fun MusicPick.request(from: String, shareId: String, playAnyway: Boolean): MusicSendRequest? {
    val it = item ?: return null
    return MusicSendRequest(it.kind.wire, it.id, action, shuffle && it.kind == MusicItem.Kind.PLAYLIST, from,
        it.title, it.artist, it.thumb, playAnyway, shareId)
}
