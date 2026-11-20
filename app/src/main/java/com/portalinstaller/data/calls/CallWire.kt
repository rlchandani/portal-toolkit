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
    /** Calling 2b: [members] the other Portals already in the call; [move] "continue this call here". */
    data class Ring(override val callId: String, val video: Boolean, val name: String,
                    val members: List<MemberRef> = emptyList(), val move: Boolean = false) : PortalMessage
    data class Reply(override val callId: String, val reply: PortalReply, val quietEndMin: Int?) : PortalMessage
    /** The Portal answered this phone's call: open media and send the offer. */
    data class Go(override val callId: String) : PortalMessage
    /** [movedTo]: that member left because it moved the call to this id (ours, or a Portal's). */
    data class End(override val callId: String, val reason: EndWire, val movedTo: String? = null) : PortalMessage
    /** [autoAnswered]: that Portal answered by itself (Grandma mode): this call stays between the two. */
    data class Media(override val callId: String, val cameraOn: Boolean, val micOn: Boolean,
                     val autoAnswered: Boolean = false) : PortalMessage
    /** A Portal joined the call (vouched by the sender): offer to it through our own session there. */
    data class Join(override val callId: String, val portalId: String, val name: String) : PortalMessage
    /** The Portal we moved from asks for the call back ("Move it back here" there). */
    data class Pull(override val callId: String) : PortalMessage
    /** The device the call moved to is connected to everyone: we may leave now. */
    data class Moved(override val callId: String) : PortalMessage
}

/** A video message a Portal left for this phone, announced in a poll (sealed under the transit key). */
data class MessageNotice(val id: String, val fromName: String, val durationMs: Long, val sealedBytes: Long, val chunks: Int,
                         val audioOnly: Boolean, val atMs: Long, val callId: String?)

