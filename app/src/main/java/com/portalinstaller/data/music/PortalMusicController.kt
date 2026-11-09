/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.music

import com.portalinstaller.data.companion.CompanionSession
import com.portalinstaller.share.RoomOption
import com.portalinstaller.share.ShareRoom
import com.portalinstaller.share.ShareRooms
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A one-off line for the page's snackbar; [seq] grows so each shows once. */
internal data class MusicNotice(val text: String, val seq: Int)

/** What waits on the quiet-hours question. */
internal sealed interface QuietAction {
    data class Command(val command: MusicCommand, val roomIds: List<String>) : QuietAction
    data class Volume(val roomId: String, val change: VolumeChange) : QuietAction
}

/** "It's quiet hours in …": [quietRoomIds] are the rooms that asked. */
internal data class QuietAsk(val message: String, val quietRoomIds: List<String>, val action: QuietAction)

/** The Music page's whole state, replaced (never mutated) on every change. */
internal data class MusicPageState(
    /** Null while the paired Portals load. */
    val options: List<RoomOption>? = null,
    val selected: String? = null,
    val rooms: Map<String, RoomMusic> = emptyMap(),
    /** Rooms with a command in flight: their controls wait. */
    val pending: Set<String> = emptySet(),
    val ask: QuietAsk? = null,
    val notice: MusicNotice? = null,
) {
    val option: RoomOption? get() = options?.firstOrNull { it.key == selected }
    val targets: List<ShareRoom> get() = option?.rooms.orEmpty()
}

/**
 * Drives the Music page for the chosen paired Portal(s): reads each one's YouTube player, sends
 * transport/queue commands and volume changes, and asks first during quiet hours (mirroring the
 * share sheet's "Play anyway, quietly"). The screen calls [poll] only while it is visible.
 */
