/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.reminders

import android.content.Context
import android.util.Log
import com.portalinstaller.data.companion.CompanionClient
import com.portalinstaller.data.companion.CompanionException
import com.portalinstaller.data.companion.CompanionSession
import com.portalinstaller.data.companion.CompanionSessionStore
import com.portalinstaller.data.companion.FileCompanionSessionStore
import java.io.IOException
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

data class ReminderNotice(val seq: Long, val text: String)

data class RemindersUiState(
    /** One Portal's answer (every Portal holds the same replica); null until one answers. */
    val board: ReminderBoard? = null,
    val loaded: Boolean = false,
    val loading: Boolean = false,
    /** A command is on its way. */
    val sending: Boolean = false,
    val portals: Int = 0,
    val notOnHomeWifi: Boolean = false,
    /** Portals answered but none has reminders yet (older Hearthpane). */
    val needsUpdate: Boolean = false,
    val notice: ReminderNotice? = null,
) {
    /** A Portal answered but its household key isn't set up. */
    val notReady: Boolean get() = board?.ready == false
}

/**
 * Reminders from the phone (`/remote/reminders`), the same session transport as chores. Portals
 * sync one replica, so the screen shows ONE Portal's answer (the last that answered, else the
 * first); a command goes to that Portal first. Its people ids are local to it, so a command naming
 * a person never falls over to another Portal.
 */
class RemindersRepository(
    private val client: CompanionClient,
    private val store: CompanionSessionStore,
    private val log: (String) -> Unit = {},
) {
    private sealed interface Read {
        data class Ok(val session: CompanionSession, val board: ReminderBoard) : Read
        object Missing : Read
        object Offline : Read
    }

    private sealed interface Answer {
        data class Done(val board: ReminderBoard?) : Answer
        data class Refused(val text: String) : Answer
        object Unreachable : Answer
    }

    private val mutable = MutableStateFlow(RemindersUiState())
    val state: StateFlow<RemindersUiState> = mutable.asStateFlow()
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
        val chosen = ok.firstOrNull { it.session.device.id == relay } ?: ok.firstOrNull()
        if (chosen != null) relay = chosen.session.device.id
        mutable.update { s ->
            s.copy(board = chosen?.board ?: s.board, loaded = true, loading = false,
                portals = saved.size, notOnHomeWifi = saved.isNotEmpty() && reads.all { it is Read.Offline },
                needsUpdate = ok.isEmpty() && reads.any { it is Read.Missing })
        }
    }

    /** True when a Portal accepted it; its answer is already in [state]. */
    suspend fun command(command: ReminderCommand): Boolean {
        mutable.update { it.copy(sending = true) }
        try {
            val targets = order().let { if (command.namesPerson) it.take(1) else it }
            for (session in targets) {
                when (val answer = attempt(session, command)) {
                    is Answer.Done -> {
                        relay = session.device.id
                        if (answer.board != null) mutable.update { it.copy(board = answer.board) } else refresh()
                        return true
                    }
                    is Answer.Refused -> { mutable.update { it.copy(notice = notice(answer.text)) }; refresh(); return false }
                    Answer.Unreachable -> Unit
                }
            }
            mutable.update { it.copy(notice = notice(ReminderCopy.OFFLINE)) }
            return false
        } finally {
            mutable.update { it.copy(sending = false) }
        }
    }

    private suspend fun attempt(session: CompanionSession, command: ReminderCommand): Answer = try {
        val json = withTimeout(ANSWER_MS) { client.post(session, ROUTE, command.body()) }
        Answer.Done(parse(json))
    } catch (e: TimeoutCancellationException) { Answer.Unreachable }
    catch (e: CancellationException) { throw e }
    catch (e: CompanionException) {
        if (e.error.status == NOT_FOUND && e.error.code == "not_found") Answer.Unreachable // older Hearthpane
        else Answer.Refused(ReminderCopy.refused(e.error.code))
    }
    catch (_: IOException) { Answer.Unreachable }

    private suspend fun read(session: CompanionSession): Read = try {
        val json = withTimeout(ANSWER_MS) { client.get(session, ROUTE) }
        parse(json)?.let { Read.Ok(session, it) } ?: Read.Missing
    } catch (e: TimeoutCancellationException) { Read.Offline }
    catch (e: CancellationException) { throw e }
    catch (e: CompanionException) { if (e.error.status == NOT_FOUND) Read.Missing else Read.Offline }
    catch (_: IOException) { Read.Offline }

    /** The Portal on screen, then the rest. */
    private fun order(): List<CompanionSession> {
        val byId = sessions.associateBy { it.device.id }
        return (listOfNotNull(relay) + sessions.map { it.device.id }).distinct().mapNotNull { byId[it] }
    }

    private fun notice(text: String) = ReminderNotice(++noticeSeq, text)

    companion object {
        const val ROUTE = "reminders"
        const val ANSWER_MS = 4_000L
        private const val NOT_FOUND = 404
        private const val TAG = "PortalReminders"

        internal fun parse(json: JSONObject): ReminderBoard? = ReminderCodec.decode(json)

        fun create(context: Context) = RemindersRepository(CompanionClient(),
            FileCompanionSessionStore.create(context.applicationContext)) { Log.w(TAG, it) }
    }
}
