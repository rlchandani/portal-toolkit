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
    UNREACHABLE("Couldn’t reach the Portal"), FAILED("The call couldn’t connect"), NETWORK_LOST("The Wi-Fi dropped"),
    /** Calling 2b: this phone handed the call to a Portal ("To Portal"). */
    MOVED("Call moved");
}

/** One more Portal in a group call with this phone (Calling 2b). The phone always offers to it. */
enum class LegPhase { JOINING, LIVE, RECONNECTING; val connected get() = this != JOINING }

data class PhoneLeg(
    val portal: PortalRef,
    val phase: LegPhase,
    val sinceMs: Long,
    val connectedMs: Long? = null,
    val remoteCameraOn: Boolean = true,
    val remoteMicOn: Boolean = true,
    /** This leg is the Portal the call is moving to. */
    val moving: Boolean = false,
)

/**
 * A call being handed between this person's devices. [to]: we are moving it to that Portal
 * (session id); [from]: it is moving here from that Portal. [ringing]: the target hasn't said yes.
 */
data class PhoneMove(
    val to: String? = null,
    val from: String? = null,
    val ringing: Boolean = false,
    val sinceMs: Long = 0L,
    val targetLiveMs: Long? = null,
    val announced: Boolean = false,
    /** Source side: the Portal we are moving to (its session id is [to]). */
    val target: PortalRef? = null,
)

/** Something worth a line in the call ("Den left", "Kitchen didn’t pick up · the call stays here"). */
data class PhoneNotice(val name: String, val text: String, val atMs: Long)

/** The Portals a group ring names, as the Portal said them (ids are the same on every Portal). */
data class MemberRef(val portalId: String, val name: String)

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
    /** Group calls: the other Portals beyond [portal] (at most [PhoneGroup.MAX_LEGS]). */
    val legs: List<PhoneLeg> = emptyList(),
    /** Incoming group ring: the Portals already in the call; we offer to each on Answer. */
    val invited: List<PortalRef> = emptyList(),
    /** Who added us ("Living room added you to a call with Kitchen"). */
    val inviter: String? = null,
    val move: PhoneMove? = null,
    /** The Portal this call moved here from: only it may ask for the call back ("Move it back here"). */
    val cameFrom: String? = null,
    /** The other side answered by itself (Grandma mode): no adding, no moving. */
    val remoteAutoAnswered: Boolean = false,
    val notice: PhoneNotice? = null,
) {
    val people: Int get() = 2 + legs.size
    val group: Boolean get() = legs.isNotEmpty()
    fun leg(sessionId: String): PhoneLeg? = legs.firstOrNull { it.portal.sessionId == sessionId }
    fun isMember(sessionId: String): Boolean = portal.sessionId == sessionId || leg(sessionId) != null
    fun isMemberPortal(portalId: String): Boolean = portal.portalId == portalId || legs.any { it.portal.portalId == portalId }
    /** Everyone we talk to: the first Portal, then the legs. */
    val members: List<PortalRef> get() = listOf(portal) + legs.map { it.portal }
}

object PhoneGroup {
    const val MAX_PEOPLE = 4
    const val MAX_LEGS = MAX_PEOPLE - 2
}

data class EndedPhoneCall(
    val id: String,
    val portal: PortalRef,
    val direction: CallDirection,
    val reason: PhoneEndReason,
    val connectedMs: Long?,
    val endedMs: Long,
    val quietEndMin: Int? = null,
    /** [PhoneEndReason.MOVED]: the Portal that carries the call on. */
    val movedTo: PortalRef? = null,
)

/** "Move it back here" was tapped: the Portal's move ring for [callId] is answered without another tap until [untilMs]. */
data class PendingPull(val callId: String, val sessionId: String, val untilMs: Long)

data class PhoneCallState(val call: PhoneCall? = null, val ended: EndedPhoneCall? = null, val pull: PendingPull? = null)

