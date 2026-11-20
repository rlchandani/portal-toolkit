/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.calls

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Calling 2b wire (untrusted), the message format (byte-compatible with the Portal), list rules, compose and standby. */
class Calling2bWireAndMessagesTest {
    private val phoneId = CallSigner.phoneId(PortalSide.TOKEN)
    private val id = "0123456789abcdef"
    private val den = "33333333-3333-3333-3333-333333333333"

    private fun msg(t: String, b: JSONObject) = JSONObject().put("t", t).put("b", b.put("i", id).put("to", phoneId))

    @Test fun pollBodiesCarryOurIdsTheBackgroundFlagAndTheWait() {
        val b = JSONObject(CallWire.pollBody("Sam", mapOf(PortalSide.PORTAL_ID to phoneId, "bad id!" to phoneId), background = true, waitMs = 60_000))
        assertEquals(phoneId, b.getJSONObject("pa").getString(PortalSide.PORTAL_ID))
        assertEquals(1, b.getJSONObject("pa").length())
        assertTrue(b.getBoolean("bg"))
        assertEquals(CallWire.MAX_WAIT_MS, b.getLong("w"))
        val plain = JSONObject(CallWire.pollBody("Sam"))
        assertFalse(plain.has("pa") || plain.has("bg") || plain.has("w"))
    }

    @Test fun groupAndMoveRingsParseAndPhonesAreNeverMembers() {
        val g = JSONArray().put(JSONObject().put("d", den).put("fn", "Den"))
        val ring = CallWire.parseMessage(msg("call_ring", JSONObject().put("k", "c").put("v", true).put("fn", "Kitchen").put("g", g).put("mv", true)), phoneId)
            as PortalMessage.Ring
        assertEquals(listOf(MemberRef(den, "Den")), ring.members)
        assertTrue(ring.move)
        val phone = JSONArray().put(JSONObject().put("d", "phfedcba9876543210").put("fn", "Priya"))
        assertNull(CallWire.parseMessage(msg("call_ring", JSONObject().put("k", "c").put("v", true).put("fn", "K").put("g", phone)), phoneId))
        val tooMany = JSONArray().apply { (1..3).forEach { put(JSONObject().put("d", "4444444$it-4444-4444-4444-444444444444").put("fn", "R$it")) } }
        assertNull(CallWire.parseMessage(msg("call_ring", JSONObject().put("k", "c").put("v", true).put("fn", "K").put("g", tooMany)), phoneId))
    }

    @Test fun joinsPullsMovedEndsAndMediaParse() {
        assertEquals(PortalMessage.Join(id, den, "Den"), CallWire.parseMessage(msg("call_join", JSONObject().put("n", den).put("fn", "Den")), phoneId))
        assertNull("a phone newcomer", CallWire.parseMessage(msg("call_join", JSONObject().put("n", "phfedcba9876543210").put("fn", "P")), phoneId))
        assertEquals(PortalMessage.Pull(id), CallWire.parseMessage(msg("call_pull", JSONObject()), phoneId))
        assertEquals(PortalMessage.Moved(id), CallWire.parseMessage(msg("call_moved", JSONObject()), phoneId))
        assertEquals(PortalMessage.End(id, EndWire.HUNG_UP, den), CallWire.parseMessage(msg("call_end", JSONObject().put("e", "h").put("mv", den)), phoneId))
        assertNull((CallWire.parseMessage(msg("call_end", JSONObject().put("e", "h").put("mv", "../x")), phoneId) as PortalMessage.End).movedTo)
        assertTrue((CallWire.parseMessage(msg("call_media", JSONObject().put("cam", true).put("mic", true).put("aa", true)), phoneId)
            as PortalMessage.Media).autoAnswered)
    }

    @Test fun phoneSentBodiesMatchThePortalContract() {
        val ring = JSONObject(CallWire.ringBody(id, PortalSide.PORTAL_ID, true, "Sam", listOf(MemberRef(den, "Den"), MemberRef(PortalSide.PORTAL_ID, "Self")), true))
        assertEquals("call_ring", ring.getString("t"))
        assertEquals(1, ring.getJSONObject("b").getJSONArray("g").length())
        assertTrue(ring.getJSONObject("b").getBoolean("mv"))
        val join = JSONObject(CallWire.joinBody(id, PortalSide.PORTAL_ID, den, "Den")).getJSONObject("b")
        assertEquals(den, join.getString("n"))
        assertEquals("call_moved", JSONObject(CallWire.movedBody(id, PortalSide.PORTAL_ID)).getString("t"))
        assertEquals("call_pull", JSONObject(CallWire.pullBody(id, PortalSide.PORTAL_ID)).getString("t"))
        assertEquals(den, JSONObject(CallWire.endBody(id, PortalSide.PORTAL_ID, EndWire.HUNG_UP, den)).getJSONObject("b").getString("mv"))
    }

