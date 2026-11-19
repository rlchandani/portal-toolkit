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
 * its effects on the main thread. There is no background push yet: a Portal can ring this phone
 * only while the app is open.
 */
object PhoneCalls : DefaultLifecycleObserver {
    private const val TAG = "ToolkitCalls"
    private const val TICK_MS = 500L
    private const val ID_BYTES = 8

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

    private lateinit var app: Application
    private var foreground = false
    private var pollJob: Job? = null
    private var tickJob: Job? = null
    private var endpoints: Map<String, CallEndpoint> = emptyMap()
    private val http = OkHttpCallHttp()

    fun install(application: Application) {
        app = application
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) { foreground = true; startPolling() }
    override fun onStop(owner: LifecycleOwner) { foreground = false; if (state.value.call == null) stopPolling() }
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

    // --- reducer + effects -----------------------------------------------------------------

    fun dispatch(event: PhoneCallEvent) {
        val step = synchronized(this) {
            PhoneCallMachine.reduce(mutableState.value, event).also { mutableState.value = it.state }
        }
        scope.launch {
            step.effects.forEach { e -> runCatching { perform(e) }.onFailure { Log.w(TAG, "effect ${e.javaClass.simpleName} failed", it) } }
            syncTimers()
        }
    }

    private fun perform(effect: Fx) {
        when (effect) {
            is Fx.SendRing -> send(effect.call.portal) { CallWire.ringBody(effect.call.id, it.portalId, effect.call.video, phoneName()) }
                .let { job -> scope.launch { val r = job.await(); dispatch(PhoneCallEvent.RingSent(effect.call.id, r?.reply, r?.quietEndMin, now())) } }
            is Fx.SendReply -> send(effect.portal) { CallWire.replyBody(effect.callId, it.portalId, effect.reply) }
            is Fx.SendEnd -> send(effect.portal) { CallWire.endBody(effect.callId, it.portalId, effect.reason) }
            is Fx.SendMedia -> send(effect.call.portal) { CallWire.mediaBody(effect.call.id, it.portalId, effect.call.cameraOn, effect.call.micOn) }
            is Fx.StartMedia -> startMedia(effect.call)
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
            override fun onConnected() = dispatch(PhoneCallEvent.MediaUp(call.id, now()))
            override fun onDisconnected() = dispatch(PhoneCallEvent.MediaDown(call.id, now()))
        })
        mutableMedia.value = m
        m.camera(call.cameraOn)
        m.mic(call.micOn)
        m.speaker(call.speakerOn)
        m.createOffer { sdp -> scope.launch { exchange(call, m, sdp) } }
    }

    private suspend fun exchange(call: PhoneCall, m: PhoneCallMedia, sdp: String?) {
        val endpoint = endpoints[call.portal.sessionId]
        val client = client()
        val answer = if (sdp == null || endpoint == null || client == null) null else try {
            client.offer(endpoint, call.id, call.portal.portalId, sdp)
        } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.w(TAG, "offer failed: ${e.javaClass.simpleName}"); null }
        if (answer == null) { dispatch(PhoneCallEvent.OfferDone(call.id, false, now())); return }
        m.applyAnswer(answer) { ok -> dispatch(PhoneCallEvent.OfferDone(call.id, ok, now())) }
    }

    // --- polling ---------------------------------------------------------------------------

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                pollOnce()
                delay(PhoneCallTimings.pollDelayMs(state.value))
            }
        }
    }

    private fun stopPolling() { pollJob?.cancel(); pollJob = null }

    private suspend fun pollOnce() {
        val sessions = try { FileCompanionSessionStore.create(app).load() } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
        endpoints = sessions.associate { it.device.id to CallEndpoint(it.device.id, it.device.endpoint, it.token) }
        mutableContacts.update { CallContacts.sync(it, sessions.map { s -> s.device.id to s.device.name }) }
        val client = client() ?: return
        val name = phoneName()
        endpoints.values.map { endpoint ->
            scope.async {
                try { endpoint to client.poll(endpoint, name) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            }
        }.awaitAll().filterNotNull().forEach { (endpoint, result) ->
            val t = now()
            mutableContacts.update { CallContacts.polled(it, endpoint.sessionId, result.portal, t) }
            result.messages.forEach { dispatch(PhoneCallEvent.FromPortal(endpoint.sessionId, result.portal.id, it, t)) }
        }
    }

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
        if (s.call == null && !foreground) stopPolling()
    }

    private fun client(): CallClient? {
        val key = PrefsHouseholdKeySource(app).peek()
        mutableReady.value = key != null
        return key?.let { CallClient(http, CallSigner(it)) }
    }

    private fun phoneName() = DinnerRepository.phoneName(app)
    private fun now() = System.currentTimeMillis()
    private fun newId() = ByteArray(ID_BYTES).also(random::nextBytes).joinToString("") { "%02x".format(it) }
}
