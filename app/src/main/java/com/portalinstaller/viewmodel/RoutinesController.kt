/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.viewmodel

import com.portalinstaller.data.companion.CompanionException
import com.portalinstaller.data.routines.Routine
import com.portalinstaller.data.routines.RoutineCard
import com.portalinstaller.data.routines.RoutineDraft
import com.portalinstaller.data.routines.RoutineKid
import com.portalinstaller.data.routines.RoutineSnapshot
import com.portalinstaller.data.routines.RoutineText
import com.portalinstaller.data.routines.RoutineWire
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

data class RoutineFailure(val title: String, val body: String)

sealed interface RoutineUndo {
    data class Restore(val routine: Routine) : RoutineUndo
    data class Cards(val cards: List<RoutineCard>) : RoutineUndo
}

data class RoutineSnack(val text: String, val ok: Boolean = true, val undo: RoutineUndo? = null, val id: Long = 0)

data class RoutinesUiState(
    val deviceKey: String = "",
    val connected: Boolean = false,
    val snapshot: RoutineSnapshot? = null,
    val loading: Boolean = false,
    val sending: Boolean = false,
    val pendingToggle: String? = null,
    val failure: RoutineFailure? = null,
    val snack: RoutineSnack? = null,
    val unsupported: Boolean = false,
    val stale: Boolean = false,
)

/**
 * Routines on the viewed Portal (`/remote/routines`). A change counts only once the Portal
 * acknowledges it within [ACK_TIMEOUT_MS]; until then the editor keeps the draft. Every write sends
 * the revision this phone last saw, so a stale save is refused and reloaded instead of overwriting.
 * Responses from a previous Portal session never publish.
 */
