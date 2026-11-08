/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.guests

import android.content.Context
import android.util.Log
import com.portalinstaller.data.companion.CompanionClient
import com.portalinstaller.data.companion.CompanionException
import com.portalinstaller.data.companion.CompanionSession
import com.portalinstaller.data.companion.CompanionSessionStore
import com.portalinstaller.data.companion.FileCompanionSessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/** One paired Portal; [snapshot] null when it didn't answer, [outdated] when it has no guest route. */
data class GuestRoom(val id: String, val name: String, val snapshot: GuestSnapshot?, val outdated: Boolean = false) {
    val online: Boolean get() = snapshot != null
}

data class GuestState(
    val rooms: List<GuestRoom> = emptyList(),
    /** null = every paired Portal. */
    val target: String? = null,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
) {
    val targets: List<GuestRoom> get() = if (target == null) rooms else rooms.filter { it.id == target }
    /** The card shown for editing: the chosen Portal's, or the first answering Portal's for "All". */
    val shown: GuestSnapshot? get() = targets.firstNotNullOfOrNull { it.snapshot }
}

/**
 * The Guest card from the phone: reads every paired Portal's `/remote/guests` and writes the card,
 * password and Guest mode to the chosen Portal or all of them. The password goes out only in a
 * save body and is never stored, logged or read back (the Portal answers `hasPassword` only).
 */
class GuestRepository(
    private val client: CompanionClient,
    private val store: CompanionSessionStore,
    private val log: (String) -> Unit = {},
) {
    private val mutable = MutableStateFlow(GuestState())
    val state: StateFlow<GuestState> = mutable.asStateFlow()
    @Volatile private var sessions: Map<String, CompanionSession> = emptyMap()

    suspend fun refresh() {
        mutable.update { it.copy(loading = true, error = null) }
        val saved = try { store.load() } catch (e: CancellationException) { throw e } catch (e: Exception) {
            log("could not read paired Portals: ${e.javaClass.simpleName}")
            mutable.update { it.copy(loading = false, error = "Couldn’t read your paired Portals.") }
            return
        }
        sessions = saved.associateBy { it.device.id }
        val rooms = coroutineScope { saved.map { s -> async { room(s, call(s, null)) } }.awaitAll() }
        mutable.update { s ->
            s.copy(rooms = rooms, loading = false, target = s.target?.takeIf { id -> rooms.any { it.id == id } })
        }
    }

    fun selectTarget(id: String?) = mutable.update { it.copy(target = id, message = null) }
    fun clearMessage() = mutable.update { it.copy(message = null) }

    suspend fun save(card: GuestCard, password: String) = write("Saved", GuestWire.saveBody(card, password))
    suspend fun start(start: GuestStart) = write("Guest mode started", GuestWire.startBody(start))
    suspend fun end() = write("Guest mode ended", GuestWire.endBody())

    private suspend fun write(verb: String, body: JSONObject) {
        val targets = state.value.targets
        if (targets.isEmpty() || state.value.busy) return
        mutable.update { it.copy(busy = true, message = null) }
        val results = coroutineScope {
            targets.map { room -> async { room to sessions[room.id]?.let { call(it, body) } } }.awaitAll()
        }
        val updated = results.associate { (room, reply) -> room.id to (reply ?: Reply.Failed) }
        mutable.update { s ->
            s.copy(busy = false,
                rooms = s.rooms.map { r -> updated[r.id]?.let { reply -> room(r, reply) } ?: r },
                message = GuestCopy.done(verb, names(results) { it is Reply.Ok },
                    names(results) { it is Reply.Failed || it is Reply.Outdated }, names(results) { it is Reply.Rejected }))
        }
    }

    private fun names(results: List<Pair<GuestRoom, Reply?>>, match: (Reply?) -> Boolean) =
        results.filter { match(it.second ?: Reply.Failed) }.map { it.first.name }

    private fun room(session: CompanionSession, reply: Reply): GuestRoom =
        room(GuestRoom(session.device.id, session.device.name.ifBlank { "Portal" }, null), reply)

    private fun room(base: GuestRoom, reply: Reply): GuestRoom = when (reply) {
        is Reply.Ok -> base.copy(snapshot = reply.snapshot, outdated = false)
        Reply.Outdated -> base.copy(snapshot = null, outdated = true)
        Reply.Failed, Reply.Rejected -> base
    }

    private sealed interface Reply {
        data class Ok(val snapshot: GuestSnapshot) : Reply
        data object Outdated : Reply
        data object Failed : Reply
        /** The Portal answered but refused the values (400). */
        data object Rejected : Reply
    }

    /** GET when [body] is null. Failures log only their type: a save body carries the password. */
    private suspend fun call(session: CompanionSession, body: JSONObject?): Reply = try {
        val json = if (body == null) client.get(session, GuestWire.ROUTE) else client.post(session, GuestWire.ROUTE, body)
        GuestWire.parse(json)?.let { Reply.Ok(it) } ?: Reply.Failed
    } catch (e: CancellationException) { throw e }
    catch (e: CompanionException) {
        log("guest request failed: ${e.error.code}")
        when (e.error.status) {
            404, 405 -> Reply.Outdated
            400 -> Reply.Rejected
            else -> Reply.Failed
        }
    } catch (e: Exception) { log("guest request failed: ${e.javaClass.simpleName}"); Reply.Failed }

    companion object {
        private const val TAG = "ToolkitGuests"

        fun create(context: Context): GuestRepository =
            GuestRepository(CompanionClient(), FileCompanionSessionStore.create(context)) { Log.w(TAG, it) }
    }
}
