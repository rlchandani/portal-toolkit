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
 * The group half of [PhoneCallMachine] (Calling 2b), pure. A phone has no server, so it offers to
 * every Portal in the call through its own paired session there:
 * - Rung into a group: on Answer it offers to the ringing Portal and to each member it named.
 * - A member says a Portal joined (`call_join`, from a *connected* member only): it offers to it.
 * - "To Portal": it rings the target with the members; when the target says yes (`call_go`) it
 *   offers to it and vouches for it to every connected member, then leaves once the target says
 *   it is connected to everyone (`call_moved`) or after [PhoneCallTimings.MOVE_SETTLE_MS].
 * - Moved here from a Portal: once connected to everyone it says `call_moved`; when that Portal
 *   leaves, only it may ask for the call back ("Move it back here", `call_pull`).
 * A call answered by itself on the Portal (Grandma mode) is never widened or moved.
 */
object PhoneGroupMachine {
    /** Messages about this call from a Portal other than the first; null when it is the first's to handle. */
    fun fromOther(state: PhoneCallState, call: PhoneCall, e: Ev.FromPortal): PhoneCallStep? {
        val msg = e.message
        val s = e.sessionId
        val leg = call.leg(s)
        val move = call.move
        return when {
            move?.to == s && leg == null -> fromTarget(state, call, msg, e.now)
            leg != null -> fromLeg(state, call, leg, msg, e.directory, e.now)
            msg is PortalMessage.Pull && call.cameFrom == s -> pulled(state, call, e.directory[e.portalId], e.now)
            msg is PortalMessage.Moved && move?.to == s -> moved(state, call, e.now)
            s != call.portal.sessionId -> PhoneCallStep(state)
            msg is PortalMessage.Moved -> moved(state, call, e.now)
            else -> null
        }
    }

    private fun fromLeg(state: PhoneCallState, call: PhoneCall, leg: PhoneLeg, msg: PortalMessage, directory: Map<String, PortalRef>,
                        now: Long): PhoneCallStep = when (msg) {
        is PortalMessage.End -> drop(state, call, leg, now, tell = false, text = endText(call, leg.portal.name, msg.movedTo))
        is PortalMessage.Media -> step(state, call.replaceLeg(leg.copy(remoteCameraOn = msg.cameraOn, remoteMicOn = msg.micOn))
            .copy(remoteAutoAnswered = call.remoteAutoAnswered || msg.autoAnswered))
        is PortalMessage.Join -> join(state, call, leg.portal.sessionId, msg, directory, now)
        is PortalMessage.Moved -> if (call.move?.to == leg.portal.sessionId) moved(state, call, now) else PhoneCallStep(state)
        else -> PhoneCallStep(state)
    }

    /** The Portal we are moving to, before it joined: its yes, its no, or its hang-up. */
    private fun fromTarget(state: PhoneCallState, call: PhoneCall, msg: PortalMessage, now: Long): PhoneCallStep {
        val move = call.move ?: return PhoneCallStep(state)
        val target = move.target ?: return PhoneCallStep(state)
        return when (msg) {
            is PortalMessage.Go -> if (!move.ringing || !call.phase.connected) PhoneCallStep(state) else {
                val leg = PhoneLeg(target, LegPhase.JOINING, now, moving = true)
                val next = call.copy(legs = call.legs + leg, move = move.copy(ringing = false))
                val vouch = connected(call).map { Fx.SendJoin(call.id, it, target) }
                PhoneCallStep(state.copy(call = next), listOf(Fx.StartLeg(call.id, target)) + vouch)
            }
            is PortalMessage.Reply -> if (msg.reply == PortalReply.RINGING || msg.reply == PortalReply.COUNTDOWN) PhoneCallStep(state)
            else cancelMove(state, call, now, "${target.name} can’t take the call right now · it stays here")
            is PortalMessage.End -> cancelMove(state, call, now, "${target.name} didn’t pick up · the call stays here")
            else -> PhoneCallStep(state)
        }
    }