    @Test fun pollsListWaitingMessagesOnlyWhenWellFormed() {
        val z = PhoneMessageCrypto.sealedLength(1_000)
        val ok = JSONObject().put("i", "00000000000000a1").put("fn", "Kitchen").put("ms", 12_000).put("z", z).put("c", 1).put("a", false).put("at", 5)
        val p = JSONObject().put("ok", true).put("portal", JSONObject().put("id", PortalSide.PORTAL_ID).put("name", "Kitchen"))
            .put("w", 25_000).put("vm", JSONArray().put(ok).put(JSONObject(ok.toString()).put("i", "../../x"))
                .put(JSONObject(ok.toString()).put("c", 7)).put(JSONObject(ok.toString()).put("ms", 999_999)))
        val r = CallWire.parsePoll(p, phoneId)!!
        assertEquals(25_000L, r.waitMs)
        assertEquals(listOf("00000000000000a1"), r.videoMessages.map { it.id })
        assertEquals(1_000L, PhoneMessageCrypto.plainLength(r.videoMessages[0].sealedBytes))
    }

    private val key = ByteArray(32) { it.toByte() }
    private val msgId = "00000000000000a1"

    @Test fun sealedChunksMatchThePortalsFormatByteForByte() {
        // The same vector is asserted in Hearthpane's CallPhone2bWireTest (CallMessageCrypto).
        val sealed = PhoneMessageCrypto.sealChunk(PhoneMessageCrypto.transitKey(key, msgId), msgId, 0, true, "hearthpane".toByteArray())
        assertEquals(VECTOR, sealed.joinToString("") { "%02x".format(it) })
    }

    @Test fun aSealedStreamOpensAndAnyTamperFails() {
        val plain = ByteArray(PhoneMessageLimits.CHUNK * 2 + 123) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        val k = PhoneMessageCrypto.transitKey(key, msgId)
        PhoneMessageCrypto.seal(k, msgId, ByteArrayInputStream(plain), plain.size.toLong(), out)
        val sealed = out.toByteArray()
        assertEquals(PhoneMessageCrypto.sealedLength(plain.size.toLong()), sealed.size.toLong())
        fun open(bytes: ByteArray, length: Long = bytes.size.toLong()): ByteArray {
            val got = ByteArrayOutputStream()
            val o = PhoneMessageCrypto.Opener(k, msgId, length) { _, _, b, n -> got.write(b, 0, n) }
            o.write(bytes, 0, bytes.size)
            o.finish()
            return got.toByteArray()
        }
        assertArrayEquals(plain, open(sealed))
        fun fails(block: () -> Unit) = try { block(); fail("accepted") } catch (_: PhoneMessageCrypto.Tampered) { }
        fails { open(sealed.copyOf().also { it[100] = (it[100] + 1).toByte() }) }
        fails { open(sealed.copyOf(PhoneMessageCrypto.SEALED_CHUNK * 2), sealed.size.toLong()) }
        val swapped = sealed.copyOf().also {
            System.arraycopy(sealed, PhoneMessageCrypto.SEALED_CHUNK, it, 0, PhoneMessageCrypto.SEALED_CHUNK)
            System.arraycopy(sealed, 0, it, PhoneMessageCrypto.SEALED_CHUNK, PhoneMessageCrypto.SEALED_CHUNK)
        }
        fails { open(swapped) }
        // Another message's key never opens it.
        fails {
            val o = PhoneMessageCrypto.Opener(PhoneMessageCrypto.transitKey(key, "00000000000000a2"), msgId, sealed.size.toLong()) { _, _, _, _ -> }
            o.write(sealed, 0, sealed.size)
        }
    }

