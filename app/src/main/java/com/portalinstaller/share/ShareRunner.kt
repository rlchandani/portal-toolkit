/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.share

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface ShareEvents {
    /** Some files are waiting for a room the phone can't reach; schedule a later attempt. */
    fun held(job: ShareJob)
    /** Every file reached a final state (confirmed, failed, not confirmed or stopped). */
    fun finished(job: ShareJob)
}

/**
 * Runs share jobs: one FIFO lane per room, files one at a time, every transition published as a
 * new immutable [ShareJob]. Persisted on each state change except progress ticks.
 */
class ShareRunner(
    private val transport: ShareTransport,
    private val scope: CoroutineScope,
    private val persist: (List<ShareJob>) -> Unit,
    private val events: ShareEvents,
    initial: List<ShareJob> = emptyList(),
    private val maxJobs: Int = 20,
) {
    private val state = MutableStateFlow(initial.map { it.recoverAfterRestart() })
    val jobs: StateFlow<List<ShareJob>> = state.asStateFlow()
    private val lanes = mutableMapOf<String, Mutex>()

    fun job(id: String): ShareJob? = state.value.firstOrNull { it.id == id }

    fun start(job: ShareJob): Job {
        state.update { (listOf(job) + it.filterNot { old -> old.id == job.id }).take(maxJobs) }
        save()
        return run(job.id)
    }

    fun retryFailed(id: String): Job { change(id) { it.retryFailed() }; save(); return run(id) }

    fun stop(id: String) { change(id) { it.stop() }; save(); finishIfDone(id) }

    fun cancelWaiting(id: String) { change(id) { it.cancelWaiting() }; save() }

    fun dismiss(id: String) { state.update { list -> list.filterNot { it.id == id } }; save() }

    fun markNotified(id: String) { change(id) { it.copy(notified = true) }; save() }

    fun update(id: String, transform: (ShareJob) -> ShareJob) { change(id, transform); save() }

    /** Re-tries rooms that were unreachable. Called by the network-gated job and on app start. */
    fun resumeHeld(): Job {
        val ids = state.value.filter { !it.cancelled && it.hasHeld() }.map { it.id }
        val runs = ids.map { run(it) }
        return scope.launch { runs.joinAll() }
    }

    /** Asks each Portal about files that were sent but never acknowledged. */
    fun checkAgain(id: String): Job = scope.launch {
        val job = job(id) ?: return@launch
        job.cells().filter { it.third.status == ItemStatus.UNCONFIRMED }.forEach { (room, item, s) ->
            val result = transport.check(room.id, job, item, s)
            change(id) { it.with(room.id, item.id, result) }
        }
        save(); finishIfDone(id)
    }

    private fun run(id: String): Job = scope.launch {
        val job = job(id) ?: return@launch
        job.rooms.map { room -> launch { runRoom(id, room) } }.joinAll()
        val after = job(id) ?: return@launch
        if (after.hasHeld() && !after.cancelled) events.held(after)
        finishIfDone(id)
    }

    private suspend fun runRoom(id: String, room: ShareRoom) {
        val lane = synchronized(lanes) { lanes.getOrPut(room.id) { Mutex() } }
        lane.withLock {
            val job = job(id) ?: return
            if (job.cancelled) return
            change(id) { it.release(room.id) }
            if (job(id)?.pending(room.id).isNullOrEmpty()) return
            when (val reach = transport.reach(room.id, job.kind)) {
                is Reach.Held -> { change(id) { it.hold(room.id, reach.reason) }; save(); return }
                is Reach.Refused -> {
                    change(id) { j -> j.pending(room.id).fold(j) { acc, item ->
                        acc.with(room.id, item.id, ItemState(ItemStatus.FAILED, reason = reach.reason)) } }
                    save(); return
                }
                Reach.Ready -> Unit
            }
            for (item in job(id)?.pending(room.id).orEmpty()) {
                val current = job(id) ?: return
                if (current.cancelled || current.state(room.id, item.id).status != ItemStatus.WAITING) continue
                change(id) { it.with(room.id, item.id, ItemState(ItemStatus.SENDING)) }
                val result = if (current.kind == ShareKind.PHOTOS) transport.photo(room.id, current, item) { bytes ->
                    val status = if (bytes >= item.bytes) ItemStatus.CONFIRMING else ItemStatus.SENDING
                    change(id) { it.with(room.id, item.id, ItemState(status, bytes)) }
                } else transport.message(room.id, current)
                change(id) { it.with(room.id, item.id, result) }
                save()
            }
        }
    }

    private fun finishIfDone(id: String) {
        val job = job(id) ?: return
        if (job.notified || !job.summary().done || job.cancelled) return
        change(id) { it.copy(notified = true) }
        save()
        events.finished(job(id) ?: return)
    }

    private fun change(id: String, transform: (ShareJob) -> ShareJob) =
        state.update { list -> list.map { if (it.id == id) transform(it) else it } }

    private fun save() = persist(state.value)
}