    /** A connected member vouches for a Portal that joined: we offer to it through our own session there. */
    fun join(state: PhoneCallState, call: PhoneCall, from: String, msg: PortalMessage.Join, directory: Map<String, PortalRef>,
             now: Long): PhoneCallStep {
        val newcomer = directory[msg.portalId] ?: return PhoneCallStep(state)
        val vouched = call.phase.connected && (call.portal.sessionId == from && call.phase.connected || call.leg(from)?.phase?.connected == true)
        if (!vouched || call.remoteAutoAnswered || call.isMember(newcomer.sessionId) || call.legs.size >= PhoneGroup.MAX_LEGS) {
            return PhoneCallStep(state)
        }
        val next = call.copy(legs = call.legs + PhoneLeg(newcomer, LegPhase.JOINING, now))
        return PhoneCallStep(state.copy(call = next), listOfNotNull(Fx.StartLeg(call.id, newcomer), PhoneCallMachine.mediaState(next)))
    }

    /** The first Portal hung up: if another is connected it carries on as the first; else null (the call ends). */
    fun primaryEnded(state: PhoneCallState, call: PhoneCall, msg: PortalMessage.End, now: Long): PhoneCallStep? {
        if (!call.phase.connected || call.legs.none { it.phase.connected }) return null
        return handOver(state, call, now, null, endText(call, call.portal.name, msg.movedTo))
    }

    fun legUp(state: PhoneCallState, call: PhoneCall, sessionId: String, now: Long): PhoneCallStep {
        val leg = call.leg(sessionId) ?: return PhoneCallStep(state)
        if (leg.phase == LegPhase.LIVE) return PhoneCallStep(state)
        val live = call.replaceLeg(leg.copy(phase = LegPhase.LIVE, sinceMs = now, connectedMs = leg.connectedMs ?: now))
        val move = call.move?.takeIf { leg.moving && it.to == sessionId && it.targetLiveMs == null }
        return step(state, if (move != null) live.copy(move = move.copy(targetLiveMs = now)) else live)
    }

    fun legDown(state: PhoneCallState, call: PhoneCall, sessionId: String, now: Long): PhoneCallStep {
        val leg = call.leg(sessionId)?.takeIf { it.phase == LegPhase.LIVE } ?: return PhoneCallStep(state)
        return step(state, call.replaceLeg(leg.copy(phase = LegPhase.RECONNECTING, sinceMs = now)))
    }

    fun legFailed(state: PhoneCallState, call: PhoneCall, sessionId: String, now: Long): PhoneCallStep {
        val leg = call.leg(sessionId) ?: return PhoneCallStep(state)
        return drop(state, call, leg, now, tell = true, text = "${leg.portal.name} couldn’t connect", reason = EndWire.FAILED)
    }

    /** Leg timeouts, the move's timers, and the first Portal's own reconnect handover; null when nothing changed. */
    fun tick(state: PhoneCallState, call: PhoneCall, now: Long): PhoneCallStep? {
        for (leg in call.legs) {
            val age = now - leg.sinceMs
            when {
                leg.phase == LegPhase.JOINING && age >= PhoneCallTimings.CONNECT_MS ->
                    return drop(state, call, leg, now, tell = true, text = "${leg.portal.name} couldn’t connect", reason = EndWire.FAILED)
                leg.phase == LegPhase.RECONNECTING && age >= PhoneCallTimings.RECONNECT_MS ->
                    return drop(state, call, leg, now, tell = true, text = "${leg.portal.name} lost the connection", reason = EndWire.NETWORK_LOST)
            }
        }
        val move = call.move
        if (move != null && move.ringing && now - move.sinceMs >= PhoneCallTimings.RING_MS) {
            val target = move.target
            val step = cancelMove(state, call, now, "${target?.name ?: "The Portal"} didn’t pick up · the call stays here")
            return if (target == null) step else step.copy(effects = step.effects + Fx.SendEnd(call.id, target, EndWire.NO_ANSWER))
        }
        val live = move?.targetLiveMs
        if (live != null && targetLive(call) && now - live >= PhoneCallTimings.MOVE_SETTLE_MS) return moved(state, call, now)
        if (call.phase == PhonePhase.RECONNECTING && now - call.phaseSinceMs >= PhoneCallTimings.RECONNECT_MS &&
            call.legs.any { it.phase.connected }) {
            return handOver(state, call, now, Fx.SendEnd(call.id, call.portal, EndWire.NETWORK_LOST), "${call.portal.name} lost the connection")
        }
        return null
    }