    @Test fun theReaderSeeksAcrossChunks() {
        val plain = ByteArray(PhoneMessageLimits.CHUNK + 500) { (it % 199).toByte() }
        val f = File.createTempFile("vmsg", ".hpv")
        try {
            val k = PhoneMessageCrypto.restKey(key, msgId)
            f.outputStream().use { PhoneMessageCrypto.seal(k, msgId, ByteArrayInputStream(plain), plain.size.toLong(), it) }
            PhoneMessageCrypto.Reader(f, k, msgId).use { r ->
                assertEquals(plain.size.toLong(), r.plainBytes)
                val buf = ByteArray(10)
                val at = PhoneMessageLimits.CHUNK.toLong() + 3
                assertEquals(10, r.read(at, buf, 0, 10))
                assertArrayEquals(plain.copyOfRange(at.toInt(), at.toInt() + 10), buf)
                assertEquals(-1, r.read(plain.size.toLong(), buf, 0, 10))
            }
        } finally { f.delete() }
    }

    private fun m(i: String, at: Long) = PhoneMessage(i, "Kitchen", "s1", at, 12_000, PhoneMessageCrypto.sealedLength(1_000), false)

    @Test fun messagesKeepThirtyDaysAtMostFortyAndNeverTwice() {
        val now = 100L * 24 * 60 * 60_000L
        val old = m("00000000000000b1", now - PhoneMessageLimits.KEEP_MS - 1)
        val fresh = m("00000000000000b2", now - 1_000)
        assertEquals(listOf(fresh), PhoneMessageList.prune(listOf(old, fresh), now))
        val n = MessageNotice(fresh.id, "Kitchen", 12_000, fresh.sealedBytes, 1, false, now, null)
        val free = 10L * 1024 * 1024 * 1024
        assertEquals(PhoneMessageList.Refusal.DUPLICATE, PhoneMessageList.canAccept(listOf(fresh), n, free))
        val forty = (0 until PhoneMessageLimits.MAX_STORED).map { m("0000000000000%03x".format(it), now) }
        assertEquals(PhoneMessageList.Refusal.FULL, PhoneMessageList.canAccept(forty, n.copy(id = "00000000000000c1"), free))
        assertEquals(PhoneMessageList.Refusal.NO_SPACE, PhoneMessageList.canAccept(emptyList(), n, 1_000))
        assertNull(PhoneMessageList.canAccept(emptyList(), n, free))
        assertEquals("Deletes itself in 28 days", PhoneMessageList.expiresLine(m("00000000000000d1", now - 24 * 60 * 60_000L - 1), now))
        assertEquals(1, PhoneMessageList.unseen(listOf(fresh, old.copy(seen = true))))
    }

    @Test fun theMessageIndexIsValidatedOnLoad() {
        val list = listOf(m("00000000000000a1", 5), m("00000000000000a2", 6).copy(seen = true, fromSession = null))
        assertEquals(list, PhoneMessageList.decode(PhoneMessageList.encode(list)))
        val bad = JSONArray(PhoneMessageList.encode(list)).put(JSONObject().put("i", "../x").put("fn", "K").put("at", 1).put("ms", 1).put("z", 100))
        assertEquals(list, PhoneMessageList.decode(bad.toString()))
        assertTrue(PhoneMessageList.decode("nope").isEmpty())
    }

    @Test fun composeFollowsThePortalsRules() {
        var s = ComposeState()
        fun go(e: ComposeEvent): List<ComposeEffect> { val st = PhoneCompose.reduce(s, e); s = st.state; return st.effects }
        assertEquals(listOf(ComposeEffect.StartRecording(true)), go(ComposeEvent.Record(1_000)))
        assertEquals(listOf(ComposeEffect.StopRecording), go(ComposeEvent.Tick(1_000 + PhoneMessageLimits.MAX_DURATION_MS)))
        assertEquals(listOf(ComposeEffect.CloseCamera), go(ComposeEvent.Recorded(true, 120_000)))
        assertEquals(ComposePhase.REVIEW, s.phase)
        assertEquals(listOf(ComposeEffect.Upload), go(ComposeEvent.Send))
        assertEquals(listOf(ComposeEffect.DeleteTake), go(ComposeEvent.SendOk))
        assertFalse(s.hasTake)
        // A mis-tap under a second goes back to Ready and deletes the take.
        s = ComposeState()
        go(ComposeEvent.Record(0))
        go(ComposeEvent.Stop(500))
        assertEquals(listOf(ComposeEffect.DeleteTake, ComposeEffect.OpenCamera(true)), go(ComposeEvent.Recorded(true, 500)))
        assertEquals(ComposePhase.READY, s.phase)
        // Leaving while recording stops, closes the camera and deletes the take.
        go(ComposeEvent.Record(0))
        assertEquals(listOf(ComposeEffect.StopRecording, ComposeEffect.CloseCamera, ComposeEffect.DeleteTake, ComposeEffect.Finish),
            go(ComposeEvent.Discard))
        assertEquals("Stop · 0:42 of 2:00", PhoneMessageCopy.stopLabel(42_000))
        assertEquals("They’ve turned video messages off on that Portal", PhoneMessageCopy.failure("messages_off"))
    }

