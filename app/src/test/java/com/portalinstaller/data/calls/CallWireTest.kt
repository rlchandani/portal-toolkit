/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.calls

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallWireTest {
    private val me = CallSigner.phoneId(PortalSide.TOKEN)
    private val id = "0123456789abcdef"

    private fun msg(t: String, b: JSONObject) = JSONObject().put("t", t).put("b", b.put("i", id).put("to", me))
    private fun poll(vararg m: JSONObject) = JSONObject().put("ok", true)
        .put("portal", JSONObject().put("id", PortalSide.PORTAL_ID).put("name", "Kitchen").put("inCall", false).put("quiet", true).put("acceptsPhones", true))
        .put("msgs", JSONArray(m.toList()))

    @Test fun bodiesMatchTheProtocol() {
        val ring = JSONObject(CallWire.ringBody(id, PortalSide.PORTAL_ID, true, "Pixel"))
        assertEquals("call_ring", ring.getString("t"))
        val b = ring.getJSONObject("b")
        assertEquals(listOf(id, PortalSide.PORTAL_ID, "c", true, "Pixel"), listOf(b["i"], b["to"], b["k"], b["v"], b["fn"]))
        assertEquals("d", JSONObject(CallWire.replyBody(id, "p", PortalReply.DECLINED)).getJSONObject("b")["r"])
        assertEquals("h", JSONObject(CallWire.endBody(id, "p", EndWire.HUNG_UP)).getJSONObject("b")["e"])
        val media = JSONObject(CallWire.mediaBody(id, "p", false, true)).getJSONObject("b")
        assertEquals(false, media["cam"]); assertEquals(true, media["mic"])
        val offer = JSONObject(CallWire.offerBody(id, "p", "sdp", PortalSide.FP))
        assertEquals(PortalSide.FP, offer["fp"])
        assertEquals(1, JSONObject(CallWire.pollBody("Pixel")).getInt("v"))
    }

    @Test fun parsesEveryPortalMessage() {
        val r = CallWire.parsePoll(poll(
            msg("call_ring", JSONObject().put("k", "c").put("v", true).put("fn", "Kitchen")),
            msg("call_reply", JSONObject().put("r", "q").put("qe", 420)),
            msg("call_go", JSONObject()),
            msg("call_end", JSONObject().put("e", "m")),
            msg("call_media", JSONObject().put("cam", false).put("mic", true))), me)!!
        assertEquals(PortalInfo(PortalSide.PORTAL_ID, "Kitchen", false, true, true), r.portal)
        assertEquals(listOf(PortalMessage.Ring(id, true, "Kitchen"), PortalMessage.Reply(id, PortalReply.QUIET, 420),
            PortalMessage.Go(id), PortalMessage.End(id, EndWire.MISSED), PortalMessage.Media(id, false, true)), r.messages)
    }

    @Test fun dropsMalformedOrMisaddressedMessages() {
        val bad = listOf(
            msg("call_ring", JSONObject().put("k", "d").put("v", true).put("fn", "K")), // drop-in
            msg("call_ring", JSONObject().put("k", "c").put("v", "yes").put("fn", "K")),
            msg("call_ring", JSONObject().put("k", "c").put("v", true).put("fn", " ")),
            msg("call_reply", JSONObject().put("r", "zz")),
            msg("call_end", JSONObject().put("e", "??")),
            msg("call_media", JSONObject().put("cam", 1).put("mic", true)),
            msg("call_whatever", JSONObject()),
            JSONObject().put("t", "call_go").put("b", JSONObject().put("i", id).put("to", "phsomeoneelse0000")),
            JSONObject().put("t", "call_go").put("b", JSONObject().put("i", "XYZ").put("to", me)),
            JSONObject().put("t", "call_go"))
        assertTrue(CallWire.parsePoll(poll(*bad.toTypedArray()), me)!!.messages.isEmpty())
        val outOfRange = CallWire.parseMessage(msg("call_reply", JSONObject().put("r", "r").put("qe", 99_999)), me) as PortalMessage.Reply
        assertNull(outOfRange.quietEndMin)
    }

    @Test fun rejectsBadPollPayloads() {
        assertNull(CallWire.parsePoll(JSONObject().put("ok", false), me))
        assertNull(CallWire.parsePoll(JSONObject().put("ok", true), me))
        assertNull(CallWire.parsePoll(JSONObject().put("ok", true).put("portal", JSONObject().put("id", "bad id!").put("name", "K")), me))
        assertNull(CallWire.parsePortal(JSONObject().put("id", "abc").put("name", "")))
    }

    @Test fun sendResult() {
        assertEquals(SendResult(PortalReply.RINGING, null), CallWire.parseSend(JSONObject().put("ok", true).put("r", "r")))
        assertEquals(SendResult(null, null), CallWire.parseSend(JSONObject().put("ok", true)))
        assertEquals(SendResult(PortalReply.QUIET, 450), CallWire.parseSend(JSONObject().put("ok", true).put("r", "q").put("qe", 450)))
        assertNull(CallWire.parseSend(JSONObject().put("ok", true).put("r", "nope")))
        assertNull(CallWire.parseSend(JSONObject()))
    }

    @Test fun answerMustBeBoundToCallAndFingerprint() {
        val sdp = PortalSide.sdp()
        assertEquals(sdp, CallWire.parseAnswer(JSONObject().put("i", id).put("sdp", sdp).put("fp", PortalSide.FP), id))
        assertNull(CallWire.parseAnswer(JSONObject().put("i", "ffffffffffffffff").put("sdp", sdp).put("fp", PortalSide.FP), id))
        assertNull(CallWire.parseAnswer(JSONObject().put("i", id).put("sdp", sdp).put("fp", PortalSide.FP.replace("AB", "BA")), id))
        assertNull(CallWire.parseAnswer(JSONObject().put("i", id).put("sdp", "").put("fp", PortalSide.FP), id))
    }

    @Test fun namesAndIds() {
        assertEquals("Kitchen", CallWire.cleanName("  Kit\u0007chen "))
        assertNull(CallWire.cleanName("\n\t"))
        assertEquals(40, CallWire.cleanName("x".repeat(80))!!.length)
        assertTrue(CallWire.isCallId(id)); assertFalse(CallWire.isCallId("0123"))
        assertTrue(CallWire.isDeviceId(PortalSide.PORTAL_ID)); assertFalse(CallWire.isDeviceId(null))
        assertEquals(PortalReply.COUNTDOWN, PortalReply.fromWire("cd")); assertEquals(EndWire.CAP_REACHED, EndWire.fromWire("cap"))
    }
}