    /** "To Portal": ring [target] with the members, as long as there is a seat and the call isn't locked. */
    fun moveTo(state: PhoneCallState, call: PhoneCall, target: PortalRef, now: Long): PhoneCallStep {
        if (!canMove(call, target)) return PhoneCallStep(state)
        val next = call.copy(move = PhoneMove(to = target.sessionId, ringing = true, sinceMs = now, target = target), notice = null)
        return PhoneCallStep(state.copy(call = next), listOf(Fx.SendMoveRing(next, target, call.members)))
    }

    /** Whether "To Portal" can do anything at all right now (a target still has to be picked). */
    fun canMoveAtAll(call: PhoneCall): Boolean =
        call.phase == PhonePhase.LIVE && !call.remoteAutoAnswered && call.move == null && call.people < PhoneGroup.MAX_PEOPLE

    fun canMove(call: PhoneCall, target: PortalRef): Boolean =
        call.phase == PhonePhase.LIVE && !call.remoteAutoAnswered && call.move == null && !call.isMember(target.sessionId) &&
            !call.isMemberPortal(target.portalId) && call.people < PhoneGroup.MAX_PEOPLE

    fun moveRingSent(state: PhoneCallState, call: PhoneCall, e: Ev.MoveRingSent): PhoneCallStep {
        val move = call.move
        if (e.id != call.id || move == null || move.to != e.sessionId || !move.ringing) return PhoneCallStep(state)
        val name = move.target?.name ?: "The Portal"
        return when (e.reply) {
            PortalReply.RINGING, PortalReply.COUNTDOWN -> PhoneCallStep(state)
            PortalReply.QUIET -> cancelMove(state, call, e.now, "$name is in quiet hours · the call stays here")
            PortalReply.BUSY -> cancelMove(state, call, e.now, "$name is in another call · the call stays here")
            null -> cancelMove(state, call, e.now, "Couldn’t reach $name · the call stays here")
            else -> cancelMove(state, call, e.now, "$name can’t take the call right now · it stays here")
        }
    }

    /** After a move to a Portal: ask it for the call back; its move ring is then answered without another tap. */
    fun pullBack(state: PhoneCallState, now: Long): PhoneCallStep {
        val ended = state.ended?.takeIf { it.reason == PhoneEndReason.MOVED } ?: return PhoneCallStep(state)
        val to = ended.movedTo ?: return PhoneCallStep(state)
        if (state.call != null) return PhoneCallStep(state)
        return PhoneCallStep(state.copy(pull = PendingPull(ended.id, to.sessionId, now + PhoneCallTimings.PULL_BACK_MS)),
            listOf(Fx.SendPull(ended.id, to)))
    }

    /** The move target's side: once connected to everyone, tell the Portal we took over from, once. */
    fun settle(step: PhoneCallStep): PhoneCallStep {
        val call = step.state.call ?: return step
        val move = call.move ?: return step
        val from = move.from ?: return step
        if (move.announced || call.phase != PhonePhase.LIVE || call.legs.any { it.phase != LegPhase.LIVE }) return step
        val to = call.members.firstOrNull { it.sessionId == from } ?: return step
        return PhoneCallStep(step.state.copy(call = call.copy(move = move.copy(announced = true))), step.effects + Fx.SendMoved(call.id, to))
    }

    fun target(call: PhoneCall, sessionId: String): PortalRef? = call.move?.target?.takeIf { it.sessionId == sessionId }

    /** The Portal we moved from asks for the call back: move it there (its own tap was the consent). */
    private fun pulled(state: PhoneCallState, call: PhoneCall, portal: PortalRef?, now: Long): PhoneCallStep =
        if (portal == null || !canMove(call, portal)) PhoneCallStep(state) else moveTo(state, call, portal, now)

