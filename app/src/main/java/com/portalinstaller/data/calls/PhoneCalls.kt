/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.calls

import android.app.Application
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.portalinstaller.data.calls.PhoneCallEffect as Fx
import com.portalinstaller.data.companion.FileCompanionSessionStore
import com.portalinstaller.data.companion.PrefsHouseholdKeySource
import com.portalinstaller.data.dinner.DinnerRepository
import com.portalinstaller.ui.calls.PhoneCallActivity
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Phone ↔ Portal calls at runtime. Polls every paired Portal while Portal Toolkit is in the
 * foreground (and for as long as a call lasts), feeds the pure [PhoneCallMachine] and performs
 * its effects on the main thread. Calling 2b: with "Ring when Portal Toolkit is closed" on and the
 * phone on Wi-Fi, [CallStandbyService] keeps one long-poll parked at each Portal instead, so a
 * Portal can ring this phone with the app closed (see [CallStandbyPolicy]).
 */
object PhoneCalls : DefaultLifecycleObserver {
    private const val TAG = "ToolkitCalls"
    private const val TICK_MS = 500L
    private const val ID_BYTES = 8
    /** A Portal may hear our offer before the vouching `call_join` reached it: retry briefly. */
    private const val LEG_OFFER_TRIES = 5
    private const val LEG_RETRY_MS = 700L
    private const val HTTP_CONFLICT = 409

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val random = SecureRandom()
    private val mutableState = MutableStateFlow(PhoneCallState())
    val state: StateFlow<PhoneCallState> = mutableState.asStateFlow()
    private val mutableContacts = MutableStateFlow<List<CallContact>>(emptyList())
    val contacts: StateFlow<List<CallContact>> = mutableContacts.asStateFlow()
    private val mutableMedia = MutableStateFlow<PhoneCallMedia?>(null)
    val media: StateFlow<PhoneCallMedia?> = mutableMedia.asStateFlow()
    /** False when this phone has no household key yet (pair a Portal first). */
    private val mutableReady = MutableStateFlow(true)
    val ready: StateFlow<Boolean> = mutableReady.asStateFlow()
    private val mutableStandby = MutableStateFlow(CallStandbyPolicy.Mode.OFF)
    /** What background ringing is doing right now (the service sets it). */
    val standby: StateFlow<CallStandbyPolicy.Mode> = mutableStandby.asStateFlow()

    private lateinit var app: Application
    private var foreground = false
    private var pollJob: Job? = null
    private var tickJob: Job? = null
    private val parkJobs = HashMap<String, Job>()
    @Volatile private var endpoints: Map<String, CallEndpoint> = emptyMap()
    private val http = OkHttpCallHttp()

    fun install(application: Application) {
        app = application
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        PhoneMessages.install(application)
    }

    override fun onStart(owner: LifecycleOwner) { foreground = true; startPolling() }
    override fun onStop(owner: LifecycleOwner) { foreground = false; syncPolling() }
    val inForeground: Boolean get() = foreground

    // --- user actions (main thread) --------------------------------------------------------

    fun dial(contact: CallContact, video: Boolean): Boolean {
        val ref = contact.ref() ?: return false
        if (state.value.call != null || !CallContacts.presence(contact, now()).callable) return false
        dispatch(PhoneCallEvent.Dial(newId(), ref, video, now()))
        return true
    }

    fun answer(audioOnly: Boolean) = dispatch(PhoneCallEvent.Answer(audioOnly, now()))
    fun decline() = dispatch(PhoneCallEvent.Decline(now()))
    fun hangUp() = dispatch(PhoneCallEvent.HangUp(now()))
    fun toggleMic() = dispatch(PhoneCallEvent.ToggleMic)
    fun toggleCamera() = dispatch(PhoneCallEvent.ToggleCamera)
    fun toggleSpeaker() = dispatch(PhoneCallEvent.ToggleSpeaker)
    fun dismissEnded() = dispatch(PhoneCallEvent.DismissEnded)
    fun moveTo(contact: CallContact) { contact.ref()?.let { dispatch(PhoneCallEvent.MoveTo(it, now())) } }
    fun pullBack() = dispatch(PhoneCallEvent.PullBack(now()))

