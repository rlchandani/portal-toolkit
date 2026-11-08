/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.ota

import com.portalinstaller.data.companion.CompanionSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class RoomUpdate(val id: String, val name: String, val status: RoomOta)

/** A one-off message; [seq] grows so each shows once. */
data class UpdatesNotice(val text: String, val seq: Int)

/** Every paired Portal's Hearthpane update state. Replaced, never mutated. */
data class PortalUpdatesState(
    val rooms: List<RoomUpdate> = emptyList(),
    val pending: Set<String> = emptySet(),
    val notice: UpdatesNotice? = null,
) {
    /** The rooms with something to show, as (room id, line). */
    fun lines(): List<Pair<String, PortalUpdateLine>> = rooms.mapNotNull { room ->
        val ota = (room.status as? RoomOta.Ready)?.ota ?: return@mapNotNull null
        PortalOtaCopy.line(room.name, ota, room.id in pending)?.let { room.id to it }
    }

    val anyBusy: Boolean get() = lines().any { it.second.busy }
}

/** Drives Home's "Hearthpane updates" card: reads each paired Portal, and asks one to update now. */
class PortalUpdatesController(
    private val ota: PortalOtaClient,
    private val sessions: suspend () -> List<CompanionSession>,
    private val settleMs: Long = SETTLE_MS,
) {
    private val mutable = MutableStateFlow(PortalUpdatesState())
    val state: StateFlow<PortalUpdatesState> = mutable.asStateFlow()
    @Volatile private var paired: Map<String, CompanionSession> = emptyMap()

    suspend fun load() {
        val list = try { sessions() } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
        paired = list.associateBy { it.device.id }
        mutable.update { s ->
            val known = s.rooms.associateBy { it.id }
            s.copy(rooms = list.map { RoomUpdate(it.device.id, it.device.name, known[it.device.id]?.status ?: RoomOta.Loading) })
        }
        refresh(list.map { it.device.id })
    }

    /** Re-reads rooms whose update is in progress. */
    suspend fun poll() = refresh(state.value.lines().filter { it.second.busy }.map { it.first })

    suspend fun install(roomId: String) {
        val session = paired[roomId] ?: return
        if (roomId in state.value.pending) return
        mutable.update { it.copy(pending = it.pending + roomId) }
        try {
            ota.install(session)?.let { message -> notice(message) }
            if (settleMs > 0) delay(settleMs)
            refresh(listOf(roomId))
        } finally {
            mutable.update { it.copy(pending = it.pending - roomId) }
        }
    }

    private suspend fun refresh(ids: List<String>) {
        val targets = ids.mapNotNull { paired[it] }
        if (targets.isEmpty()) return
        val results = coroutineScope { targets.map { s -> async { s.device.id to ota.read(s) } }.awaitAll() }.toMap()
        mutable.update { s -> s.copy(rooms = s.rooms.map { room -> results[room.id]?.let { room.copy(status = it) } ?: room }) }
    }

    private fun notice(text: String) = mutable.update { it.copy(notice = UpdatesNotice(text, (it.notice?.seq ?: 0) + 1)) }

    companion object {
        const val SETTLE_MS = 1_500L
        const val POLL_MS = 5_000L
    }
}