class RoutinesController(
    private val scope: CoroutineScope,
    private val read: suspend (String) -> JSONObject?,
    private val send: suspend (String, JSONObject) -> JSONObject?,
    private val room: () -> String,
) {
    companion object { const val ACK_TIMEOUT_MS = 5_000L }

    private val lock = Any()
    private val mutable = MutableStateFlow(RoutinesUiState())
    val state = mutable.asStateFlow()
    private var snackSeq = 0L

    fun updateSession(deviceKey: String, connected: Boolean) = synchronized(lock) {
        val s = mutable.value
        if (s.deviceKey == deviceKey && s.connected == connected) return@synchronized
        mutable.value = if (s.deviceKey == deviceKey) s.copy(connected = connected, stale = !connected)
            else RoutinesUiState(deviceKey, connected)
    }

    private fun publish(key: String, change: (RoutinesUiState) -> RoutinesUiState) = synchronized(lock) {
        if (mutable.value.deviceKey == key) mutable.value = change(mutable.value)
    }

    fun clearFailure() = synchronized(lock) { mutable.value = mutable.value.copy(failure = null) }
    fun dismissSnack() = synchronized(lock) { mutable.value = mutable.value.copy(snack = null) }
    fun showSnack(text: String, ok: Boolean = true, undo: RoutineUndo? = null) = synchronized(lock) {
        mutable.value = mutable.value.copy(snack = RoutineSnack(text, ok, undo, ++snackSeq))
    }

    fun refresh(): Job {
        val key = mutable.value.deviceKey
        if (!mutable.value.connected) return scope.launch { }
        publish(key) { it.copy(loading = true) }
        return scope.launch {
            try {
                val json = read(key) ?: return@launch
                val snap = RoutineWire.parseSnapshot(json)
                publish(key) { it.copy(snapshot = snap, unsupported = false, stale = false) }
            } catch (c: CancellationException) { throw c }
            catch (e: Exception) {
                publish(key) { it.copy(stale = true, unsupported = e is CompanionException && e.error.status in setOf(404, 405)) }
            } finally { publish(key) { it.copy(loading = false) } }
        }
    }

    /** Sends [body] (with `expectedRev` added for document edits); null result means failure. */
    private fun write(body: JSONObject, withRev: Boolean, toggle: String? = null,
                      onOk: (RoutineSnapshot?) -> Unit, onFail: (String, RoutineFailure) -> Unit): Job {
        val s = mutable.value
        val key = s.deviceKey
        if (!s.connected) return scope.launch { onFail("phone", failText("phone")) }
        if (withRev) body.put("expectedRev", s.snapshot?.rev ?: 0L)
        publish(key) { it.copy(sending = true, pendingToggle = toggle, failure = null) }
        return scope.launch {
            try {
                val json = withTimeout(ACK_TIMEOUT_MS) { send(key, body) } ?: return@launch
                val snap = json.takeIf { it.has("rev") }?.let(RoutineWire::parseSnapshot)
                publish(key) { it.copy(snapshot = snap ?: it.snapshot) }
                onOk(snap)
            } catch (t: TimeoutCancellationException) { onFail("timeout", failText("timeout")) }
            catch (c: CancellationException) { throw c }
            catch (e: Exception) {
                val code = (e as? CompanionException)?.error?.code ?: "timeout"
                if (code == "stale_revision") refresh()
                onFail(code, failText(code))
            } finally { publish(key) { it.copy(sending = false, pendingToggle = null) } }
        }
    }

    private fun failText(code: String): RoutineFailure {
        val r = room()
        return when (code) {
            "timeout" -> RoutineFailure("Not sent to $r", "$r didn’t answer. Check it’s on and on home Wi-Fi. Your changes are still here.")
            "phone" -> RoutineFailure("Your phone is offline", "Connect to home Wi-Fi to send this to $r. Your changes are still here.")
            "storage_full" -> RoutineFailure("$r couldn’t save it", "Its storage is full. Free up space in Portal Settings › Storage, then try again.")
            "stale_revision" -> RoutineFailure("Changed on $r", "Routines on $r changed since you opened them. Review, then save again. Your changes are still here.")
            "overlap" -> RoutineFailure("Not saved", "It overlaps another routine on $r. Change the time or days.")
            else -> RoutineFailure("Not saved", "$r refused this change ($code). Try again.")
        }
    }

    private fun failed(code: String, f: RoutineFailure) = synchronized(lock) { mutable.value = mutable.value.copy(failure = f) }

    fun save(draft: RoutineDraft, onDone: (Boolean) -> Unit): Job {
        val r = draft.routine
        return write(JSONObject().put("action", "upsert").put("routine", RoutineWire.routineJson(r)), withRev = true,
            onOk = { snap -> snap?.let { showSnack(RoutineText.savedText(it, r, room())) } ?: showSnack("Saved."); onDone(true) },
            onFail = { code, f -> failed(code, f); onDone(false) })
    }

    fun toggle(rid: String): Job {
        val r = mutable.value.snapshot?.routines?.firstOrNull { it.id == rid } ?: return scope.launch { }
        val next = r.copy(enabled = !r.enabled)
        return write(JSONObject().put("action", "upsert").put("routine", RoutineWire.routineJson(next)), withRev = true, toggle = rid,
            onOk = { showSnack("${r.name} is ${if (next.enabled) "on" else "off"}.") },
            onFail = { _, f -> showSnack("${f.title}. ${r.name} is still ${if (r.enabled) "on" else "off"}.", ok = false) })
    }

    fun delete(rid: String, onDone: (Boolean) -> Unit): Job {
        val r = mutable.value.snapshot?.routines?.firstOrNull { it.id == rid } ?: return scope.launch { onDone(false) }
        return write(JSONObject().put("action", "delete").put("id", rid), withRev = true,
            onOk = { showSnack("Deleted ${r.name}", undo = RoutineUndo.Restore(r)); onDone(true) },
            onFail = { _, f -> showSnack("${f.title}. Nothing was deleted.", ok = false); onDone(false) })
    }

    /** Undo for the last snackbar: restore a deleted routine (card undos are handled by the editor). */
    fun undo(): Job {
        val u = mutable.value.snack?.undo as? RoutineUndo.Restore ?: return scope.launch { }
        dismissSnack()
        return write(JSONObject().put("action", "upsert").put("routine", RoutineWire.routineJson(u.routine)), withRev = true,
            onOk = { showSnack("Restored ${u.routine.name}.") }, onFail = { _, f -> showSnack("${f.title}.", ok = false) })
    }

    fun close(rid: String): Job = write(JSONObject().put("action", "close").put("id", rid), withRev = false,
        onOk = { showSnack("Closed on ${room()}."); refresh() },
        onFail = { _, f -> showSnack("${f.title}. Close it on the Portal: press and hold the close button.", ok = false) })

    fun preview(r: Routine): Job = write(JSONObject().put("action", "preview").put("routine", RoutineWire.routineJson(r)), withRev = false,
        onOk = { showSnack("Showing on ${room()} for 90 seconds.") },
        onFail = { code, f -> showSnack(if (code == "busy") "${room()} is running a routine. Try again after it ends." else "${f.title}.", ok = false) })

    fun saveKids(kids: List<RoutineKid>): Job = write(JSONObject().put("action", "kids").put("kids", RoutineWire.kidsJson(kids)), withRev = true,
        onOk = { showSnack("Kids saved.") }, onFail = { _, f -> showSnack("${f.title}.", ok = false) })

    /** Skip-day toggles for a saved routine go through the editor's save; kept for list shortcuts. */
    fun skip(rid: String, iso: String, skip: Boolean): Job = write(JSONObject().put("action", "skip").put("id", rid)
        .put("date", iso).put("skip", skip), withRev = true, onOk = { }, onFail = { _, f -> showSnack("${f.title}.", ok = false) })

}