    /** Portals this call could move to now ("To Portal"): paired, available and not already in it. */
    fun moveTargets(call: PhoneCall): List<CallContact> = contacts.value.filter { c ->
        val ref = c.ref() ?: return@filter false
        CallContacts.presence(c, now()) == ContactPresence.AVAILABLE && PhoneGroupMachine.canMove(call, ref)
    }

    /** The service: background ringing is [mode] now. */
    fun setStandby(mode: CallStandbyPolicy.Mode) {
        if (!::app.isInitialized) return
        mutableStandby.value = mode
        syncPolling()
    }

    // --- reducer + effects -----------------------------------------------------------------

    fun dispatch(event: PhoneCallEvent) {
        val step = synchronized(this) {
            PhoneCallMachine.reduce(mutableState.value, event).also { mutableState.value = it.state }
        }
        scope.launch {
            step.effects.forEach { e -> runCatching { perform(e) }.onFailure { Log.w(TAG, "effect ${e.javaClass.simpleName} failed", it) } }
            step.state.call?.let { c -> mutableMedia.value?.let { m -> m.groupSize(c.people); m.primary(c.portal.sessionId) } }
            syncTimers()
        }
    }

    private fun perform(effect: Fx) {
        when (effect) {
            is Fx.SendRing -> send(effect.call.portal) { CallWire.ringBody(effect.call.id, it.portalId, effect.call.video, phoneName()) }
                .let { job -> scope.launch { val r = job.await(); dispatch(PhoneCallEvent.RingSent(effect.call.id, r?.reply, r?.quietEndMin, now())) } }
            is Fx.SendMoveRing -> sendMoveRing(effect)
            is Fx.SendReply -> send(effect.portal) { CallWire.replyBody(effect.callId, it.portalId, effect.reply) }
            is Fx.SendEnd -> send(effect.portal) { CallWire.endBody(effect.callId, it.portalId, effect.reason, effect.movedTo) }
            is Fx.SendMedia -> effect.call.members.forEach { p ->
                send(p) { CallWire.mediaBody(effect.call.id, it.portalId, effect.call.cameraOn, effect.call.micOn) }
            }
            is Fx.SendJoin -> send(effect.to) { CallWire.joinBody(effect.callId, it.portalId, effect.newcomer.portalId, effect.newcomer.name) }
            is Fx.SendMoved -> send(effect.to) { CallWire.movedBody(effect.callId, it.portalId) }
            is Fx.SendPull -> send(effect.to) { CallWire.pullBody(effect.callId, it.portalId) }
            is Fx.StartMedia -> startMedia(effect.call)
            is Fx.StartLeg -> mutableMedia.value?.let { m -> offer(effect.callId, effect.portal, m, LEG_OFFER_TRIES) }
            is Fx.ClosePeer -> mutableMedia.value?.close(effect.sessionId)
            Fx.StopMedia -> { mutableMedia.value?.release(); mutableMedia.value = null }
            is Fx.SetMic -> mutableMedia.value?.mic(effect.on)
            is Fx.SetCamera -> mutableMedia.value?.camera(effect.on)
            is Fx.SetSpeaker -> mutableMedia.value?.speaker(effect.on)
            is Fx.ShowIncoming -> { CallAlerts.incoming(app, effect.call); if (foreground) PhoneCallActivity.open(app) }
            Fx.StartRingtone -> CallAlerts.startRingtone(app)
            Fx.StopRingtone -> CallAlerts.stopRingtone()
            is Fx.NotifyMissed -> CallAlerts.missed(app, effect.ended)
            Fx.ClearIncoming -> CallAlerts.clearIncoming(app)
        }
    }