sealed interface PhoneCallEvent {
    data class Dial(val id: String, val portal: PortalRef, val video: Boolean, val now: Long) : PhoneCallEvent
    /** The Portal's immediate answer to our ring (null reply: the request failed). */
    data class RingSent(val id: String, val reply: PortalReply?, val quietEndMin: Int?, val now: Long) : PhoneCallEvent
    /**
     * [portalId]: that session's Portal id from its poll (a ring names only the room).
     * [directory]: every paired Portal by its id, so a group ring's members map to our sessions.
     */
    data class FromPortal(val sessionId: String, val portalId: String, val message: PortalMessage, val now: Long,
                          val directory: Map<String, PortalRef> = emptyMap()) : PhoneCallEvent
    data class Answer(val audioOnly: Boolean, val now: Long) : PhoneCallEvent
    data class Decline(val now: Long) : PhoneCallEvent
    data class HangUp(val now: Long) : PhoneCallEvent
    /** Our signed offer got a verified answer (true) or not. [sessionId]: which Portal (null = the first). */
    data class OfferDone(val id: String, val ok: Boolean, val now: Long, val sessionId: String? = null) : PhoneCallEvent
    data class MediaUp(val id: String, val now: Long, val sessionId: String? = null) : PhoneCallEvent
    data class MediaDown(val id: String, val now: Long, val sessionId: String? = null) : PhoneCallEvent
    /** "To Portal": move this call to [portal]; we leave once it is connected to everyone. */
    data class MoveTo(val portal: PortalRef, val now: Long) : PhoneCallEvent
    /** The target Portal's immediate answer to our move ring (null: the request failed). */
    data class MoveRingSent(val id: String, val sessionId: String, val reply: PortalReply?, val now: Long) : PhoneCallEvent
    /** After a move to a Portal: ask it for the call back ("Move it back here"). */
    data class PullBack(val now: Long) : PhoneCallEvent
    data class Tick(val now: Long) : PhoneCallEvent
    data object ToggleMic : PhoneCallEvent
    data object ToggleCamera : PhoneCallEvent
    data object ToggleSpeaker : PhoneCallEvent
    data object DismissEnded : PhoneCallEvent
}

sealed interface PhoneCallEffect {
    data class SendRing(val call: PhoneCall) : PhoneCallEffect
    data class SendReply(val callId: String, val portal: PortalRef, val reply: PortalReply) : PhoneCallEffect
    /** [movedTo]: the Portal id the call moved to (we are leaving because of a move). */
    data class SendEnd(val callId: String, val portal: PortalRef, val reason: EndWire, val movedTo: String? = null) : PhoneCallEffect
    /** Offer to one more Portal in the call (camera and mic are already open). */
    data class StartLeg(val callId: String, val portal: PortalRef) : PhoneCallEffect
    data class ClosePeer(val sessionId: String) : PhoneCallEffect
    /** Vouch to [to] that [newcomer] joined (it will offer to them). */
    data class SendJoin(val callId: String, val to: PortalRef, val newcomer: PortalRef) : PhoneCallEffect
    /** Move target: tell the Portal we are taking over from that everyone is connected. */
    data class SendMoved(val callId: String, val to: PortalRef) : PhoneCallEffect
    /** "To Portal": ring [to] to take this call over, naming the Portals already in it. */
    data class SendMoveRing(val call: PhoneCall, val to: PortalRef, val members: List<PortalRef>) : PhoneCallEffect
    /** "Move it back here": ask [to] to move the call back to this phone. */
    data class SendPull(val callId: String, val to: PortalRef) : PhoneCallEffect
    /** Our camera/mic state, to every Portal in the call. */
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
    /** Once the device a call moves to is connected, the old one leaves after at most this. */
    const val MOVE_SETTLE_MS = 8_000L
    /** "Move it back here": the Portal's move ring is taken without a second tap for this long. */
    const val PULL_BACK_MS = 20_000L

    fun pollDelayMs(state: PhoneCallState): Long = if (state.call != null) POLL_IN_CALL_MS else POLL_IDLE_MS
}