/** [waitMs]: how long the Portal let this poll park (0: it answered at once). */
data class PollResult(val portal: PortalInfo, val messages: List<PortalMessage>, val waitMs: Long = 0L,
                      val videoMessages: List<MessageNotice> = emptyList())

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
    const val JOIN = "call_join"
    const val PULL = "call_pull"
    const val MOVED = "call_moved"
    /** Longest a background poll asks to park at a Portal (the Portal caps it too). */
    const val MAX_WAIT_MS = 25_000L
    private const val MAX_ALIASES = 16
    private const val MSG_ID_HEX = 16
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

    /**
     * [aliases]: our id on every paired Portal (portal id → phone id), so Portals can name us to
     * each other in a group call; [background]: "Ring when Portal Toolkit is closed" is on;
     * [waitMs]: park this poll up to that long (background long-poll).
     */
    fun pollBody(phoneName: String, aliases: Map<String, String> = emptyMap(), background: Boolean = false, waitMs: Long = 0L): String =
        JSONObject().put("fn", cleanName(phoneName) ?: "Phone").put("v", VERSION).apply {
            val pa = aliases.entries.filter { isDeviceId(it.key) && isDeviceId(it.value) }.take(MAX_ALIASES)
            if (pa.isNotEmpty()) put("pa", JSONObject().apply { pa.forEach { put(it.key, it.value) } })
            if (background) put("bg", true)
            if (waitMs > 0) put("w", waitMs.coerceAtMost(MAX_WAIT_MS))
        }.toString()

    /** A ring; Calling 2b "To Portal" adds the Portals in the call ([members], never phones) and `mv`. */
    fun ringBody(callId: String, to: String, video: Boolean, phoneName: String, members: List<MemberRef> = emptyList(),
                 move: Boolean = false): String = send(RING,
        JSONObject().put("i", callId).put("to", to).put("k", "c").put("v", video).put("fn", cleanName(phoneName) ?: "Phone").apply {
            val g = members.filter { it.portalId != to && isDeviceId(it.portalId) }
            if (g.isNotEmpty()) put("g", JSONArray(g.map { JSONObject().put("d", it.portalId).put("fn", cleanName(it.name) ?: "Portal") }))
            if (move) put("mv", true)
        })
    fun joinBody(callId: String, to: String, newcomerId: String, newcomerName: String): String =
        send(JOIN, JSONObject().put("i", callId).put("to", to).put("n", newcomerId).put("fn", cleanName(newcomerName) ?: "Portal"))
    fun movedBody(callId: String, to: String): String = send(MOVED, JSONObject().put("i", callId).put("to", to))
    fun pullBody(callId: String, to: String): String = send(PULL, JSONObject().put("i", callId).put("to", to))
    fun replyBody(callId: String, to: String, reply: PortalReply): String =
        send(REPLY, JSONObject().put("i", callId).put("to", to).put("r", reply.wire))
    fun endBody(callId: String, to: String, reason: EndWire, movedTo: String? = null): String =
        send(END, JSONObject().put("i", callId).put("to", to).put("e", reason.wire).apply { movedTo?.let { put("mv", it) } })
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
        val vm = p.optJSONArray("vm") ?: JSONArray()
        val notices = (0 until minOf(vm.length(), MAX_NOTICES)).mapNotNull { i -> vm.optJSONObject(i)?.let(::parseNotice) }
        val wait = (p.opt("w") as? Number)?.toLong()?.coerceIn(0L, MAX_WAIT_MS) ?: 0L
        return PollResult(portal, messages, wait, notices)
    }

    /** A waiting video message: everything validated before a byte is fetched. */
    fun parseNotice(o: JSONObject): MessageNotice? {
        val id = o.optString("i").takeIf { it.length == MSG_ID_HEX && isCallId(it) } ?: return null
        val name = cleanName(o.optString("fn")) ?: return null
        val ms = (o.opt("ms") as? Number)?.toLong()?.takeIf { it in 1..PhoneMessageLimits.MAX_DURATION_MS + DURATION_SLACK_MS } ?: return null
        val z = (o.opt("z") as? Number)?.toLong() ?: return null
        val c = (o.opt("c") as? Number)?.toInt() ?: return null
        val plain = PhoneMessageCrypto.plainLength(z)
        if (plain <= 0 || plain > PhoneMessageLimits.MAX_BYTES || PhoneMessageCrypto.chunkCount(plain) != c) return null
        val at = (o.opt("at") as? Number)?.toLong() ?: return null
        return MessageNotice(id, name, ms, z, c, o.optBoolean("a", false), at, o.optString("ci").takeIf(::isCallId))
    }

    private const val MAX_NOTICES = 8
    private const val DURATION_SLACK_MS = 2_000L

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
                val members = parseMembers(b.optJSONArray("g")) ?: return null
                PortalMessage.Ring(id, video, cleanName(b.optString("fn")) ?: return null, members, b.optBoolean("mv", false))
            }
            REPLY -> PortalMessage.Reply(id, PortalReply.fromWire(b.optString("r")) ?: return null, quietEnd(b))
            GO -> PortalMessage.Go(id)
            END -> PortalMessage.End(id, EndWire.fromWire(b.optString("e")) ?: return null, b.optString("mv").takeIf(::isDeviceId))
            MEDIA -> PortalMessage.Media(id, b.opt("cam") as? Boolean ?: return null, b.opt("mic") as? Boolean ?: return null,
                b.optBoolean("aa", false))
            JOIN -> {
                // The newcomer is always a Portal: a phone never offers to a phone.
                val n = b.optString("n").takeIf(::isDeviceId)?.takeUnless(::isPhoneId) ?: return null
                PortalMessage.Join(id, n, cleanName(b.optString("fn")) ?: return null)
            }
            PULL -> PortalMessage.Pull(id)
            MOVED -> PortalMessage.Moved(id)
            else -> null
        }
    }

    /** A ring's Portal members: null when malformed, a phone, duplicated or over the cap. */
    private fun parseMembers(arr: JSONArray?): List<MemberRef>? {
        if (arr == null) return emptyList()
        if (arr.length() > PhoneGroup.MAX_LEGS) return null
        val out = (0 until arr.length()).map { i ->
            val o = arr.optJSONObject(i) ?: return null
            val d = o.optString("d").takeIf(::isDeviceId)?.takeUnless(::isPhoneId) ?: return null
            MemberRef(d, cleanName(o.optString("fn")) ?: return null)
        }
        return out.takeIf { l -> l.map { it.portalId }.toSet().size == l.size }
    }

    private val PHONE_ID = Regex("^ph[0-9a-f]{16}$")
    fun isPhoneId(text: String?) = text != null && PHONE_ID.matches(text)

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
