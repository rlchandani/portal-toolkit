/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.calls

import com.portalinstaller.data.calls.PhoneCallEffect as Fx
import com.portalinstaller.data.calls.PhoneCallEvent as Ev
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Calling 2b on the phone: group calls, vouching, Grandma locks, and moving a call both ways. */
class PhoneGroupMachineTest {
    private val t0 = 1_000_000L
    private val id = "0123456789abcdef"
    private val living = PortalRef("s1", "11111111-1111-1111-1111-111111111111", "Living room")
    private val kitchen = PortalRef("s2", "22222222-2222-2222-2222-222222222222", "Kitchen")
    private val den = PortalRef("s3", "33333333-3333-3333-3333-333333333333", "Den")
    private val directory = listOf(living, kitchen, den).associateBy { it.portalId }
    private fun reduce(s: PhoneCallState, e: Ev) = PhoneCallMachine.reduce(s, e)
    private fun from(p: PortalRef, m: PortalMessage, at: Long) = Ev.FromPortal(p.sessionId, p.portalId, m, at, directory)

    /** Living room rang us into a call that already has Kitchen. */
    private fun groupRing(move: Boolean = false) =
        reduce(PhoneCallState(), from(living, PortalMessage.Ring(id, true, "Living room", listOf(MemberRef(kitchen.portalId, "Kitchen")), move), t0))

    private fun answered(move: Boolean = false) = reduce(groupRing(move).state, Ev.Answer(false, t0 + 1_000))

    private fun allLive(move: Boolean = false): PhoneCallState {
        var s = answered(move).state
        s = reduce(s, Ev.MediaUp(id, t0 + 2_000, living.sessionId)).state
        return reduce(s, Ev.MediaUp(id, t0 + 2_500, kitchen.sessionId)).state
    }

    /** A 1:1 call with the Living room, live. */
    private fun solo(): PhoneCallState {
        var s = reduce(PhoneCallState(), from(living, PortalMessage.Ring(id, true, "Living room"), t0)).state
        s = reduce(s, Ev.Answer(false, t0 + 500)).state
        return reduce(s, Ev.MediaUp(id, t0 + 1_000, living.sessionId)).state
    }

    @Test fun aGroupRingShowsWhoIsInItAndAnswerOffersToEveryone() {
        val ring = groupRing()
        val call = ring.state.call!!
        assertEquals(listOf(kitchen), call.invited)
        assertEquals(PhoneCallCopy.Ring("Group video call", "Living room added you to a call with Kitchen", "Answer"), PhoneCallCopy.ring(call))
        val step = answered()
        assertTrue(step.effects.any { it is Fx.StartMedia })
        assertEquals(listOf(Fx.StartLeg(id, kitchen)), step.effects.filterIsInstance<Fx.StartLeg>())
        assertEquals(LegPhase.JOINING, step.state.call!!.leg(kitchen.sessionId)!!.phase)
    }

    @Test fun membersWeAreNotPairedWithAreLeftOut() {
        val s = reduce(PhoneCallState(), from(living, PortalMessage.Ring(id, true, "Living room",
            listOf(MemberRef("44444444-4444-4444-4444-444444444444", "Shed"))), t0)).state
        assertTrue(s.call!!.invited.isEmpty())
    }

    @Test fun onlyAConnectedMemberCanVouchForANewcomer() {
        val s = allLive()
        val joined = reduce(s, from(living, PortalMessage.Join(id, den.portalId, "Den"), t0 + 5_000))
        assertEquals(listOf(Fx.StartLeg(id, den)), joined.effects.filterIsInstance<Fx.StartLeg>())
        // Not yet connected (just answered): no vouch counts.
        val early = answered().state
        assertEquals(early, reduce(early, from(kitchen, PortalMessage.Join(id, den.portalId, "Den"), t0 + 1_100)).state)
        // Unknown Portal, or already a member: nothing.
        assertEquals(s, reduce(s, from(living, PortalMessage.Join(id, "55555555-5555-5555-5555-555555555555", "X"), t0 + 5_000)).state)
        assertEquals(s, reduce(s, from(living, PortalMessage.Join(id, kitchen.portalId, "Kitchen"), t0 + 5_000)).state)
    }

    @Test fun aMemberLeavingClosesOnlyItsLink() {
        val step = reduce(allLive(), from(kitchen, PortalMessage.End(id, EndWire.HUNG_UP), t0 + 9_000))
        assertNull(step.state.call!!.leg(kitchen.sessionId))
        assertEquals("Kitchen left the call", step.state.call!!.notice!!.text)
        assertTrue(step.effects.contains(Fx.ClosePeer(kitchen.sessionId)))
        assertTrue(step.effects.none { it is Fx.StopMedia })
    }

    @Test fun whenTheFirstPortalLeavesTheNextCarriesOn() {
        val step = reduce(allLive(), from(living, PortalMessage.End(id, EndWire.HUNG_UP), t0 + 9_000))
        val call = step.state.call!!
        assertEquals(kitchen, call.portal)
        assertEquals(PhonePhase.LIVE, call.phase)
        assertTrue(step.effects.contains(Fx.ClosePeer(living.sessionId)))
    }