internal class PortalMusicController(
    private val music: PortalMusicClient,
    private val sessions: suspend () -> List<CompanionSession>,
    private val rememberRoom: (String) -> Unit = {},
    private val settleMs: Long = SETTLE_MS,
) {
    private val mutable = MutableStateFlow(MusicPageState())
    val state: StateFlow<MusicPageState> = mutable.asStateFlow()
    @Volatile private var paired: Map<String, CompanionSession> = emptyMap()

    suspend fun load(lastUsed: String?) {
        val list = try { sessions() } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
        paired = list.associateBy { it.device.id }
        val options = ShareRooms.options(list.map { ShareRoom(it.device.id, it.device.name) })
        mutable.update { s ->
            s.copy(options = options, selected = ShareRooms.defaultKey(options, s.selected ?: lastUsed),
                rooms = list.associate { it.device.id to (s.rooms[it.device.id] ?: RoomMusic.Loading) })
        }
        refresh(list.map { it.device.id })
    }

    /** Re-reads the selected rooms. */
    suspend fun poll() = refresh(state.value.targets.map { it.id })

    fun select(key: String) {
        if (state.value.options?.none { it.key == key } != false) return
        mutable.update { it.copy(selected = key) }
        rememberRoom(key)
    }

    fun name(roomId: String): String = paired[roomId]?.device?.name ?: "the Portal"

    /** The paired session for [roomId], shared with the page's search and history. */
    fun session(roomId: String): CompanionSession? = paired[roomId]

    /** Sends [command] to [roomIds]; asks first when one is in quiet hours and it would start sound. */
    suspend fun command(command: MusicCommand, roomIds: List<String>, playAnyway: Boolean = false) {
        val ids = roomIds.filter { paired.containsKey(it) && it !in state.value.pending }
        if (ids.isEmpty()) return
        if (command.startsPlayback && !playAnyway) {
            val quiet = ids.filter { (state.value.rooms[it] as? RoomMusic.Ready)?.player?.quiet == true }
            if (quiet.isNotEmpty()) return askPlay(command, ids, quiet, untilOf(quiet))
        }
        setPending(ids, true)
        val results = coroutineScope {
            ids.map { id -> async { id to music.command(paired.getValue(id), command, playAnyway) } }.awaitAll()
        }
        setPending(ids, false)
        val quiet = results.filter { it.second is CommandResult.Quiet }.map { it.first }
        results.forEach { (id, result) -> report(id, command, result) }
        if (quiet.isNotEmpty()) askPlay(command, quiet, quiet,
            results.firstNotNullOfOrNull { (it.second as? CommandResult.Quiet)?.until })
        settleThenRefresh(ids)
    }

    /** Changes [roomId]'s speaker volume; turning it up in quiet hours asks first. */
    suspend fun volume(roomId: String, change: VolumeChange, confirmed: Boolean = false) {
        val session = paired[roomId] ?: return
        val room = state.value.rooms[roomId]
        val player = (room as? RoomMusic.Ready)?.player
        if (!confirmed && player?.quiet == true && change.raises(room.volume)) {
            mutable.update { it.copy(ask = QuietAsk(MusicCopy.quietAskVolume(name(roomId), player.quietUntil),
                listOf(roomId), QuietAction.Volume(roomId, change))) }
            return
        }
        mutable.update { s -> s.copy(rooms = s.rooms + (roomId to expected(s.rooms[roomId], change))) }
        music.volume(session, change)?.let(::notify)
        refresh(listOf(roomId))
    }

    /** Closing the quiet-hours question sends nothing anywhere. */
    fun dismissQuiet() = mutable.update { it.copy(ask = null) }

    /** The quiet-hours answer: yes plays (quietly) or turns up; no plays only in the other rooms. */
    suspend fun answerQuiet(yes: Boolean) {
        val ask = state.value.ask ?: return
        mutable.update { it.copy(ask = null) }
        when (val action = ask.action) {
            is QuietAction.Command ->
                if (yes) command(action.command, action.roomIds, playAnyway = true)
                else (action.roomIds - ask.quietRoomIds.toSet()).takeIf { it.isNotEmpty() }
                    ?.let { command(action.command, it, playAnyway = false) }
            is QuietAction.Volume -> if (yes) volume(action.roomId, action.change, confirmed = true)
        }
    }

    private suspend fun refresh(ids: List<String>) = coroutineScope {
        ids.distinct().forEach { id ->
            val session = paired[id] ?: return@forEach
            launch {
                val read = music.read(session)
                mutable.update { it.copy(rooms = it.rooms + (id to read)) }
            }
        }
    }

    /** The Portal applies commands on its main thread after answering, so read back a moment later. */
    private suspend fun settleThenRefresh(ids: List<String>) {
        delay(settleMs)
        refresh(ids)
    }

    private fun report(roomId: String, command: MusicCommand, result: CommandResult) = when (result) {
        CommandResult.Done -> if (command is MusicCommand.QueueTrack) notify(MusicCopy.queued(name(roomId), command.next)) else Unit
        is CommandResult.Quiet -> Unit
        CommandResult.Opening -> notify(MusicCopy.opening(name(roomId)))
        is CommandResult.Declined -> notify(result.reason)
        is CommandResult.Failed -> notify(result.message)
    }

    private fun askPlay(command: MusicCommand, ids: List<String>, quiet: List<String>, until: String?) {
        val names = ShareRooms.destination(quiet.map { ShareRoom(it, name(it)) })
        mutable.update { it.copy(ask = QuietAsk(MusicCopy.quietAskPlay(names, until), quiet, QuietAction.Command(command, ids))) }
    }

    private fun untilOf(ids: List<String>): String? =
        ids.firstNotNullOfOrNull { (state.value.rooms[it] as? RoomMusic.Ready)?.player?.quietUntil }

    private fun expected(room: RoomMusic?, change: VolumeChange): RoomMusic = when (room) {
        is RoomMusic.Ready -> room.copy(player = room.player.withVolume(change.expected(room.player.volume)))
        is RoomMusic.Legacy -> room.copy(volume = change.expected(room.volume))
        else -> room ?: RoomMusic.Loading
    }

    private fun setPending(ids: List<String>, on: Boolean) =
        mutable.update { it.copy(pending = if (on) it.pending + ids else it.pending - ids.toSet()) }

    private fun notify(text: String) = mutable.update { it.copy(notice = MusicNotice(text, (it.notice?.seq ?: 0) + 1)) }

    companion object {
        /** How often the visible page re-reads the selected rooms. */
        const val POLL_MS = 2_500L
        /** Wait after a command before reading back, so the answer reflects it. */
        const val SETTLE_MS = 600L
    }
}
