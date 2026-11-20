/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.calls

import com.portalinstaller.data.calls.PhoneCallEffect as Fx
import com.portalinstaller.data.calls.PhoneCallEvent as Ev

/**
 * The phone's one call, as a pure state machine (JVM-tested). Rules mirror the Portal's: the phone
 * is always the WebRTC offerer, camera and mic open only after Answer (or after the Portal's
 * `call_go` when the phone placed the call), and every way out releases media. Group calls and
 * moving a call (Calling 2b) live in [PhoneGroupMachine]; this file keeps the first Portal's rules.
 */
object PhoneCallMachine {
    fun reduce(state: PhoneCallState, event: Ev): PhoneCallStep = PhoneGroupMachine.settle(step(state, event))

    private fun step(state: PhoneCallState, event: Ev): PhoneCallStep {
        val call = state.call
        return when (event) {
            is Ev.Dial -> dial(state, event)
            is Ev.FromPortal -> fromPortal(state, event)
            is Ev.DismissEnded -> PhoneCallStep(state.copy(ended = null))
            is Ev.Tick -> tick(state, event.now)
            is Ev.PullBack -> PhoneGroupMachine.pullBack(state, event.now)
            else -> if (call == null) PhoneCallStep(state) else active(state, call, event)
        }
    }

    private fun active(state: PhoneCallState, call: PhoneCall, event: Ev): PhoneCallStep = when (event) {
        is Ev.RingSent -> ringSent(state, call, event)
        is Ev.Answer -> answer(state, call, event.audioOnly, event.now)
        is Ev.Decline -> if (call.phase == PhonePhase.RINGING_IN)
            end(state, call, PhoneEndReason.DECLINED, event.now, Fx.SendReply(call.id, call.portal, PortalReply.DECLINED))
        else PhoneCallStep(state)
        is Ev.HangUp -> hangUp(state, call, event.now)
        is Ev.OfferDone -> when {
            event.id != call.id || event.ok -> PhoneCallStep(state)
            !primary(call, event.sessionId) -> PhoneGroupMachine.legFailed(state, call, event.sessionId!!, event.now)
            else -> end(state, call, PhoneEndReason.FAILED, event.now, Fx.SendEnd(call.id, call.portal, EndWire.FAILED))
        }
        is Ev.MediaUp -> when {
            event.id != call.id -> PhoneCallStep(state)
            !primary(call, event.sessionId) -> PhoneGroupMachine.legUp(state, call, event.sessionId!!, event.now)
            call.phase == PhonePhase.CONNECTING || call.phase == PhonePhase.RECONNECTING ->
                next(state, call.copy(phase = PhonePhase.LIVE, phaseSinceMs = event.now, connectedMs = call.connectedMs ?: event.now))
            else -> PhoneCallStep(state)
        }
        is Ev.MediaDown -> when {
            event.id != call.id -> PhoneCallStep(state)
            !primary(call, event.sessionId) -> PhoneGroupMachine.legDown(state, call, event.sessionId!!, event.now)
            call.phase == PhonePhase.LIVE -> next(state, call.copy(phase = PhonePhase.RECONNECTING, phaseSinceMs = event.now))
            else -> PhoneCallStep(state)
        }
        is Ev.ToggleMic -> call.copy(micOn = !call.micOn).let { n ->
            PhoneCallStep(state.copy(call = n), listOfNotNull(Fx.SetMic(n.micOn), mediaState(n)))
        }
        is Ev.ToggleCamera -> call.copy(cameraOn = !call.cameraOn).let { n ->
            // Before Answer nothing is open: the toggle only records the wish.
            PhoneCallStep(state.copy(call = n), if (call.phase.beforeAnswer) emptyList() else listOfNotNull(Fx.SetCamera(n.cameraOn), mediaState(n)))
        }
        is Ev.ToggleSpeaker -> call.copy(speakerOn = !call.speakerOn).let { n ->
            PhoneCallStep(state.copy(call = n), listOf(Fx.SetSpeaker(n.speakerOn)))
        }
        is Ev.MoveTo -> PhoneGroupMachine.moveTo(state, call, event.portal, event.now)
        is Ev.MoveRingSent -> PhoneGroupMachine.moveRingSent(state, call, event)
        else -> PhoneCallStep(state)
    }

