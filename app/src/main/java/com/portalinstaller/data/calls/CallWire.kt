/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.calls

import org.json.JSONArray
import org.json.JSONObject

/** What a Portal said about a ring (its `CallReply` wire values). */
enum class PortalReply(val wire: String) {
    RINGING("r"), COUNTDOWN("cd"), QUIET("q"), BUSY("b"), CALLS_OFF("x"), DROP_INS_OFF("dx"), DECLINED("d");
    companion object { fun fromWire(raw: String?) = entries.firstOrNull { it.wire == raw } }
}

/** Why a call ended, as the Portal's `CallEndReason` wire values. */
enum class EndWire(val wire: String) {
    HUNG_UP("h"), CANCELLED("c"), DECLINED("d"), NO_ANSWER("n"), MISSED("m"), OFFLINE("o"), BUSY("b"), QUIET("q"),
    CALLS_OFF("x"), DROP_INS_OFF("dx"), NETWORK_LOST("l"), LEFT("v"), CAP_REACHED("cap"), FAILED("f"),
    NO_PERMISSION("p"), UNAVAILABLE("u");
    companion object { fun fromWire(raw: String?) = entries.firstOrNull { it.wire == raw } }
}

/** A Portal as its last poll described it. */
data class PortalInfo(val id: String, val name: String, val inCall: Boolean, val quiet: Boolean, val acceptsPhones: Boolean)

/** Messages a Portal leaves for this phone (collected by `/remote/call/poll`). */
sealed interface PortalMessage {
    val callId: String
    data class Ring(override val callId: String, val video: Boolean, val name: String) : PortalMessage
    data class Reply(override val callId: String, val reply: PortalReply, val quietEndMin: Int?) : PortalMessage
    /** The Portal answered this phone's call: open media and send the offer. */
    data class Go(override val callId: String) : PortalMessage
    data class End(override val callId: String, val reason: EndWire) : PortalMessage
    data class Media(override val callId: String, val cameraOn: Boolean, val micOn: Boolean) : PortalMessage
}

data class PollResult(val portal: PortalInfo, val messages: List<PortalMessage>)

/** The Portal's immediate answer to a sent message (a ring's decision, when it was a ring). */
data class SendResult(val reply: PortalReply?, val quietEndMin: Int?)

/**
 * Bodies and payloads of the phone call routes. Every field of a Portal payload is untrusted:
 * anything malformed decodes to null (or the message is dropped). Pure.
 */
object CallWire {
    const val POLL = "/remote/call/poll"
    const val SEND = "/remote/call/send"
    const val OFFER = "/remote/call/offer"
    const val RING = "call_ring"
    const val REPLY = "call_reply"
    const val GO = "call_go"
    const val END = "call_end"
    const val MEDIA = "call_media"
    const val MAX_NAME = 40
    private const val MINUTES_PER_DAY = 24 * 60
    private const val VERSION = 1
    private val CALL_ID = Regex("^[a-f0-9]{16}$")
    private val DEVICE_ID = Regex("^[A-Za-z0-9_-]{1,64}$")

    fun isCallId(text: String?) = text != null && CALL_ID.matches(text)
    fun isDeviceId(text: String?) = text != null && DEVICE_ID.matches(text)

    /** Printable, single line, at most [MAX_NAME] code points; null when nothing is left. */
    fun cleanName(raw: String?): String? {
        val text = raw?.filter { !it.isISOControl() }?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (text.codePointCount(0, text.length) <= MAX_NAME) return text
        return text.substring(0, text.offsetByCodePoints(0, MAX_NAME)).trimEnd()
    }

    fun pollBody(phoneName: String): String =
        JSONObject().put("fn", cleanName(phoneName) ?: "Phone").put("v", VERSION).toString()

    fun ringBody(callId: String, to: String, video: Boolean, phoneName: String): String = send(RING,
        JSONObject().put("i", callId).put("to", to).put("k", "c").put("v", video).put("fn", cleanName(phoneName) ?: "Phone"))
    fun replyBody(callId: String, to: String, reply: PortalReply): String =
        send(REPLY, JSONObject().put("i", callId).put("to", to).put("r", reply.wire))
    fun endBody(callId: String, to: String, reason: EndWire): String =
        send(END, JSONObject().put("i", callId).put("to", to).put("e", reason.wire))
    fun mediaBody(callId: String, to: String, cameraOn: Boolean, micOn: Boolean): String =
        send(MEDIA, JSONObject().put("i", callId).put("to", to).put("cam", cameraOn).put("mic", micOn))
    fun offerBody(callId: String, to: String, sdp: String, fingerprint: String): String =
        JSONObject().put("i", callId).put("to", to).put("sdp", sdp).put("fp", fingerprint).toString()

    private fun send(type: String, b: JSONObject) = JSONObject().put("t", type).put("b", b).toString()

    /** A poll payload; messages not addressed to [phoneId] or malformed are dropped. */
    fun parsePoll(p: JSONObject, phoneId: String): PollResult? {
        if (p.optBoolean("ok") != true) return null
        val portal = parsePortal(p.optJSONObject("portal") ?: return null) ?: return null
        val raw = p.optJSONArray("msgs") ?: JSONArray()
        val messages = (0 until raw.length()).mapNotNull { i -> raw.optJSONObject(i)?.let { parseMessage(it, phoneId) } }
        return PollResult(portal, messages)
    }

    fun parsePortal(o: JSONObject): PortalInfo? {
        val id = o.optString("id").takeIf(::isDeviceId) ?: return null
        val name = cleanName(o.optString("name")) ?: return null
        return PortalInfo(id, name, o.optBoolean("inCall"), o.optBoolean("quiet"), o.optBoolean("acceptsPhones", true))
    }

    fun parseMessage(m: JSONObject, phoneId: String): PortalMessage? {
        val b = m.optJSONObject("b") ?: return null
        val id = b.optString("i").takeIf(::isCallId) ?: return null
        if (b.optString("to") != phoneId) return null
        return when (m.optString("t")) {
            RING -> {
                if (b.optString("k") != "c") return null
                val video = b.opt("v") as? Boolean ?: return null
                PortalMessage.Ring(id, video, cleanName(b.optString("fn")) ?: return null)
            }
            REPLY -> PortalMessage.Reply(id, PortalReply.fromWire(b.optString("r")) ?: return null, quietEnd(b))
            GO -> PortalMessage.Go(id)
            END -> PortalMessage.End(id, EndWire.fromWire(b.optString("e")) ?: return null)
            MEDIA -> PortalMessage.Media(id, b.opt("cam") as? Boolean ?: return null, b.opt("mic") as? Boolean ?: return null)
            else -> null
        }
    }

    fun parseSend(p: JSONObject): SendResult? {
        if (p.optBoolean("ok") != true) return null
        val raw = p.opt("r") as? String
        val reply = raw?.let { PortalReply.fromWire(it) ?: return null }
        return SendResult(reply, quietEnd(p))
    }

    /** The answer SDP if it names [callId] and its `fp` is exactly the SDP's fingerprint. */
    fun parseAnswer(p: JSONObject, callId: String): String? {
        if (p.optString("i") != callId) return null
        val sdp = p.optString("sdp").takeIf { it.length in 1..CallSdp.MAX_SDP } ?: return null
        val fp = CallSdp.fingerprintOf(sdp) ?: return null
        return sdp.takeIf { p.optString("fp") == fp }
    }

    private fun quietEnd(o: JSONObject): Int? = (o.opt("qe") as? Number)?.toInt()?.takeIf { it in 0 until MINUTES_PER_DAY }
}
