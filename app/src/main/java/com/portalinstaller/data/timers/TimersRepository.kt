/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.timers

import com.portalinstaller.data.companion.CompanionClient
import com.portalinstaller.data.companion.CompanionException
import com.portalinstaller.data.companion.CompanionSession
import com.portalinstaller.data.companion.CompanionSessionStore
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

data class TimerNotice(val seq: Long, val text: String)

data class TimersState(
    /** Every timer the Portals reported (live and recently ended); show [TimerMerge.display]. */
    val timers: List<PhoneTimer> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    /** Timer ids (or [TimersRepository.START]) with a command on the way. */
    val pending: Set<String> = emptySet(),
    val portals: Int = 0,
    /** No paired Portal answered: the phone is away from home Wi-Fi or offline. */
    val notOnHomeWifi: Boolean = false,
    /** Portals answered but none has shared timers yet (older Hearthpane). */
    val needsUpdate: Boolean = false,
    val notice: TimerNotice? = null,
)

/**
 * Household timers from the phone (`/remote/timers`). Every Portal holds the same list, so a
 * refresh reads all paired Portals at once and keeps, per timer, the newest version (highest
 * `seq`) — a Portal that missed a message doesn't hide a timer. Commands go to ONE reachable
 * Portal (preferring the one that reported that timer), which relays to the others over the
 * household bus. Nothing runs in the background here; the screen drives [refresh].
 */
class TimersRepository(
    private val client: CompanionClient,
    private val store: CompanionSessionStore,
    private val senderName: String,
    /** Monotonic clock for the local countdown (SystemClock.elapsedRealtime in production). */
    private val clock: () -> Long,
    private val log: (String) -> Unit = {},
) {
    private sealed interface Read {
        data class Ok(val session: CompanionSession, val timers: List<PhoneTimer>) : Read
        object Missing : Read
        object Offline : Read
    }

    private val mutable = MutableStateFlow(TimersState())
    val state: StateFlow<TimersState> = mutable.asStateFlow()
    @Volatile private var sessions: List<CompanionSession> = emptyList()
    @Volatile private var sources: Map<String, String> = emptyMap()
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
        sources = newestSources(ok)
        if (ok.isNotEmpty() && relay !in ok.map { it.session.device.id }) relay = ok.first().session.device.id
        mutable.update { s ->
            s.copy(timers = TimerMerge.merge(ok.map { it.timers }), loaded = true, loading = false, portals = saved.size,
                notOnHomeWifi = saved.isNotEmpty() && reads.all { it is Read.Offline },
                needsUpdate = ok.isEmpty() && reads.any { it is Read.Missing })
        }
    }

    /** True when a Portal accepted it; the new state is already in [state]. */
    suspend fun command(command: TimerCommand): Boolean {
        val key = targetId(command)
        mutable.update { it.copy(pending = it.pending + key) }
        try {
            for (session in order(sourceFor(command))) {
                val answer = attempt(session, command)
                when (answer) {
                    is Answer.Done -> {
                        relay = session.device.id
                        answer.timer?.let { t -> mutable.update { it.copy(timers = TimerMerge.apply(it.timers, t)) } }
                        return true
                    }
                    is Answer.Refused -> { mutable.update { it.copy(notice = notice(answer.text)) }; refresh(); return false }
                    Answer.Unreachable -> Unit
                }
            }
            mutable.update { it.copy(notice = notice(OFFLINE)) }
            return false
        } finally {
            mutable.update { it.copy(pending = it.pending - key) }
        }
    }

    private sealed interface Answer {
        data class Done(val timer: PhoneTimer?) : Answer
        data class Refused(val text: String) : Answer
        object Unreachable : Answer
    }

    private suspend fun attempt(session: CompanionSession, command: TimerCommand): Answer = try {
        val json = withTimeout(ANSWER_MS) { client.post(session, ROUTE, command.body(senderName)) }
        Answer.Done(PhoneTimer.parse(json.optJSONObject("timer"), clock()))
    } catch (e: TimeoutCancellationException) { Answer.Unreachable }
    catch (e: CancellationException) { throw e }
    catch (e: CompanionException) {
        when {
            e.error.status == NOT_FOUND && e.error.code != "unknown_timer" -> Answer.Unreachable // older Hearthpane
            else -> Answer.Refused(TimerCopy.refused(e.error.code))
        }
    }
    catch (_: IOException) { Answer.Unreachable }

    private suspend fun read(session: CompanionSession): Read = try {
        val json: JSONObject = withTimeout(ANSWER_MS) { client.get(session, ROUTE) }
        Read.Ok(session, PhoneTimer.parseList(json.optJSONArray("timers"), clock()))
    } catch (e: TimeoutCancellationException) { Read.Offline }
    catch (e: CancellationException) { throw e }
    catch (e: CompanionException) { if (e.error.status == NOT_FOUND) Read.Missing else Read.Offline }
    catch (_: IOException) { Read.Offline }

    /** Which Portal reported the newest version of each timer (commands go there first). */
    private fun newestSources(reads: List<Read.Ok>): Map<String, String> {
        val best = HashMap<String, Pair<PhoneTimer, String>>()
        reads.forEach { r ->
            r.timers.forEach { t ->
                val current = best[t.id]
                if (current == null || TimerMerge.newer(t, current.first) === t) best[t.id] = t to r.session.device.id
            }
        }
        return best.mapValues { it.value.second }
    }

    private fun targetId(command: TimerCommand): String = when (command) {
        is TimerCommand.Start -> START
        is TimerCommand.Pause -> command.id
        is TimerCommand.Resume -> command.id
        is TimerCommand.AddMinute -> command.id
        is TimerCommand.Cancel -> command.id
        is TimerCommand.Stop -> command.id
    }

    private fun sourceFor(command: TimerCommand): String? = sources[targetId(command)]

    /** The timer's own Portal first, then the last one that answered, then the rest. */
    private fun order(preferred: String?): List<CompanionSession> {
        val byId = sessions.associateBy { it.device.id }
        return (listOfNotNull(preferred, relay) + sessions.map { it.device.id }).distinct().mapNotNull { byId[it] }
    }

    private fun notice(text: String) = TimerNotice(++noticeSeq, text)

    companion object {
        const val ROUTE = "timers"
        const val START = "start"
        const val ANSWER_MS = 4_000L
        private const val NOT_FOUND = 404
        const val OFFLINE = "Couldn’t reach your Portals. Check that this phone is on home Wi-Fi."
    }
}

/** Phone copy for Portal refusals. */
object TimerCopy {
    fun refused(code: String): String = when (code) {
        "unknown_timer" -> "That timer already ended."
        "invalid_state" -> "That timer already changed in another room."
        "too_many_timers" -> "That’s a lot of timers. Cancel one first."
        "invalid_duration" -> "Choose a time between 1 second and 24 hours."
        "invalid_name" -> "Use a shorter name."
        "unauthorized" -> "Pair this phone with the Portal again."
        else -> "The Portal couldn’t do that. Try again."
    }

    fun doneTitle(name: String) = "$name is done"
    fun doneText(room: String) = "Timer done · $room"
}