    @Test fun aLegThatCannotConnectIsDroppedAndTold() {
        val step = reduce(answered().state, Ev.OfferDone(id, false, t0 + 3_000, kitchen.sessionId))
        assertNull(step.state.call!!.leg(kitchen.sessionId))
        assertTrue(step.effects.contains(Fx.SendEnd(id, kitchen, EndWire.FAILED)))
        assertEquals(PhonePhase.CONNECTING, step.state.call!!.phase)
    }

    @Test fun grandmaCallsAreNeitherWidenedNorMoved() {
        var s = solo()
        s = reduce(s, from(living, PortalMessage.Media(id, true, true, autoAnswered = true), t0 + 2_000)).state
        assertTrue(s.call!!.remoteAutoAnswered)
        assertFalse(PhoneGroupMachine.canMoveAtAll(s.call!!))
        assertEquals(s, reduce(s, Ev.MoveTo(kitchen, t0 + 3_000)).state)
        assertEquals(s, reduce(s, from(living, PortalMessage.Join(id, kitchen.portalId, "Kitchen"), t0 + 3_000)).state)
    }

    @Test fun movedHereFromAPortalWeSayWhenEveryoneIsConnectedOnce() {
        val ring = groupRing(move = true).state.call!!
        assertEquals("Continue on this phone", PhoneCallCopy.ring(ring).title)
        assertEquals("Pick up", PhoneCallCopy.ring(ring).answer)
        var s = answered(move = true).state
        s = reduce(s, Ev.MediaUp(id, t0 + 2_000, living.sessionId)).state
        val last = reduce(s, Ev.MediaUp(id, t0 + 2_500, kitchen.sessionId))
        assertEquals(listOf(Fx.SendMoved(id, living)), last.effects.filterIsInstance<Fx.SendMoved>())
        val again = reduce(reduce(last.state, Ev.MediaDown(id, t0 + 3_000, kitchen.sessionId)).state, Ev.MediaUp(id, t0 + 3_200, kitchen.sessionId))
        assertTrue(again.effects.none { it is Fx.SendMoved })
        // The Living room leaves (it moved the call to us): Kitchen carries on, and only the Living room may pull it back.
        val left = reduce(again.state, from(living, PortalMessage.End(id, EndWire.HUNG_UP, movedTo = "ph0123456789abcdef"), t0 + 4_000)).state
        assertEquals(kitchen, left.call!!.portal)
        assertEquals(living.sessionId, left.call!!.cameFrom)
        assertNull(left.call!!.move)
        assertEquals(left, reduce(left, from(kitchen, PortalMessage.Pull(id), t0 + 5_000)).state)
        assertEquals("too late", left, reduce(left, from(living, PortalMessage.Pull(id), t0 + 4_001 + PhoneCallTimings.PULL_WINDOW_MS)).state)
        val pulled = reduce(left, from(living, PortalMessage.Pull(id), t0 + 5_000))
        val ringBack = pulled.effects.filterIsInstance<Fx.SendMoveRing>().single()
        assertEquals(living, ringBack.to)
        assertEquals(listOf(kitchen), ringBack.members)
    }

    @Test fun toPortalRingsItThenVouchesThenLeavesOnceItIsIn() {
        val s = solo()
        val ring = reduce(s, Ev.MoveTo(kitchen, t0 + 5_000))
        val sent = ring.effects.filterIsInstance<Fx.SendMoveRing>().single()
        assertEquals(kitchen, sent.to)
        assertEquals(listOf(living), sent.members)
        assertEquals("Ringing Kitchen · pick up there to move the call", PhoneCallCopy.movingLine(ring.state.call!!))
        var st = reduce(ring.state, Ev.MoveRingSent(id, kitchen.sessionId, PortalReply.RINGING, t0 + 5_100)).state
        val go = reduce(st, from(kitchen, PortalMessage.Go(id), t0 + 8_000))
        assertTrue(go.effects.contains(Fx.StartLeg(id, kitchen)))
        assertTrue(go.effects.contains(Fx.SendJoin(id, living, kitchen)))
        st = reduce(go.state, Ev.MediaUp(id, t0 + 9_000, kitchen.sessionId)).state
        assertEquals(t0 + 9_000, st.call!!.move!!.targetLiveMs)
        val done = reduce(st, from(kitchen, PortalMessage.Moved(id), t0 + 9_500))
        assertNull(done.state.call)
        assertEquals(PhoneEndReason.MOVED, done.state.ended!!.reason)
        assertEquals(kitchen, done.state.ended!!.movedTo)
        val ends = done.effects.filterIsInstance<Fx.SendEnd>()
        assertTrue(ends.contains(Fx.SendEnd(id, living, EndWire.HUNG_UP, kitchen.portalId)))
        assertTrue(ends.contains(Fx.SendEnd(id, kitchen, EndWire.HUNG_UP, kitchen.portalId)))
        assertEquals("Call moved to Kitchen", PhoneCallCopy.movedTitle(done.state.ended!!))
    }

