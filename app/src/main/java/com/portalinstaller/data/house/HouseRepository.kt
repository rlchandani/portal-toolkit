/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.house

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import com.portalinstaller.data.companion.CompanionClient
import com.portalinstaller.data.companion.CompanionException
import com.portalinstaller.data.companion.CompanionSession
import com.portalinstaller.data.companion.CompanionSessionStore
import com.portalinstaller.data.companion.FileCompanionSessionStore
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

@Immutable
data class HouseNotice(val seq: Long, val text: String, val error: Boolean = false)

/** What the phone's Control Center renders; replaced, never mutated. */
@Immutable
data class HouseUiState(
    val snapshot: HouseSnapshot? = null,
    val plan: HousePlan? = null,
    val reach: HouseReach = HouseReach.OK,
    val lastOkAt: Long = 0L,
    /** The Portal that answered last, and whether it isn't the one this phone is viewing. */
    val via: String? = null,
    val failover: Boolean = false,
    val pending: Set<String> = emptySet(),
    /** Alerts dismissed, snoozed or put off on this phone (the Portals keep their own). */
    val hidden: Set<String> = emptySet(),
    val notice: HouseNotice? = null,
)

/** How one control request ended. [Confirm] carries the Portal's one-time ticket for the resend. */
sealed interface HouseActResult {
    data class Done(val label: String) : HouseActResult
    data class Confirm(val confirm: HouseConfirm, val act: HouseAct) : HouseActResult
    data class Failed(val text: String) : HouseActResult
}

/**
 * Control Center from the phone, always through a paired Portal (`/remote/controlcenter`): the
 * Portal holds the Home Assistant token, so the phone never does. The viewed Portal is asked
 * first; when it doesn't answer, the next paired Portal is (it shows the same house). A poll sends
 * the `rev` it holds and gets only what moved. Controls go to the Portal that answered last.
 */