    private fun sendMoveRing(e: Fx.SendMoveRing) {
        val members = e.members.map { MemberRef(it.portalId, it.name) }
        val job = send(e.to) { CallWire.ringBody(e.call.id, it.portalId, e.call.video, phoneName(), members, move = true) }
        scope.launch { val r = job.await(); dispatch(PhoneCallEvent.MoveRingSent(e.call.id, e.to.sessionId, r?.reply, now())) }
    }

    private fun send(portal: PortalRef, body: (PortalRef) -> String) = scope.async {
        val endpoint = endpoints[portal.sessionId] ?: return@async null
        val client = client() ?: return@async null
        try { client.send(endpoint, body(portal)) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            Log.w(TAG, "send failed: ${e.javaClass.simpleName}"); null
        }
    }

    private fun startMedia(call: PhoneCall) {
        mutableMedia.value?.release()
        val m = PhoneCallMedia(app, object : PhoneCallMedia.Events {
            override fun onConnected(peer: String) = dispatch(PhoneCallEvent.MediaUp(call.id, now(), peer))
            override fun onDisconnected(peer: String) = dispatch(PhoneCallEvent.MediaDown(call.id, now(), peer))
        })
        mutableMedia.value = m
        m.camera(call.cameraOn)
        m.mic(call.micOn)
        m.speaker(call.speakerOn)
        m.primary(call.portal.sessionId)
        m.groupSize(call.people)
        offer(call.id, call.portal, m, tries = 1)
    }

    /** Offers to one Portal; a 409 means its vouching `call_join` hasn't arrived yet, so try again briefly. */
    private fun offer(callId: String, portal: PortalRef, m: PhoneCallMedia, tries: Int) {
        m.createOffer(portal.sessionId) { sdp -> scope.launch { exchange(callId, portal, m, sdp, tries) } }
    }

    private suspend fun exchange(callId: String, portal: PortalRef, m: PhoneCallMedia, sdp: String?, left: Int) {
        val endpoint = endpoints[portal.sessionId]
        val client = client()
        var retry = false
        val answer = if (sdp == null || endpoint == null || client == null) null else try {
            client.offer(endpoint, callId, portal.portalId, sdp)
        } catch (e: CancellationException) { throw e } catch (e: CallClientException) {
            retry = e.status == HTTP_CONFLICT && left > 1; null
        } catch (e: Exception) { Log.w(TAG, "offer failed: ${e.javaClass.simpleName}"); null }
        val stillHere = state.value.call?.let { it.id == callId && it.isMember(portal.sessionId) } == true && mutableMedia.value === m
        if (!stillHere) return
        if (answer == null && retry && sdp != null) { delay(LEG_RETRY_MS); exchange(callId, portal, m, sdp, left - 1); return }
        if (answer == null) { dispatch(PhoneCallEvent.OfferDone(callId, false, now(), portal.sessionId)); return }
        m.applyAnswer(portal.sessionId, answer) { ok -> dispatch(PhoneCallEvent.OfferDone(callId, ok, now(), portal.sessionId)) }
    }

    // --- polling ---------------------------------------------------------------------------