    @Test fun withoutTheSignalThePhoneStillLeavesOnceThePortalHasBeenInAWhile() {
        var st = reduce(solo(), Ev.MoveTo(kitchen, t0 + 5_000)).state
        st = reduce(st, from(kitchen, PortalMessage.Go(id), t0 + 8_000)).state
        st = reduce(st, Ev.MediaUp(id, t0 + 9_000, kitchen.sessionId)).state
        assertNotNull(reduce(st, Ev.Tick(t0 + 9_000 + PhoneCallTimings.MOVE_SETTLE_MS - 1)).state.call)
        assertEquals(PhoneEndReason.MOVED, reduce(st, Ev.Tick(t0 + 9_000 + PhoneCallTimings.MOVE_SETTLE_MS)).state.ended!!.reason)
    }

    @Test fun aMoveThatDoesNotHappenLeavesTheCallHere() {
        val st = reduce(solo(), Ev.MoveTo(kitchen, t0 + 5_000)).state
        val quiet = reduce(st, Ev.MoveRingSent(id, kitchen.sessionId, PortalReply.QUIET, t0 + 5_100)).state.call!!
        assertNull(quiet.move)
        assertEquals("Kitchen is in quiet hours · the call stays here", quiet.notice!!.text)
        val noAnswer = reduce(st, Ev.Tick(t0 + 5_000 + PhoneCallTimings.RING_MS))
        assertNull(noAnswer.state.call!!.move)
        assertTrue(noAnswer.effects.contains(Fx.SendEnd(id, kitchen, EndWire.NO_ANSWER)))
        val declined = reduce(st, from(kitchen, PortalMessage.Reply(id, PortalReply.DECLINED, null), t0 + 7_000)).state.call!!
        assertNull(declined.move)
        assertEquals(PhonePhase.LIVE, declined.phase)
    }

    @Test fun aMoveNeedsAFreeSeatAndAPortalNotInTheCall() {
        val s = allLive()
        assertFalse(PhoneGroupMachine.canMove(s.call!!, kitchen))
        assertTrue(PhoneGroupMachine.canMove(s.call!!, den))
        val four = reduce(s, from(living, PortalMessage.Join(id, den.portalId, "Den"), t0 + 5_000)).state
        assertFalse(PhoneGroupMachine.canMoveAtAll(four.call!!))
    }

    @Test fun moveItBackHereIsAnsweredWithoutASecondTap() {
        var st = reduce(solo(), Ev.MoveTo(kitchen, t0 + 5_000)).state
        st = reduce(st, from(kitchen, PortalMessage.Go(id), t0 + 8_000)).state
        st = reduce(st, Ev.MediaUp(id, t0 + 9_000, kitchen.sessionId)).state
        st = reduce(st, from(kitchen, PortalMessage.Moved(id), t0 + 9_500)).state
        val pull = reduce(st, Ev.PullBack(t0 + 20_000))
        assertEquals(listOf(Fx.SendPull(id, kitchen)), pull.effects)
        val ring = reduce(pull.state, from(kitchen, PortalMessage.Ring(id, true, "Kitchen", listOf(MemberRef(living.portalId, "Living room")), true),
            t0 + 21_000))
        assertEquals(PhonePhase.CONNECTING, ring.state.call!!.phase)
        assertTrue(ring.effects.any { it is Fx.StartMedia })
        assertTrue(ring.effects.none { it is Fx.StartRingtone })
        // After the window a move ring rings normally.
        val late = reduce(pull.state, from(kitchen, PortalMessage.Ring(id, true, "Kitchen", emptyList(), true),
            t0 + 20_000 + PhoneCallTimings.PULL_BACK_MS + 1))
        assertEquals(PhonePhase.RINGING_IN, late.state.call!!.phase)
    }

    @Test fun hangingUpTellsEveryoneAndCancelsAPendingMove() {
        val st = reduce(allLive(), Ev.MoveTo(den, t0 + 5_000)).state
        val step = reduce(st, Ev.HangUp(t0 + 6_000))
        val ends = step.effects.filterIsInstance<Fx.SendEnd>()
        assertTrue(ends.contains(Fx.SendEnd(id, living, EndWire.HUNG_UP)))
        assertTrue(ends.contains(Fx.SendEnd(id, kitchen, EndWire.HUNG_UP)))
        assertTrue(ends.contains(Fx.SendEnd(id, den, EndWire.CANCELLED)))
        assertTrue(step.effects.contains(Fx.StopMedia))
    }

    @Test fun groupCopy() {
        assertEquals("Living room, Kitchen and you", PhoneCallCopy.title(allLive().call!!))
        assertEquals("Kitchen · Portal", PhoneCallCopy.tile(kitchen))
        assertEquals("A, B and C", PhoneCallCopy.names(listOf("A", "B", "C")))
    }
}
