/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.chores

import android.content.Context
import android.util.Log
import com.portalinstaller.data.companion.CompanionClient
import com.portalinstaller.data.companion.CompanionException
import com.portalinstaller.data.companion.CompanionSession
import com.portalinstaller.data.companion.CompanionSessionStore
import com.portalinstaller.data.companion.FileCompanionSessionStore
import java.io.IOException
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

data class ChoreNotice(val seq: Long, val text: String)

data class ChoresUiState(
    /** Every Portal's replica merged (the same rules the Portals use), so no device hides an edit. */
    val board: ChoreState = ChoreState(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    /** A command is on its way. */
    val sending: Boolean = false,
    val portals: Int = 0,
    val notOnHomeWifi: Boolean = false,
    /** Portals answered but none has chores yet (older Hearthpane). */
    val needsUpdate: Boolean = false,
    val notice: ChoreNotice? = null,
)

/**
 * Chores from the phone (`/remote/chores`). A refresh reads every paired Portal and merges their
 * replicas; a command goes to ONE reachable Portal (the last that answered first), which syncs the
 * others over the household bus, and its answer is merged in. The screen drives [refresh].
 */
class ChoresRepository(
    private val client: CompanionClient,
    private val store: CompanionSessionStore,
    private val log: (String) -> Unit = {},
) {
    private sealed interface Read {
        data class Ok(val session: CompanionSession, val state: ChoreState) : Read
        object Missing : Read
        object Offline : Read
    }

    private val mutable = MutableStateFlow(ChoresUiState())
    val state: StateFlow<ChoresUiState> = mutable.asStateFlow()
    @Volatile private var sessions: List<CompanionSession> = emptyList()
    @Volatile private var relay: String? = null
    private var noticeSeq = 0L

    suspend fun refresh() {
        mutable.update { it.copy(loading = true) }
        val saved = try { store.load() } catch (e: CancellationException) { throw e } catch (e: Exception) {
            log("could not read paired Portals: ${e.javaClass.simpleName}")
            mutable.update { it.copy(loading = false, loaded = true, notice = notice("Couldn’t read your paired Portals.")) }
            return
        }
        sessions = saved
        val reads = coroutineScope { saved.map { s -> async { read(s) } }.awaitAll() }
        val ok = reads.filterIsInstance<Read.Ok>()
        if (ok.isNotEmpty() && relay !in ok.map { it.session.device.id }) relay = ok.first().session.device.id
        mutable.update { s ->
            s.copy(board = ok.fold(s.board) { acc, r -> ChoreMerge.merge(acc, r.state) }, loaded = true, loading = false,
                portals = saved.size, notOnHomeWifi = saved.isNotEmpty() && reads.all { it is Read.Offline },
                needsUpdate = ok.isEmpty() && reads.any { it is Read.Missing })
        }
    }

    /** True when a Portal accepted it; its answer is already merged into [state]. */
    suspend fun command(command: ChoreCommand): Boolean {
        mutable.update { it.copy(sending = true) }
        try {
            for (session in order()) {
                when (val answer = attempt(session, command)) {
                    is Answer.Done -> {
                        relay = session.device.id
                        mutable.update { s -> s.copy(board = answer.state?.let { ChoreMerge.merge(s.board, it) } ?: s.board) }
                        return true
                    }
                    is Answer.Refused -> { mutable.update { it.copy(notice = notice(answer.text)) }; refresh(); return false }
                    Answer.Unreachable -> Unit
                }
            }
            mutable.update { it.copy(notice = notice(ChoreCopy.OFFLINE)) }
            return false
        } finally {
            mutable.update { it.copy(sending = false) }
        }
    }

    private sealed interface Answer {
        data class Done(val state: ChoreState?) : Answer
        data class Refused(val text: String) : Answer
        object Unreachable : Answer
    }

    private suspend fun attempt(session: CompanionSession, command: ChoreCommand): Answer = try {
        val json = withTimeout(ANSWER_MS) { client.post(session, ROUTE, command.body()) }
        Answer.Done(parse(json))
    } catch (e: TimeoutCancellationException) { Answer.Unreachable }
    catch (e: CancellationException) { throw e }
    catch (e: CompanionException) {
        if (e.error.status == NOT_FOUND && e.error.code == "not_found") Answer.Unreachable // older Hearthpane
        else Answer.Refused(ChoreCopy.refused(e.error.code))
    }
    catch (_: IOException) { Answer.Unreachable }

    private suspend fun read(session: CompanionSession): Read = try {
        val json = withTimeout(ANSWER_MS) { client.get(session, ROUTE) }
        parse(json)?.let { Read.Ok(session, it) } ?: Read.Missing
    } catch (e: TimeoutCancellationException) { Read.Offline }
    catch (e: CancellationException) { throw e }
    catch (e: CompanionException) { if (e.error.status == NOT_FOUND) Read.Missing else Read.Offline }
    catch (_: IOException) { Read.Offline }

    /** The last Portal that answered, then the rest. */
    private fun order(): List<CompanionSession> {
        val byId = sessions.associateBy { it.device.id }
        return (listOfNotNull(relay) + sessions.map { it.device.id }).distinct().mapNotNull { byId[it] }
    }

    private fun notice(text: String) = ChoreNotice(++noticeSeq, text)

    companion object {
        const val ROUTE = "chores"
        const val ANSWER_MS = 4_000L
        private const val NOT_FOUND = 404
        private const val TAG = "PortalChores"

        internal fun parse(json: JSONObject): ChoreState? = ChoreCodec.decodeState(json.optJSONObject("state"))

        /** Today on this phone's clock (the household shares one time zone). */
        fun today(nowMs: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): Int = ChoreDays.day(nowMs, zone)

        fun create(context: Context) = ChoresRepository(CompanionClient(),
            FileCompanionSessionStore.create(context.applicationContext)) { Log.w(TAG, it) }
    }
}