    private fun primary(call: PhoneCall, sessionId: String?) = sessionId == null || sessionId == call.portal.sessionId

    private fun dial(state: PhoneCallState, e: Ev.Dial): PhoneCallStep {
        if (state.call != null) return PhoneCallStep(state)
        val call = PhoneCall(e.id, e.portal, CallDirection.OUTGOING, e.video, PhonePhase.DIALING, e.now, cameraOn = e.video)
        return PhoneCallStep(PhoneCallState(call = call), listOf(Fx.SendRing(call)))
    }

    private fun ringSent(state: PhoneCallState, call: PhoneCall, e: Ev.RingSent): PhoneCallStep {
        if (e.id != call.id || call.direction != CallDirection.OUTGOING || call.phase != PhonePhase.DIALING) return PhoneCallStep(state)
        return when (e.reply) {
            null -> end(state, call, PhoneEndReason.UNREACHABLE, e.now)
            else -> replied(state, call, e.reply, e.quietEndMin, e.now)
        }
    }

    internal fun replied(state: PhoneCallState, call: PhoneCall, reply: PortalReply, quietEnd: Int?, now: Long): PhoneCallStep {
        if (call.direction != CallDirection.OUTGOING || !call.phase.beforeAnswer) return PhoneCallStep(state)
        return when (reply) {
            PortalReply.RINGING, PortalReply.COUNTDOWN -> if (call.phase == PhonePhase.DIALING)
                next(state, call.copy(phase = PhonePhase.RINGING_OUT, phaseSinceMs = now)) else PhoneCallStep(state)
            PortalReply.QUIET -> end(state, call, PhoneEndReason.QUIET, now, quietEndMin = quietEnd)
            PortalReply.BUSY -> end(state, call, PhoneEndReason.BUSY, now)
            PortalReply.CALLS_OFF, PortalReply.DROP_INS_OFF -> end(state, call, PhoneEndReason.CALLS_OFF, now)
            PortalReply.DECLINED -> end(state, call, PhoneEndReason.REMOTE_DECLINED, now)
        }
    }

    private fun fromPortal(state: PhoneCallState, e: Ev.FromPortal): PhoneCallStep {
        val msg = e.message
        if (msg is PortalMessage.Ring) return incoming(state, PortalRef(e.sessionId, e.portalId, msg.name), msg, e.directory, e.now)
        val call = state.call
        if (call == null || call.id != msg.callId) return PhoneCallStep(state)
        // Messages from Portals beyond the first, from the move target, or from where the call came from.
        PhoneGroupMachine.fromOther(state, call, e)?.let { return it }
        if (call.portal.sessionId != e.sessionId) return PhoneCallStep(state)
        return when (msg) {
            is PortalMessage.Reply -> replied(state, call, msg.reply, msg.quietEndMin, e.now)
            is PortalMessage.Go -> if (call.direction == CallDirection.OUTGOING && call.phase.beforeAnswer)
                connect(state, call.copy(phase = PhonePhase.CONNECTING, phaseSinceMs = e.now)) else PhoneCallStep(state)
            is PortalMessage.End -> PhoneGroupMachine.primaryEnded(state, call, msg, e.now) ?: remoteEnded(state, call, msg.reason, e.now)
            is PortalMessage.Media -> next(state, call.copy(remoteCameraOn = msg.cameraOn, remoteMicOn = msg.micOn,
                remoteAutoAnswered = call.remoteAutoAnswered || msg.autoAnswered))
            is PortalMessage.Join -> PhoneGroupMachine.join(state, call, e.sessionId, msg, e.directory, e.now)
            is PortalMessage.Moved, is PortalMessage.Pull, is PortalMessage.Ring -> PhoneCallStep(state)
        }
    }

    private fun incoming(state: PhoneCallState, portal: PortalRef, ring: PortalMessage.Ring, directory: Map<String, PortalRef>,
                         now: Long): PhoneCallStep {
        val current = state.call ?: return ringIn(state, portal, ring, directory, now)
        // The same ring again: stay idempotent. Another Portal while we're in a call: say busy.
        if (current.id == ring.callId) return PhoneCallStep(state)
        return PhoneCallStep(state, listOf(Fx.SendReply(ring.callId, portal, PortalReply.BUSY)))
    }

