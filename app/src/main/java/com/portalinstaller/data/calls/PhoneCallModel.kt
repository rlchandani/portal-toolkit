/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.calls

/** Which Portal a call is with: the paired session it travels over, and the Portal's own id. */
data class PortalRef(val sessionId: String, val portalId: String, val name: String)

enum class CallDirection { OUTGOING, INCOMING }

/** Outgoing: DIALING → RINGING_OUT → CONNECTING → LIVE. Incoming: RINGING_IN → CONNECTING → LIVE. */
enum class PhonePhase {
    DIALING, RINGING_OUT, RINGING_IN, CONNECTING, LIVE, RECONNECTING;
    val beforeAnswer get() = this == DIALING || this == RINGING_OUT || this == RINGING_IN
    val connected get() = this == LIVE || this == RECONNECTING
}

/** How the last call ended, worded on the ended screen and in the missed-call notification. */
enum class PhoneEndReason(val title: String) {
    HUNG_UP("Call ended"), REMOTE_HUNG_UP("Call ended"), CANCELLED("Call cancelled"),
    DECLINED("Call declined"), REMOTE_DECLINED("They’re busy right now"), NO_ANSWER("No answer"),
    MISSED("Missed call"), BUSY("Already in a call"), QUIET("Quiet hours there"),
    CALLS_OFF("This Portal doesn’t take calls from phones"),
    UNREACHABLE("Couldn’t reach the Portal"), FAILED("The call couldn’t connect"), NETWORK_LOST("The Wi-Fi dropped");
}

data class PhoneCall(
    val id: String,
    val portal: PortalRef,
    val direction: CallDirection,
    val video: Boolean,
    val phase: PhonePhase,
    val phaseSinceMs: Long,
    val cameraOn: Boolean,
    val micOn: Boolean = true,
    val speakerOn: Boolean = true,
    val connectedMs: Long? = null,
    val remoteCameraOn: Boolean = true,
    val remoteMicOn: Boolean = true,
)

data class EndedPhoneCall(
    val id: String,
    val portal: PortalRef,
    val direction: CallDirection,
    val reason: PhoneEndReason,
    val connectedMs: Long?,
    val endedMs: Long,
    val quietEndMin: Int? = null,
)

data class PhoneCallState(val call: PhoneCall? = null, val ended: EndedPhoneCall? = null)

sealed interface PhoneCallEvent {
    data class Dial(val id: String, val portal: PortalRef, val video: Boolean, val now: Long) : PhoneCallEvent
    /** The Portal's immediate answer to our ring (null reply: the request failed). */
    data class RingSent(val id: String, val reply: PortalReply?, val quietEndMin: Int?, val now: Long) : PhoneCallEvent
    /** [portalId]: that session's Portal id from its poll (a ring names only the room). */
    data class FromPortal(val sessionId: String, val portalId: String, val message: PortalMessage, val now: Long) : PhoneCallEvent
    data class Answer(val audioOnly: Boolean, val now: Long) : PhoneCallEvent
    data class Decline(val now: Long) : PhoneCallEvent
    data class HangUp(val now: Long) : PhoneCallEvent
    /** Our signed offer got a verified answer (true) or not. */
    data class OfferDone(val id: String, val ok: Boolean, val now: Long) : PhoneCallEvent
    data class MediaUp(val id: String, val now: Long) : PhoneCallEvent
    data class MediaDown(val id: String, val now: Long) : PhoneCallEvent
    data class Tick(val now: Long) : PhoneCallEvent
    data object ToggleMic : PhoneCallEvent
    data object ToggleCamera : PhoneCallEvent
    data object ToggleSpeaker : PhoneCallEvent
    data object DismissEnded : PhoneCallEvent
}

sealed interface PhoneCallEffect {
    data class SendRing(val call: PhoneCall) : PhoneCallEffect
    data class SendReply(val callId: String, val portal: PortalRef, val reply: PortalReply) : PhoneCallEffect
    data class SendEnd(val callId: String, val portal: PortalRef, val reason: EndWire) : PhoneCallEffect
    data class SendMedia(val call: PhoneCall) : PhoneCallEffect
    /** Open camera (if [PhoneCall.cameraOn]) and mic, build the offer and POST it. */
    data class StartMedia(val call: PhoneCall) : PhoneCallEffect
    data object StopMedia : PhoneCallEffect
    data class SetMic(val on: Boolean) : PhoneCallEffect
    data class SetCamera(val on: Boolean) : PhoneCallEffect
    data class SetSpeaker(val on: Boolean) : PhoneCallEffect
    data class ShowIncoming(val call: PhoneCall) : PhoneCallEffect
    data object StartRingtone : PhoneCallEffect
    data object StopRingtone : PhoneCallEffect
    data class NotifyMissed(val ended: EndedPhoneCall) : PhoneCallEffect
    data object ClearIncoming : PhoneCallEffect
}

data class PhoneCallStep(val state: PhoneCallState, val effects: List<PhoneCallEffect> = emptyList())

/** Named timings (the Portal's own, plus the phone's poll cadence). */
object PhoneCallTimings {
    /** The Portal answers a ring at once; without any reply in this time it isn't reachable. */
    const val DIAL_ACK_MS = 6_000L
    const val RING_MS = 45_000L
    /** The callee side waits a little longer than the caller so the caller's end arrives first. */
    const val RING_GRACE_MS = 2_000L
    const val CONNECT_MS = 15_000L
    const val RECONNECT_MS = 10_000L
    const val ENDED_SHOW_MS = 4_000L
    const val POLL_IDLE_MS = 1_500L
    const val POLL_IN_CALL_MS = 1_000L
    /** A Portal that didn't answer a poll for this long shows as Offline. */
    const val OFFLINE_AFTER_MS = 6_000L

    fun pollDelayMs(state: PhoneCallState): Long = if (state.call != null) POLL_IN_CALL_MS else POLL_IDLE_MS
}
