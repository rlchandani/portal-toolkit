/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.dinner

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.portalinstaller.data.companion.CompanionClient
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

data class DinnerRoom(val id: String, val name: String, val online: Boolean, val quiet: Boolean)

data class DinnerState(
    val rooms: List<DinnerRoom> = emptyList(),
    /** null = Everyone. */
    val target: String? = null,
    val loading: Boolean = false,
    val sending: Boolean = false,
    /** The results sheet; null when closed. [lastResult] backs the "Last" shortcut. */
    val result: DinnerResult? = null,
    val lastResult: DinnerResult? = null,
    /** No paired Portal answered: the phone is away from home Wi-Fi or offline. */
    val notOnHomeWifi: Boolean = false,
    val replyBanner: DinnerReplyEvent? = null,
    val error: String? = null,
) {
    val targets: List<DinnerRoom> get() = if (target == null) rooms else rooms.filter { it.id == target }
}

/**
 * Call everyone from the phone. Rooms are this phone's paired Portals (read through each one's
 * `/remote/dinner`). A send goes to ONE reachable Portal (preferring a target), which delivers
 * locally and relays to the others over the signed household bus; results and replies are read
 * back from that relay while the screen is open (the screen drives [poll]; nothing runs in the
 * background).
 */
class DinnerRepository(
    private val client: CompanionClient,
    private val store: CompanionSessionStore,
    private val senderName: String,
    /** Failure logging (Android Log in production; injectable so JVM tests need no Android stubs). */
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
) {
    private val mutable = MutableStateFlow(DinnerState())
    val state: StateFlow<DinnerState> = mutable.asStateFlow()
    @Volatile private var sessions: Map<String, CompanionSession> = emptyMap()
    @Volatile private var relay: CompanionSession? = null
    @Volatile private var lastMessage: Pair<PhoneMessage, List<String>>? = null

    suspend fun refresh() {
        mutable.update { it.copy(loading = true, error = null) }
        val saved = try { store.load() } catch (e: CancellationException) { throw e } catch (e: Exception) {
            log("could not read paired Portals", e)
            mutable.update { it.copy(loading = false, error = "Couldn’t read your paired Portals.") }
            return
        }
        val rooms = coroutineScope { saved.map { s -> async { s to snapshot(s) } }.awaitAll() }.map { (session, snap) ->
            val self = snap?.optJSONObject("self")
            val id = self?.optString("id")?.takeIf { ID.matches(it) } ?: session.device.id
            Triple(DinnerRoom(id, session.device.name.ifBlank { self?.optString("name").orEmpty().ifBlank { "Portal" } },
                online = snap != null, quiet = self?.optBoolean("quiet") == true), session, snap != null)
        }
        sessions = rooms.associate { it.first.id to it.second }
        val ids = rooms.map { it.first.id }.toSet()
        mutable.update { s -> s.copy(rooms = rooms.map { it.first }, loading = false,
            target = s.target?.takeIf { it in ids }, notOnHomeWifi = rooms.isNotEmpty() && rooms.none { it.third }) }
    }

    fun selectTarget(roomId: String?) = mutable.update { it.copy(target = roomId) }

    fun subtitle(): String = state.value.let { s ->
        DinnerCopy.subtitle(s.targets.map { it.name }, s.targets.filter { it.quiet }.map { it.name }, s.notOnHomeWifi)
    }

    suspend fun send(message: PhoneMessage) {
        val rooms = state.value.targets.map { it.id }
        if (rooms.isEmpty()) return
        lastMessage = message to rooms
        mutable.update { it.copy(sending = true) }
        val body = DinnerRequest.body(message, rooms, senderName)
        val result = viaRelay(rooms) { client.post(it, ROUTE, body) }?.let { DinnerResult.parse(it.optJSONObject("send")) }
        show(result ?: notSent(message, rooms))
    }

    /** Try again for one room. Never-sent calls are sent again; otherwise the relay retries that room. */
    suspend fun retry(roomId: String) {
        val current = state.value.result ?: return
        val pending = lastMessage
        if (current.id == LOCAL_ID && pending != null) { send(pending.first); return }
        val session = relay ?: return
        val reply = request { client.post(session, ROUTE, DinnerRequest.retry(current.id, roomId)) }
        show(reply?.let { DinnerResult.parse(it.optJSONObject("send")) } ?: current)
    }

    /** One read of the relay's results. The screen calls this while the results sheet is open. */
    suspend fun poll() {
        val current = state.value.result ?: state.value.lastResult ?: return
        if (current.id == LOCAL_ID) return
        val session = relay ?: return
        val sends = request { client.get(session, ROUTE) }?.optJSONArray("sends") ?: return
        val match = (0 until sends.length()).mapNotNull { sends.optJSONObject(it) }.firstOrNull { it.optString("id") == current.id }
        val fresh = DinnerResult.parse(match)?.let(::localize) ?: return
        val reply = DinnerCopy.newReplies(current, fresh).lastOrNull()
        mutable.update { s -> s.copy(result = if (s.result?.id == fresh.id) fresh else s.result, lastResult = fresh,
            replyBanner = reply ?: s.replyBanner) }
    }

    fun openLast() = mutable.update { it.copy(result = it.lastResult) }
    fun closeResult() = mutable.update { it.copy(result = null) }
    fun dismissBanner() = mutable.update { it.copy(replyBanner = null) }

    private fun show(result: DinnerResult) {
        val local = localize(result)
        mutable.update { it.copy(sending = false, result = local, lastResult = local, notOnHomeWifi = result.id == LOCAL_ID) }
    }

    /** "No answer" from a Portal the phone can't reach either reads as offline (prototype copy). */
    private fun localize(result: DinnerResult): DinnerResult {
        val offline = state.value.rooms.filterNot { it.online }.map { it.id }.toSet()
        return result.copy(rows = result.rows.map { if (it.status == DinnerRowStatus.NO_ANSWER && it.roomId in offline) it.copy(status = DinnerRowStatus.OFFLINE) else it })
    }

    private fun notSent(message: PhoneMessage, rooms: List<String>): DinnerResult {
        val names = state.value.rooms.associate { it.id to it.name }
        val text = when (message) {
            is PhoneMessage.Preset -> DinnerPresets.all.firstOrNull { it.id == message.id }?.text.orEmpty()
            is PhoneMessage.Custom -> message.text
            PhoneMessage.Ping -> "Ping"
        }
        return DinnerResult(LOCAL_ID, text, false, rooms.map { DinnerResultRow(it, names[it] ?: "Portal", DinnerRowStatus.NOT_SENT, true, null) })
    }

    /** Reachable targets first, then any other reachable Portal; remembers the one that answered. */
    private suspend fun viaRelay(rooms: List<String>, call: suspend (CompanionSession) -> JSONObject): JSONObject? {
        val online = state.value.rooms.filter { it.online }.map { it.id }
        val order = (rooms.filter { it in online } + online + sessions.keys).distinct().mapNotNull { sessions[it] }
        for (session in order) {
            val result = request { call(session) }
            if (result != null) { relay = session; return result }
        }
        return null
    }

    private suspend fun snapshot(session: CompanionSession): JSONObject? = request { client.get(session, ROUTE) }

    private suspend fun request(block: suspend () -> JSONObject): JSONObject? = try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { log("dinner request failed: ${e.javaClass.simpleName}", null); null }

    companion object {
        private const val TAG = "ToolkitDinner"
        const val ROUTE = "dinner"
        private const val LOCAL_ID = "local"
        private val ID = Regex("^[A-Za-z0-9_-]{1,64}$")

        fun create(context: Context): DinnerRepository = DinnerRepository(CompanionClient(),
            FileCompanionSessionStore.create(context), phoneName(context)) { message, error -> Log.w(TAG, message, error) }

        /** Shown on the Portal card ("From Rohit’s Pixel"). */
        fun phoneName(context: Context): String =
            runCatching { Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: Build.MODEL ?: "Phone"
    }
}
