/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.list

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * The phone's household list: every change is applied locally at once, queued on disk, and sent
 * to the Portals; away from home the queue simply waits (README Q1: queue on the phone, no relay).
 * Syncs run on screen open, after each change, on network-available and on pull-to-refresh only;
 * there is no polling loop. State is mutated on [scope]'s thread; I/O runs on [dispatcher].
 */
class HouseholdListController(
    private val scope: CoroutineScope,
    private val portals: suspend () -> List<ListPortal>,
    private val store: HouseholdListStore,
    private val device: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val network: () -> NetworkKind = { NetworkKind.WIFI },
    private val newAid: () -> String = { UUID.randomUUID().toString() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutable = MutableStateFlow(HouseholdListUiState())
    val state: StateFlow<HouseholdListUiState> = mutable.asStateFlow()

    private var snapshot: ListSnapshot? = null
    private var queue = emptyList<PendingAction>()
    private var ghosts = emptyList<ListItem>()
    private var inFlight = emptySet<String>()
    private var waited = emptySet<String>()
    private var reached = emptyList<String>()
    private var unreached = emptyList<String>()
    private var undo: UndoPlan? = null
    private var syncing = false
    private var syncAgain = false
    private var snackSeq = 0L
    private val edits = HouseholdListEdits { op, id, text, order, ids -> PendingAction(newAid(), op, clock(), id, text, order, ids) }

    fun start() = scope.launch {
        val (savedQueue, savedSnapshot) = withContext(dispatcher) { store.loadQueue() to store.loadSnapshot() }
        queue = savedQueue
        snapshot = savedSnapshot
        waited = savedQueue.map { it.aid }.toSet() // left over from last time: they did wait
        publish(loaded = true)
        sync()
    }

    fun sync() {
        if (syncing) { syncAgain = true; return }
        syncing = true
        publish()
        scope.launch {
            do { syncAgain = false; syncOnce() } while (syncAgain)
            syncing = false
            publish()
        }
    }

    fun onNetworkAvailable() = sync()

    fun add(text: String) = perform(edits.add(currentView(), text))
    fun toggle(id: String) = currentView().find(id)?.let { perform(edits.toggle(it)) }
    fun remove(id: String) = currentView().find(id)?.let { perform(edits.remove(it)) }
    fun rename(id: String, text: String) = currentView().find(id)?.let { item -> edits.rename(item, text)?.let(::perform) }
    fun useAlt(id: String) = currentView().find(id)?.let { item -> edits.useAlt(item)?.let(::perform) }
    fun moveToTop(id: String) = edits.moveToTop(currentView(), id)?.let(::perform)
    fun move(id: String, beforeId: String?) = edits.move(currentView(), id, beforeId)?.let(::perform)
    fun clearDone() = edits.clearDone(currentView())?.let(::perform)
    fun dismissNote(id: String) = mutable.update { it.copy(dismissedNotes = it.dismissedNotes + id) }
    fun consumeSnack() = mutable.update { it.copy(snack = null) }

    fun undo() {
        val plan = undo ?: return
        undo = null
        val cancellable = plan.cancel.isNotEmpty() && plan.cancel.all { aid -> aid !in inFlight && queue.any { it.aid == aid } }
        if (cancellable) {
            queue = queue.filterNot { it.aid in plan.cancel }
            persistQueue()
            mutable.update { it.copy(snack = null) }
            publish()
        } else {
            enqueue(plan.inverse.map { it.copy(aid = newAid(), at = clock()) })
            mutable.update { it.copy(snack = null) }
        }
    }

    private fun perform(edit: Edit) {
        if (edit.actions.isEmpty()) { snack(edit.snack, undoable = false); return }
        ghosts = (ghosts.filterNot { g -> edit.ghosts.any { it.id == g.id } } + edit.ghosts).takeLast(MAX_GHOSTS)
        undo = UndoPlan(edit.actions.map { it.aid }.toSet(), edit.inverse)
        if (edit.snack.isNotEmpty()) snack(edit.snack, undoable = edit.inverse.isNotEmpty())
        enqueue(edit.actions)
    }

    private fun enqueue(actions: List<PendingAction>) {
        queue = (queue + actions).takeLast(HouseholdListQueue.MAX_QUEUE)
        persistQueue()
        publish()
        sync()
    }

    private suspend fun syncOnce() {
        val sending = queue
        inFlight = sending.map { it.aid }.toSet()
        val outcome = withContext(dispatcher) {
            HouseholdListSync.run(portals(), sending, device(), snapshot?.revision)
        }
        inFlight = emptySet()
        val gone = outcome.acked + outcome.dropped
        if (gone.isNotEmpty()) { queue = queue.filterNot { it.aid in gone }; persistQueue() }
        outcome.snapshot?.let { fresh -> snapshot = fresh; withContext(dispatcher) { store.saveSnapshot(fresh) } }
        reached = outcome.reached
        unreached = outcome.unreached
        val sentAfterWaiting = outcome.acked.count { it in waited }
        waited = if (outcome.reached.isEmpty()) waited + sending.map { it.aid } else waited - gone
        if (sentAfterWaiting > 0) snack(HouseholdListStatus.sent(sentAfterWaiting, outcome.reached), undoable = false)
        publish()
    }

    private fun persistQueue() {
        val copy = queue
        scope.launch { withContext(dispatcher) { store.saveQueue(copy) } }
    }

    private fun currentView(): ListView = HouseholdListApply.view(snapshot?.items.orEmpty(), queue, ghosts, device())

    private fun snack(text: String, undoable: Boolean) {
        if (!undoable) undo = null
        mutable.update { it.copy(snack = ListSnack(text, undoable, ++snackSeq)) }
    }

    private fun publish(loaded: Boolean? = null) {
        val view = currentView()
        val open = view.open.map { HouseholdListIds.norm(it.text) }.toSet()
        mutable.update { s ->
            s.copy(loaded = loaded ?: s.loaded, view = view, pending = queue.size, syncing = syncing,
                status = HouseholdListStatus.line(reached, unreached, queue.size, network()),
                usedBefore = snapshot?.usedBefore.orEmpty().filterNot { HouseholdListIds.norm(it) in open })
        }
    }

    private companion object { const val MAX_GHOSTS = 100 }
}
