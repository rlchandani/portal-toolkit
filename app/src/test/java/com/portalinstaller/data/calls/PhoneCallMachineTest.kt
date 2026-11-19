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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneCallMachineTest {
    private val t0 = 1_000_000L
    private val id = "0123456789abcdef"
    private val kitchen = PortalRef("s1", PortalSide.PORTAL_ID, "Kitchen")
    private fun reduce(s: PhoneCallState, e: Ev) = PhoneCallMachine.reduce(s, e)
    private fun from(m: PortalMessage, at: Long = t0, session: String = "s1") = Ev.FromPortal(session, PortalSide.PORTAL_ID, m, at)

    private fun dialled(video: Boolean = true) = reduce(PhoneCallState(), Ev.Dial(id, kitchen, video, t0))
    private fun ringingOut() = reduce(dialled().state, Ev.RingSent(id, PortalReply.RINGING, null, t0 + 100)).state
    private fun ringingIn(video: Boolean = true) = reduce(PhoneCallState(), from(PortalMessage.Ring(id, video, "Kitchen")))
    private fun live(): PhoneCallState {
        val c = reduce(ringingOut(), from(PortalMessage.Go(id), t0 + 1_000)).state
        return reduce(c, Ev.MediaUp(id, t0 + 2_000)).state
    }

    @Test fun dialSendsTheRingAndOpensNothing() {
        val s = dialled()
        assertEquals(PhonePhase.DIALING, s.state.call!!.phase)
        assertEquals(listOf(Fx.SendRing(s.state.call!!)), s.effects)
        assertTrue(s.effects.none { it is Fx.StartMedia })
        // Only one call at a time.
        assertEquals(s.state, reduce(s.state, Ev.Dial("ffffffffffffffff", kitchen, true, t0)).state)
    }

    @Test fun portalDecisionsEndTheDialHonestly() {
        val cases = mapOf(PortalReply.QUIET to PhoneEndReason.QUIET, PortalReply.BUSY to PhoneEndReason.BUSY,
            PortalReply.CALLS_OFF to PhoneEndReason.CALLS_OFF, PortalReply.DECLINED to PhoneEndReason.REMOTE_DECLINED)
        cases.forEach { (reply, reason) ->
            val s = reduce(dialled().state, Ev.RingSent(id, reply, 420, t0 + 50))
            assertNull(s.state.call)
            assertEquals(reason, s.state.ended!!.reason)
            assertTrue(Fx.StopMedia in s.effects)
        }
        assertEquals(420, reduce(dialled().state, Ev.RingSent(id, PortalReply.QUIET, 420, t0)).state.ended!!.quietEndMin)
        assertEquals(PhoneEndReason.UNREACHABLE, reduce(dialled().state, Ev.RingSent(id, null, null, t0)).state.ended!!.reason)
    }

    @Test fun goOpensMediaOnlyForTheCallerSide() {
        val s = reduce(ringingOut(), from(PortalMessage.Go(id), t0 + 1_000))
        assertEquals(PhonePhase.CONNECTING, s.state.call!!.phase)
        assertTrue(s.effects.single() is Fx.StartMedia)
        // A go for another call, or over another Portal's session, is ignored.
        assertEquals(ringingOut(), reduce(ringingOut(), from(PortalMessage.Go("ffffffffffffffff"))).state)
        assertEquals(ringingOut(), reduce(ringingOut(), from(PortalMessage.Go(id), session = "s2")).state)
    }

    @Test fun incomingRingsAndRepliesWithoutOpeningMedia() {
        val s = ringingIn()
        assertEquals(PhonePhase.RINGING_IN, s.state.call!!.phase)
        assertEquals(kitchen, s.state.call!!.portal)
        assertEquals(listOf(Fx.SendReply(id, kitchen, PortalReply.RINGING), Fx.StartRingtone, Fx.ShowIncoming(s.state.call!!)), s.effects)
        assertEquals(s.state, reduce(s.state, from(PortalMessage.Ring(id, true, "Kitchen"))).state)
    }

    @Test fun secondRingWhileBusyIsToldBusy() {
        val other = reduce(live(), Ev.FromPortal("s2", "den", PortalMessage.Ring("ffffffffffffffff", true, "Den"), t0))
        assertEquals(listOf(Fx.SendReply("ffffffffffffffff", PortalRef("s2", "den", "Den"), PortalReply.BUSY)), other.effects)
        assertEquals(id, other.state.call!!.id)
    }

    @Test fun answerOpensMediaAudioOnlyClearsCamera() {
        val video = reduce(ringingIn().state, Ev.Answer(audioOnly = false, now = t0 + 500))
        assertEquals(listOf(Fx.StopRingtone, Fx.ClearIncoming, Fx.StartMedia(video.state.call!!)), video.effects)
        assertTrue(video.state.call!!.cameraOn)
        val audio = reduce(ringingIn().state, Ev.Answer(audioOnly = true, now = t0 + 500))
        assertTrue(!audio.state.call!!.cameraOn)
        // Answer is meaningless once connecting.
        assertEquals(video.state, reduce(video.state, Ev.Answer(false, t0 + 600)).state)
    }

    @Test fun declineAndHangUpTellThePortal() {
        val d = reduce(ringingIn().state, Ev.Decline(t0))
        assertEquals(Fx.SendReply(id, kitchen, PortalReply.DECLINED), d.effects.first())
        assertEquals(PhoneEndReason.DECLINED, d.state.ended!!.reason)
        assertEquals(Fx.SendReply(id, kitchen, PortalReply.DECLINED), reduce(ringingIn().state, Ev.HangUp(t0)).effects.first())
        assertEquals(Fx.SendEnd(id, kitchen, EndWire.CANCELLED), reduce(ringingOut(), Ev.HangUp(t0)).effects.first())
        val h = reduce(live(), Ev.HangUp(t0 + 9_000))
        assertEquals(Fx.SendEnd(id, kitchen, EndWire.HUNG_UP), h.effects.first())
        assertEquals(t0 + 2_000, h.state.ended!!.connectedMs)
        assertEquals(PhoneCallState(), reduce(PhoneCallState(), Ev.Decline(t0)).state)
        assertEquals(live(), reduce(live(), Ev.Decline(t0)).state)
    }

    @Test fun remoteEndsMapToReasonsAndMissedNotifies() {
        val missed = reduce(ringingIn().state, from(PortalMessage.End(id, EndWire.CANCELLED)))
        assertEquals(PhoneEndReason.MISSED, missed.state.ended!!.reason)
        assertTrue(missed.effects.any { it is Fx.NotifyMissed })
        val map = mapOf(EndWire.NO_ANSWER to PhoneEndReason.NO_ANSWER, EndWire.DECLINED to PhoneEndReason.REMOTE_DECLINED,
            EndWire.NETWORK_LOST to PhoneEndReason.NETWORK_LOST, EndWire.FAILED to PhoneEndReason.FAILED,
            EndWire.BUSY to PhoneEndReason.BUSY, EndWire.QUIET to PhoneEndReason.QUIET, EndWire.LEFT to PhoneEndReason.REMOTE_HUNG_UP)
        map.forEach { (wire, reason) -> assertEquals(reason, reduce(live(), from(PortalMessage.End(id, wire))).state.ended!!.reason) }
    }

    @Test fun mediaLifecycleAndRemoteState() {
        val l = live()
        assertEquals(PhonePhase.LIVE, l.call!!.phase)
        val down = reduce(l, Ev.MediaDown(id, t0 + 3_000)).state
        assertEquals(PhonePhase.RECONNECTING, down.call!!.phase)
        assertEquals(PhonePhase.LIVE, reduce(down, Ev.MediaUp(id, t0 + 4_000)).state.call!!.phase)
        val muted = reduce(l, from(PortalMessage.Media(id, cameraOn = false, micOn = false))).state.call!!
        assertTrue(!muted.remoteCameraOn && !muted.remoteMicOn)
        val failed = reduce(reduce(ringingOut(), from(PortalMessage.Go(id))).state, Ev.OfferDone(id, false, t0))
        assertEquals(Fx.SendEnd(id, kitchen, EndWire.FAILED), failed.effects.first())
        assertEquals(l, reduce(l, Ev.OfferDone(id, true, t0)).state)
        assertEquals(l, reduce(l, Ev.MediaUp("ffffffffffffffff", t0)).state)
    }

    @Test fun togglesSendStateOnlyOnceConnecting() {
        val before = reduce(ringingIn().state, Ev.ToggleCamera)
        assertTrue(before.effects.isEmpty())
        val mic = reduce(live(), Ev.ToggleMic)
        assertEquals(Fx.SetMic(false), mic.effects[0])
        assertTrue(mic.effects[1] is Fx.SendMedia)
        val cam = reduce(live(), Ev.ToggleCamera)
        assertEquals(Fx.SetCamera(false), cam.effects[0])
        assertEquals(listOf(Fx.SetSpeaker(false)), reduce(live(), Ev.ToggleSpeaker).effects)
        assertEquals(listOf(Fx.SetMic(false)), reduce(dialled().state, Ev.ToggleMic).effects)
    }

    @Test fun timeoutsEndCleanly() {
        fun at(s: PhoneCallState, ms: Long) = reduce(s, Ev.Tick(ms))
        assertEquals(PhoneEndReason.UNREACHABLE, at(dialled().state, t0 + PhoneCallTimings.DIAL_ACK_MS).state.ended!!.reason)
        assertTrue(at(dialled().state, t0 + 1_000).state.call != null)
        val out = at(ringingOut(), t0 + 100 + PhoneCallTimings.RING_MS)
        assertEquals(Fx.SendEnd(id, kitchen, EndWire.NO_ANSWER), out.effects.first())
        assertEquals(PhoneEndReason.MISSED, at(ringingIn().state, t0 + PhoneCallTimings.RING_MS + PhoneCallTimings.RING_GRACE_MS).state.ended!!.reason)
        val connecting = reduce(ringingOut(), from(PortalMessage.Go(id), t0 + 1_000)).state
        assertEquals(PhoneEndReason.FAILED, at(connecting, t0 + 1_000 + PhoneCallTimings.CONNECT_MS).state.ended!!.reason)
        val down = reduce(live(), Ev.MediaDown(id, t0 + 3_000)).state
        assertEquals(PhoneEndReason.NETWORK_LOST, at(down, t0 + 3_000 + PhoneCallTimings.RECONNECT_MS).state.ended!!.reason)
        assertEquals(live(), at(live(), t0 + 10 * PhoneCallTimings.RING_MS).state)
    }

    @Test fun endedCardClosesByItself() {
        val ended = reduce(live(), Ev.HangUp(t0 + 5_000)).state
        assertTrue(reduce(ended, Ev.Tick(t0 + 5_000 + 1_000)).state.ended != null)
        assertNull(reduce(ended, Ev.Tick(t0 + 5_000 + PhoneCallTimings.ENDED_SHOW_MS)).state.ended)
        assertNull(reduce(ended, Ev.DismissEnded).state.ended)
        assertEquals(PhoneCallState(), reduce(PhoneCallState(), Ev.Tick(t0)).state)
    }

    @Test fun lateMessagesForAnEndedCallAreIgnored() {
        val ended = reduce(live(), Ev.HangUp(t0)).state
        assertEquals(ended, reduce(ended, from(PortalMessage.End(id, EndWire.HUNG_UP))).state)
        assertEquals(ended, reduce(ended, Ev.MediaUp(id, t0)).state)
        assertEquals(ended, reduce(ended, from(PortalMessage.Reply(id, PortalReply.RINGING, null))).state)
    }

    @Test fun pollCadence() {
        assertEquals(PhoneCallTimings.POLL_IDLE_MS, PhoneCallTimings.pollDelayMs(PhoneCallState()))
        assertEquals(PhoneCallTimings.POLL_IN_CALL_MS, PhoneCallTimings.pollDelayMs(live()))
    }
}