class HouseRepository(
    private val client: CompanionClient,
    private val store: CompanionSessionStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    private val mutable = MutableStateFlow(HouseUiState())
    val state: StateFlow<HouseUiState> = mutable.asStateFlow()
    private val polling = Mutex()
    @Volatile private var current: CompanionSession? = null
    private var noticeSeq = 0L

    private sealed interface Read {
        data class Ok(val session: CompanionSession, val json: JSONObject) : Read
        data class Skip(val reach: HouseReach) : Read
        object Busy : Read
    }

    /** One poll; [want] = "tesla" also loads the week's home charging on the Portal. */
    suspend fun poll(want: String? = null) = polling.withLock {
        val sessions = try { store.load() } catch (e: CancellationException) { throw e } catch (e: Exception) {
            log("could not read paired Portals: ${e.javaClass.simpleName}"); emptyList()
        }
        if (sessions.isEmpty()) { mutable.update { it.copy(reach = HouseReach.NO_PORTAL) }; return@withLock }
        val active = runCatching { store.loadActiveId() }.getOrNull()
        var reach = HouseReach.OFFLINE
        for (session in order(sessions, active)) {
            when (val r = read(session, want)) {
                is Read.Ok -> { accepted(r, active); return@withLock }
                Read.Busy -> return@withLock
                is Read.Skip -> if (r.reach.ordinal > reach.ordinal) reach = r.reach
            }
        }
        current = null
        mutable.update { it.copy(reach = reach) }
    }

    private suspend fun accepted(r: Read.Ok, active: String?) {
        val same = current?.device?.id == r.session.device.id
        current = r.session
        val merged = HouseCodec.merge(if (same) mutable.value.snapshot else null, r.json)
        mutable.update { it.copy(snapshot = merged, reach = HouseReach.OK, lastOkAt = clock(), via = r.session.device.name,
            failover = active != null && r.session.device.id != active) }
        if (merged.hasPlan && mutable.value.plan?.updatedAt != merged.planAt) loadPlan(r.session)
        if (!merged.hasPlan && mutable.value.plan != null) mutable.update { it.copy(plan = null) }
    }

    private suspend fun read(session: CompanionSession, want: String?): Read = try {
        val held = mutable.value.snapshot?.takeIf { current?.device?.id == session.device.id }
        val body = HouseCodec.pollBody(held, want)
        Read.Ok(session, withTimeout(ANSWER_MS) { client.post(session, ROUTE, body) })
    } catch (e: TimeoutCancellationException) { Read.Skip(HouseReach.OFFLINE) }
    catch (e: CancellationException) { throw e }
    catch (e: CompanionException) {
        when {
            e.error.code == "rate_limited" -> Read.Busy
            e.error.status == NOT_FOUND && e.error.code == "not_found" -> Read.Skip(HouseReach.NEEDS_UPDATE)
            e.error.code == "ha_not_configured" -> Read.Skip(HouseReach.HA_NOT_SET_UP)
            else -> Read.Skip(HouseReach.OFFLINE)
        }
    }
    catch (_: IOException) { Read.Skip(HouseReach.OFFLINE) }

    private suspend fun loadPlan(session: CompanionSession) {
        val json = try { withTimeout(ANSWER_MS) { client.get(session, "$ROUTE/plan") } }
        catch (e: CancellationException) { if (e is TimeoutCancellationException) return else throw e }
        catch (_: IOException) { return }
        HousePlanCodec.decode(json.optJSONObject("plan"))?.let { plan -> mutable.update { it.copy(plan = plan) } }
    }

    /** Sends [act]; a confirmed resend carries the Portal's [ticket] (and an alarm [code]). */
    suspend fun act(act: HouseAct, ticket: String? = null, code: String? = null): HouseActResult {
        val session = current ?: return HouseActResult.Failed(HouseCopy.OFFLINE)
        val id = act.entity
        if (id != null) mutable.update { it.copy(pending = it.pending + id) }
        return try {
            val json = withTimeout(ACT_MS) { client.post(session, "$ROUTE/act", act.body(ticket, code)) }
            mutable.update { s -> s.copy(snapshot = s.snapshot?.let { HouseOptimistic.apply(it, act) }) }
            if (json.optBoolean("done")) HouseActResult.Done(act.label) else HouseActResult.Done("${act.label} · sent")
        } catch (e: TimeoutCancellationException) { HouseActResult.Failed(HouseCopy.NO_ANSWER) }
        catch (e: CancellationException) { throw e }
        catch (e: CompanionException) {
            if (e.error.code == "confirm") HouseCodec.confirm(e.error.details)?.let { HouseActResult.Confirm(it, act) }
                ?: HouseActResult.Failed(HouseCopy.refused("confirm", null))
            else HouseActResult.Failed(HouseCopy.refused(e.error.code, e.error.details))
        }
        catch (_: IOException) { HouseActResult.Failed(HouseCopy.OFFLINE) }
        finally { if (id != null) mutable.update { it.copy(pending = it.pending - id) } }
    }

    /** One snapshot fetched by the Portal, or null (unreachable, refused, not a picture). */
    suspend fun camera(cameraId: String, width: Int): ByteArray? {
        val session = current ?: return null
        return try {
            withTimeout(CAMERA_MS) {
                client.postImage(session, "$ROUTE/camera", JSONObject().put("entity", cameraId).put("width", width), MAX_PICTURE)
            }
        } catch (e: TimeoutCancellationException) { null }
        catch (e: CancellationException) { throw e }
        catch (_: IOException) { null }
    }

    fun hide(alertId: String) = mutable.update { it.copy(hidden = it.hidden + alertId) }

    fun notify(text: String, error: Boolean = false) = mutable.update { it.copy(notice = HouseNotice(++noticeSeq, text, error)) }

    /** The viewed Portal first, then the one that answered last, then the rest. */
    private fun order(sessions: List<CompanionSession>, active: String?): List<CompanionSession> {
        val byId = sessions.associateBy { it.device.id }
        return (listOfNotNull(active, current?.device?.id) + sessions.map { it.device.id }).distinct().mapNotNull { byId[it] }
    }

    companion object {
        const val ROUTE = "controlcenter"
        const val ANSWER_MS = 4_000L
        /** The Portal waits up to 5 s for Home Assistant's answer. */
        const val ACT_MS = 7_000L
        const val CAMERA_MS = 9_000L
        const val MAX_PICTURE = 1_600_000L
        private const val NOT_FOUND = 404
        private const val TAG = "PortalHouse"

        fun create(context: Context) = HouseRepository(CompanionClient(),
            FileCompanionSessionStore.create(context.applicationContext)) { Log.w(TAG, it) }
    }
}