    private fun ringIn(state: PhoneCallState, portal: PortalRef, ring: PortalMessage.Ring, directory: Map<String, PortalRef>,
                       now: Long): PhoneCallStep {
        // Members we have no session with can't be reached; the Portal only adds phones paired with all of them.
        val invited = ring.members.mapNotNull { directory[it.portalId] }.filter { it.sessionId != portal.sessionId }
            .distinctBy { it.sessionId }.take(PhoneGroup.MAX_LEGS)
        val call = PhoneCall(ring.callId, portal, CallDirection.INCOMING, ring.video, PhonePhase.RINGING_IN, now, cameraOn = ring.video,
            invited = invited, inviter = portal.name.takeIf { ring.members.isNotEmpty() },
            move = if (ring.move) PhoneMove(from = portal.sessionId, sinceMs = now) else null)
        val pull = state.pull
        // "Move it back here" on this phone: the Portal's move ring is the answer to our own tap.
        if (ring.move && pull != null && pull.callId == ring.callId && pull.sessionId == portal.sessionId && now <= pull.untilMs) {
            val step = answer(PhoneCallState(call = call), call, audioOnly = false, now)
            return step.copy(effects = listOf(Fx.SendReply(call.id, portal, PortalReply.RINGING)) + step.effects)
        }
        return PhoneCallStep(PhoneCallState(call = call, ended = null),
            listOf(Fx.SendReply(call.id, portal, PortalReply.RINGING), Fx.StartRingtone, Fx.ShowIncoming(call)))
    }

    private fun answer(state: PhoneCallState, call: PhoneCall, audioOnly: Boolean, now: Long): PhoneCallStep {
        if (call.phase != PhonePhase.RINGING_IN) return PhoneCallStep(state)
        val legs = call.invited.map { PhoneLeg(it, LegPhase.JOINING, now) }
        val next = call.copy(phase = PhonePhase.CONNECTING, phaseSinceMs = now, cameraOn = call.video && !audioOnly,
            legs = legs, invited = emptyList())
        return connect(PhoneCallState(call = next), next, listOf(Fx.StopRingtone, Fx.ClearIncoming), legs.map { Fx.StartLeg(call.id, it.portal) })
    }

    private fun connect(state: PhoneCallState, next: PhoneCall, before: List<Fx> = emptyList(), after: List<Fx> = emptyList()) =
        PhoneCallStep(state.copy(call = next), before + Fx.StartMedia(next) + after)

    private fun hangUp(state: PhoneCallState, call: PhoneCall, now: Long): PhoneCallStep = when {
        call.phase == PhonePhase.RINGING_IN ->
            end(state, call, PhoneEndReason.DECLINED, now, Fx.SendReply(call.id, call.portal, PortalReply.DECLINED))
        call.phase.beforeAnswer -> end(state, call, PhoneEndReason.CANCELLED, now, Fx.SendEnd(call.id, call.portal, EndWire.CANCELLED))
        else -> end(state, call, PhoneEndReason.HUNG_UP, now, Fx.SendEnd(call.id, call.portal, EndWire.HUNG_UP))
    }

    private fun remoteEnded(state: PhoneCallState, call: PhoneCall, reason: EndWire, now: Long): PhoneCallStep {
        val mine = when {
            call.phase == PhonePhase.RINGING_IN -> PhoneEndReason.MISSED
            reason == EndWire.NO_ANSWER -> PhoneEndReason.NO_ANSWER
            reason == EndWire.DECLINED -> PhoneEndReason.REMOTE_DECLINED
            reason == EndWire.NETWORK_LOST -> PhoneEndReason.NETWORK_LOST
            reason == EndWire.FAILED || reason == EndWire.UNAVAILABLE || reason == EndWire.NO_PERMISSION -> PhoneEndReason.FAILED
            reason == EndWire.BUSY -> PhoneEndReason.BUSY
            reason == EndWire.QUIET -> PhoneEndReason.QUIET
            else -> PhoneEndReason.REMOTE_HUNG_UP
        }
        return end(state, call, mine, now)
    }

