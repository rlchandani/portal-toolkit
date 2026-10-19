/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.viewmodel

import com.portalinstaller.data.companion.CompanionException
import com.portalinstaller.data.companion.CompanionMusic
import com.portalinstaller.data.companion.CompanionMusicAction
import com.portalinstaller.data.companion.commandBody
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

data class CompanionMusicUiState(
    val deviceKey: String = "",
    val connected: Boolean = false,
    val music: CompanionMusic? = null,
    val fresh: Boolean = false,
    val reading: Boolean = false,
    val sending: Boolean = false,
    val unsupported: Boolean = false,
    val feedback: String? = null,
)

/**
 * One observation owner per paired Portal session. Reads coalesce; commands retire older
 * reads and require the original rendered source/track/queue/group binding. Reconnection,
 * page entry and cancellation only observe state: none can send or retry a command.
 */
class CompanionMusicController(
    private val scope: CoroutineScope,
    private val read: suspend (String) -> JSONObject?,
    private val send: suspend (String, JSONObject) -> JSONObject?,
) {
    private val lock = Any()
    private val mutableState = MutableStateFlow(CompanionMusicUiState())
    val state = mutableState.asStateFlow()
    private var epoch = 0L
    private var readJob: Job? = null
    private var commandJob: Job? = null
    private var confirmationJob: Job? = null
    private data class Awaited(val id: Long, val command: String, val revision: Long, val source: String)
    private var awaited: Awaited? = null

    fun updateSession(deviceKey: String, connected: Boolean) = synchronized(lock) {
        val old = mutableState.value
        if (old.deviceKey == deviceKey && old.connected == connected) return@synchronized
        retire()
        awaited = null
        mutableState.value = CompanionMusicUiState(deviceKey, connected)
    }

    fun stopObserving() = synchronized(lock) {
        val wasPending = mutableState.value.sending || awaited != null
        retire()
        awaited = null
        mutableState.value = mutableState.value.copy(fresh = false, reading = false, sending = false,
            feedback = if (wasPending) "Review the latest state to check the previous change."
                else mutableState.value.feedback)
    }

    private fun retire() {
        epoch++
        readJob?.cancel()
        commandJob?.cancel()
        confirmationJob?.cancel()
        readJob = null
        commandJob = null
        confirmationJob = null
    }

    private fun current(key: String, owner: Long): Boolean =
        mutableState.value.connected && mutableState.value.deviceKey == key && epoch == owner

    fun refresh(): Job = synchronized(lock) {
        val s = mutableState.value
        if (!s.connected || s.sending) return@synchronized completed()
        readJob?.takeIf { it.isActive }?.let { return@synchronized it }
        val owner = epoch
        val key = s.deviceKey
        mutableState.value = s.copy(reading = true)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val response = read(key) ?: return@launch
                val music = CompanionMusic.fromResponse(response)
                synchronized(lock) {
                    if (!current(key, owner)) return@synchronized
                    var feedback = mutableState.value.feedback
                    val pendingResult = awaited
                    if (pendingResult != null && music.pending == null) {
                        val result = music.lastCommand
                        val matches = music.sourceRevision == pendingResult.revision &&
                            music.sourceId == pendingResult.source && result?.requestId == pendingResult.id &&
                            result.command == pendingResult.command
                        feedback = if (matches) when (result!!.outcome) {
                            "confirmed" -> "Change confirmed by the player."
                            "failed" -> "The player could not apply this change."
                            "timed_out" -> "The player did not confirm this change in time."
                            "disconnected" -> "The player disconnected before confirming this change."
                            else -> "Latest state loaded; the previous change was not confirmed."
                        } else "Latest state loaded; the previous change was not confirmed."
                        awaited = null
                    }
                    mutableState.value = mutableState.value.copy(
                        music = music, fresh = true, unsupported = false, feedback = feedback)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                synchronized(lock) {
                    if (current(key, owner)) mutableState.value = mutableState.value.copy(
                        fresh = false,
                        unsupported = error is CompanionException && error.error.status in setOf(404, 405, 501),
                        feedback = "Could not load music. Refresh to try again.")
                }
            } finally {
                val ownJob = currentCoroutineContext()[Job]
                synchronized(lock) {
                    if (readJob === ownJob) readJob = null
                    if (current(key, owner)) mutableState.value = mutableState.value.copy(reading = false)
                }
            }
        }
        readJob = job
        job.start()
        job
    }

    fun command(deviceKey: String, observed: CompanionMusic, action: CompanionMusicAction): Job =
        synchronized(lock) {
            val s = mutableState.value
            if (s.deviceKey != deviceKey || !s.connected || !s.fresh || s.sending) return@synchronized completed()
            val body = observed.commandBody(action)
            val latest = s.music?.commandBody(action)
            if (body == null || latest == null || body.toString() != latest.toString()) {
                mutableState.value = s.copy(fresh = false,
                    feedback = "Music changed. Refresh and review the controls before trying again.")
                return@synchronized refresh()
            }
            // Claim the write before launching, so consecutive taps cannot queue duplicates.
            epoch++
            readJob?.cancel()
            confirmationJob?.cancel()
            readJob = null
            confirmationJob = null
            val owner = epoch
            awaited = null
            mutableState.value = s.copy(fresh = false, reading = false, sending = true,
                feedback = "Sending change…")
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val response = send(deviceKey, body) ?: return@launch
                    synchronized(lock) {
                        if (!current(deviceKey, owner)) return@synchronized
                        when {
                            response.opt("admitted") == true && response.opt("status") == "admitted" -> {
                                val admitted = CompanionMusic.fromResponse(response)
                                val pending = admitted.pending
                                if (action is CompanionMusicAction.SelectPlayer && pending == null &&
                                    admitted.sourceId == action.roomId) {
                                    // Source selection applies synchronously and clears the old
                                    // playback observation; it never creates a pending music command.
                                    mutableState.value = mutableState.value.copy(
                                        music = admitted, fresh = true, unsupported = false,
                                        feedback = "Player selected.")
                                } else {
                                    require(pending != null) { "Missing correlated admission" }
                                    awaited = Awaited(pending.requestId, pending.command,
                                        observed.sourceRevision, observed.sourceId)
                                    mutableState.value = mutableState.value.copy(
                                        feedback = "Accepted. Waiting for the player…")
                                }
                            }
                            response.opt("status") == "already_applied" ->
                                mutableState.value = mutableState.value.copy(
                                    feedback = "Already in that playback state.")
                            else -> throw IllegalArgumentException("Invalid admission response")
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    synchronized(lock) {
                        if (current(deviceKey, owner)) mutableState.value = mutableState.value.copy(
                            fresh = false,
                            feedback = "Could not confirm the change. Review the latest state before trying again.")
                    }
                } finally {
                    val ownJob = currentCoroutineContext()[Job]
                    synchronized(lock) {
                        if (commandJob === ownJob) commandJob = null
                        if (current(deviceKey, owner)) {
                            mutableState.value = mutableState.value.copy(sending = false)
                            if (awaited != null) observeConfirmation(deviceKey, owner) else refresh()
                        }
                    }
                }
            }
            commandJob = job
            job.start()
            job
        }

    /** Short authoritative readback after admission; idle metadata keeps its slower UI cadence. */
    private fun observeConfirmation(key: String, owner: Long) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val finished = withTimeoutOrNull(12_000) {
                    while (synchronized(lock) { current(key, owner) && awaited != null }) {
                        refresh().join() // Shares the same one-GET admission gate as refresh taps.
                        if (!synchronized(lock) { current(key, owner) && awaited != null }) break
                        delay(250)
                    }
                    true
                }
                synchronized(lock) {
                    if (finished == null && current(key, owner) && awaited != null) {
                        epoch++ // Retire a delayed sibling read after the deadline.
                        readJob?.cancel()
                        readJob = null
                        awaited = null
                        mutableState.value = mutableState.value.copy(
                            fresh = false, reading = false,
                            feedback = "The player did not confirm this change in time.")
                    }
                }
            } finally {
                val ownJob = currentCoroutineContext()[Job]
                synchronized(lock) { if (confirmationJob === ownJob) confirmationJob = null }
            }
        }
        confirmationJob = job
        job.start()
    }

    private fun completed(): Job = Job().apply { complete() }
}