    @Test fun backgroundRingingIsOptInWifiOnlyAndFallsBackWhenParkingIsFull() {
        assertEquals(CallStandbyPolicy.Mode.OFF, CallStandbyPolicy.mode(false, true, 2, true))
        assertEquals(CallStandbyPolicy.Mode.OFF, CallStandbyPolicy.mode(true, true, 0, true))
        assertEquals(CallStandbyPolicy.Mode.OFF, CallStandbyPolicy.mode(true, true, 2, false))
        assertEquals(CallStandbyPolicy.Mode.WAITING_FOR_WIFI, CallStandbyPolicy.mode(true, false, 2, true))
        assertEquals(CallStandbyPolicy.Mode.LISTENING, CallStandbyPolicy.mode(true, true, 2, true))
        val listening = CallStandbyPolicy.Mode.LISTENING
        assertEquals(CallStandbyPolicy.Plan(0, PhoneCallTimings.POLL_IN_CALL_MS), CallStandbyPolicy.plan(true, false, listening, 0, 0))
        assertEquals(CallStandbyPolicy.Plan(0, PhoneCallTimings.POLL_IDLE_MS), CallStandbyPolicy.plan(false, true, listening, 0, 0))
        assertEquals(CallStandbyPolicy.Plan(CallWire.MAX_WAIT_MS, CallStandbyPolicy.REPOLL_MS), CallStandbyPolicy.plan(false, false, listening, 0, 0))
        assertEquals(CallStandbyPolicy.Plan(0, CallStandbyPolicy.FALLBACK_POLL_MS), CallStandbyPolicy.plan(false, false, listening, 25_000, 0))
        assertTrue("the fallback still beats the Portal's 4 s ring window", CallStandbyPolicy.FALLBACK_POLL_MS < 4_000)
        assertNull(CallStandbyPolicy.plan(false, false, CallStandbyPolicy.Mode.WAITING_FOR_WIFI, 0, 0))
        assertEquals("Portal calls paused", CallStandbyPolicy.notice(CallStandbyPolicy.Mode.WAITING_FOR_WIFI, 2).first)
        assertEquals("On home Wi-Fi · 2 Portals can ring this phone", CallStandbyPolicy.notice(listening, 2).second)
    }

    @Test fun clientPollsWithTheWaitAndItsOwnTimeout() {
        var seenTimeout = 0L
        var seenBody = ""
        val http = CallHttp { _, _, header, body, timeout ->
            seenTimeout = timeout; seenBody = body
            val nonce = JSONObject(header.substring("IMMORTAL_HH/1|".length, header.lastIndexOf('|'))).getString("n")
            CallHttpResponse(200, PortalSide.envelope(nonce, JSONObject().put("ok", true).put("w", 25_000)
                .put("portal", JSONObject().put("id", PortalSide.PORTAL_ID).put("name", "Kitchen")).toString()))
        }
        val client = CallClient(http, CallSigner(PortalSide.KEY))
        val r = kotlinx.coroutines.runBlocking { client.poll(CallEndpoint("s1", "http://192.168.1.20:8723", PortalSide.TOKEN), "Sam", waitMs = 25_000) }
        assertEquals(25_000L, r.waitMs)
        assertEquals(CallClient.POLL_TIMEOUT_MS + 25_000, seenTimeout)
        assertEquals(25_000L, JSONObject(seenBody).getLong("w"))
        assertNotNull(r.portal)
    }

    private companion object {
        const val VECTOR = "6a4d7aae7dd801d90426113708b4d946dafe7a82fc9a16f54570"
    }
}