    private fun tick(state: PhoneCallState, now: Long): PhoneCallStep {
        val call = state.call
        if (call == null) {
            val pull = state.pull?.takeIf { now <= it.untilMs }
            val ended = state.ended ?: return PhoneCallStep(state.copy(pull = pull))
            // The moved screen stays (it offers "Move it back here"); the others close after a moment.
            val keep = ended.reason == PhoneEndReason.MOVED && now - ended.endedMs < MOVED_SHOW_MS
            return if (!keep && now - ended.endedMs >= PhoneCallTimings.ENDED_SHOW_MS) PhoneCallStep(state.copy(ended = null, pull = pull))
            else PhoneCallStep(state.copy(pull = pull))
        }
        PhoneGroupMachine.tick(state, call, now)?.let { return it }
        val inPhase = now - call.phaseSinceMs
        return when (call.phase) {
            PhonePhase.DIALING -> if (inPhase >= PhoneCallTimings.DIAL_ACK_MS) end(state, call, PhoneEndReason.UNREACHABLE, now,
                Fx.SendEnd(call.id, call.portal, EndWire.CANCELLED)) else PhoneCallStep(state)
            PhonePhase.RINGING_OUT -> if (inPhase >= PhoneCallTimings.RING_MS) end(state, call, PhoneEndReason.NO_ANSWER, now,
                Fx.SendEnd(call.id, call.portal, EndWire.NO_ANSWER)) else PhoneCallStep(state)
            PhonePhase.RINGING_IN -> if (inPhase >= PhoneCallTimings.RING_MS + PhoneCallTimings.RING_GRACE_MS)
                end(state, call, PhoneEndReason.MISSED, now) else PhoneCallStep(state)
            PhonePhase.CONNECTING -> if (inPhase >= PhoneCallTimings.CONNECT_MS) end(state, call, PhoneEndReason.FAILED, now,
                Fx.SendEnd(call.id, call.portal, EndWire.FAILED)) else PhoneCallStep(state)
            PhonePhase.RECONNECTING -> if (inPhase >= PhoneCallTimings.RECONNECT_MS) end(state, call, PhoneEndReason.NETWORK_LOST, now,
                Fx.SendEnd(call.id, call.portal, EndWire.NETWORK_LOST)) else PhoneCallStep(state)
            PhonePhase.LIVE -> PhoneCallStep(state)
        }
    }

    internal fun mediaState(call: PhoneCall): Fx? =
        if (call.phase == PhonePhase.CONNECTING || call.phase.connected) Fx.SendMedia(call) else null

    private fun next(state: PhoneCallState, call: PhoneCall) = PhoneCallStep(state.copy(call = call))

    /**
     * Ends the call here. [first] goes to the first Portal; every other member hears a hang-up
     * (or, for [PhoneEndReason.MOVED], where the call went), and a Portal still ringing for a
     * move is told it was cancelled.
     */
    internal fun end(state: PhoneCallState, call: PhoneCall, reason: PhoneEndReason, now: Long, first: Fx? = null,
                     quietEndMin: Int? = null, movedTo: PortalRef? = null): PhoneCallStep {
        val ended = EndedPhoneCall(call.id, call.portal, call.direction, reason, call.connectedMs, now, quietEndMin, movedTo)
        val effects = buildList {
            first?.let(::add)
            call.legs.forEach { add(Fx.SendEnd(call.id, it.portal, EndWire.HUNG_UP, movedTo?.portalId)) }
            call.move?.takeIf { it.ringing }?.to?.let { to -> PhoneGroupMachine.target(call, to)?.let { add(Fx.SendEnd(call.id, it, EndWire.CANCELLED)) } }
            add(Fx.StopRingtone)
            add(Fx.StopMedia)
            add(Fx.ClearIncoming)
            if (reason == PhoneEndReason.MISSED) add(Fx.NotifyMissed(ended))
        }
        return PhoneCallStep(PhoneCallState(call = null, ended = ended, pull = state.pull), effects)
    }

    /** "Call moved to Kitchen" stays this long, offering to move it back. */
    private const val MOVED_SHOW_MS = 60_000L
}