    /** Short polls while open or in a call; parked long-polls while background ringing listens; else nothing. */
    private fun syncPolling() {
        if (!::app.isInitialized) return
        val active = foreground || state.value.call != null
        if (active) startPolling() else stopPolling()
        // Parked loops end themselves after their in-flight poll once they shouldn't listen any more.
        if (!active && standby.value == CallStandbyPolicy.Mode.LISTENING) startParking()
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                pollOnce()
                val plan = CallStandbyPolicy.plan(state.value.call != null, foreground, standby.value, 0, 0) ?: break
                delay(plan.delayMs)
            }
        }
    }

    private fun stopPolling() { pollJob?.cancel(); pollJob = null }

    /**
     * One loop per paired Portal, each poll parked there for up to 25 s. A parked request is never
     * cancelled midway (it may be carrying a ring); its result is always handled, then the loop
     * re-checks whether it should still be listening.
     */
    private fun startParking() = scope.launch {
        refreshSessions()
        for (endpoint in endpoints.values) {
            if (parkJobs[endpoint.sessionId]?.isActive == true) continue
            parkJobs[endpoint.sessionId] = scope.launch {
                var asked = 0L
                var granted = 0L
                while (isActive) {
                    val plan = CallStandbyPolicy.plan(state.value.call != null, foreground, standby.value, asked, granted)
                    if (plan == null || plan.waitMs == 0L && (foreground || state.value.call != null)) break
                    val result = pollOne(endpoint, plan.waitMs)
                    asked = plan.waitMs
                    granted = result?.waitMs ?: 0L
                    delay(if (result == null) CallStandbyPolicy.RETRY_MS else plan.delayMs)
                }
                parkJobs.remove(endpoint.sessionId)
            }
        }
    }

    private suspend fun refreshSessions() {
        val sessions = try { FileCompanionSessionStore.create(app).load() } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
        endpoints = sessions.associate { it.device.id to CallEndpoint(it.device.id, it.device.endpoint, it.token) }
        mutableContacts.update { CallContacts.sync(it, sessions.map { s -> s.device.id to s.device.name }) }
    }

    private suspend fun pollOnce() {
        refreshSessions()
        client() ?: return
        endpoints.values.map { endpoint -> scope.async { pollOne(endpoint, 0L) } }.awaitAll()
    }

    /** Polls one Portal and handles everything it said; null when the request failed. */
    private suspend fun pollOne(endpoint: CallEndpoint, waitMs: Long): PollResult? {
        val client = client() ?: return null
        val background = standby.value != CallStandbyPolicy.Mode.OFF
        val result = try { client.poll(endpoint, phoneName(), aliases(), background, waitMs) }
        catch (e: CancellationException) { throw e } catch (_: Exception) { return null }
        val t = now()
        mutableContacts.update { CallContacts.polled(it, endpoint.sessionId, result.portal, t) }
        val directory = directory()
        result.messages.forEach { dispatch(PhoneCallEvent.FromPortal(endpoint.sessionId, result.portal.id, it, t, directory)) }
        if (result.videoMessages.isNotEmpty()) PhoneMessages.collect(endpoint, result.portal.name, result.videoMessages)
        return result
    }

    /** Our id on each paired Portal whose id we know: Portals name us to each other with it. */
    private fun aliases(): Map<String, String> = contacts.value.mapNotNull { c ->
        val id = c.portal?.id ?: return@mapNotNull null
        val endpoint = endpoints[c.sessionId] ?: return@mapNotNull null
        id to CallSigner.phoneId(endpoint.token)
    }.toMap()

    private fun directory(): Map<String, PortalRef> = contacts.value.mapNotNull { c -> c.ref()?.let { it.portalId to it } }.toMap()

    fun endpoint(sessionId: String): CallEndpoint? = endpoints[sessionId]

    private fun syncTimers() {
        val s = state.value
        if ((s.call != null || s.ended != null) && tickJob?.isActive != true) {
            tickJob = scope.launch {
                while (isActive && (state.value.call != null || state.value.ended != null)) {
                    delay(TICK_MS)
                    dispatch(PhoneCallEvent.Tick(now()))
                }
            }
        }
        syncPolling()
    }

    internal fun client(): CallClient? {
        val key = PrefsHouseholdKeySource(app).peek()
        mutableReady.value = key != null
        return key?.let { CallClient(http, CallSigner(it)) }
    }

    private fun phoneName() = DinnerRepository.phoneName(app)
    private fun now() = System.currentTimeMillis()
    private fun newId() = ByteArray(ID_BYTES).also(random::nextBytes).joinToString("") { "%02x".format(it) }
}