    private fun moved(state: PhoneCallState, call: PhoneCall, now: Long): PhoneCallStep {
        val target = call.move?.target ?: return PhoneCallStep(state)
        if (!targetLive(call)) return PhoneCallStep(state)
        return PhoneCallMachine.end(state, call, PhoneEndReason.MOVED, now,
            Fx.SendEnd(call.id, call.portal, EndWire.HUNG_UP, target.portalId), movedTo = target)
    }

    private fun targetLive(call: PhoneCall): Boolean {
        val to = call.move?.to ?: return false
        return call.leg(to)?.phase == LegPhase.LIVE || (call.portal.sessionId == to && call.phase == PhonePhase.LIVE)
    }

    private fun cancelMove(state: PhoneCallState, call: PhoneCall, now: Long, text: String): PhoneCallStep {
        val to = call.move?.to
        val leg = to?.let { call.leg(it) }
        val next = call.copy(move = null, legs = call.legs.filterNot { it.moving }, notice = PhoneNotice(call.move?.target?.name ?: "", text, now))
        return PhoneCallStep(state.copy(call = next), if (leg != null) listOf(Fx.ClosePeer(leg.portal.sessionId)) else emptyList())
    }

    private fun drop(state: PhoneCallState, call: PhoneCall, leg: PhoneLeg, now: Long, tell: Boolean, text: String,
                     reason: EndWire = EndWire.HUNG_UP): PhoneCallStep {
        val moveOff = leg.moving && call.move?.to == leg.portal.sessionId
        val next = call.copy(legs = call.legs - leg, notice = PhoneNotice(leg.portal.name,
            if (moveOff) "${leg.portal.name} didn’t pick up · the call stays here" else text, now),
            move = if (moveOff) null else call.move?.takeUnless { it.from == leg.portal.sessionId },
            cameFrom = if (call.move?.from == leg.portal.sessionId) leg.portal.sessionId else call.cameFrom)
        val effects = buildList {
            if (tell) add(Fx.SendEnd(call.id, leg.portal, reason))
            add(Fx.ClosePeer(leg.portal.sessionId))
        }
        return PhoneCallStep(state.copy(call = next), effects)
    }

    /** The first Portal left; the next connected one takes its place and the call carries on. */
    private fun handOver(state: PhoneCallState, call: PhoneCall, now: Long, first: Fx?, text: String): PhoneCallStep {
        val heir = call.legs.firstOrNull { it.phase.connected } ?: call.legs.first()
        val phase = if (heir.phase == LegPhase.LIVE) PhonePhase.LIVE else if (heir.phase == LegPhase.RECONNECTING) PhonePhase.RECONNECTING
        else PhonePhase.CONNECTING
        val old = call.portal
        val next = call.copy(portal = heir.portal, phase = phase, phaseSinceMs = now, remoteCameraOn = heir.remoteCameraOn,
            remoteMicOn = heir.remoteMicOn, legs = call.legs - heir, notice = PhoneNotice(old.name, text, now),
            // The Portal we moved from just left: the move is done, and only it may ask for the call back.
            move = call.move?.takeUnless { it.from == old.sessionId },
            cameFrom = if (call.move?.from == old.sessionId) old.sessionId else call.cameFrom)
        return PhoneCallStep(state.copy(call = next), listOfNotNull(first, Fx.ClosePeer(old.sessionId)))
    }

    private fun endText(call: PhoneCall, name: String, movedTo: String?): String {
        val to = movedTo?.let { id -> call.members.firstOrNull { it.portalId == id }?.name }
        return when {
            movedTo == null -> "$name left the call"
            to != null -> "$name moved the call to $to"
            else -> "$name handed the call over"
        }
    }

    private fun connected(call: PhoneCall): List<PortalRef> = buildList {
        if (call.phase.connected) add(call.portal)
        call.legs.filter { it.phase.connected }.forEach { add(it.portal) }
    }

    private fun PhoneCall.replaceLeg(leg: PhoneLeg) = copy(legs = legs.map { if (it.portal.sessionId == leg.portal.sessionId) leg else it })

    private fun step(state: PhoneCallState, call: PhoneCall) = PhoneCallStep(state.copy(call = call))
}
