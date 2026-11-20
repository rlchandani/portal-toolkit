/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.calls

import org.json.JSONArray
import org.json.JSONObject

/** Named limits, the same as the Portal's (Hearthpane `CallMessageLimits`). */
object PhoneMessageLimits {
    /** "Up to 2 minutes." */
    const val MAX_DURATION_MS = 120_000L
    /** A message shorter than this is a mis-tap: back to Ready instead of Review. */
    const val MIN_DURATION_MS = 1_000L
    const val MAX_BYTES = 48L * 1024 * 1024
    const val MAX_STORED = 40
    /** Kept in Portal Toolkit's private storage for 30 days, then deleted. */
    const val KEEP_MS = 30L * 24 * 60 * 60_000L
    /** Never fill the phone: keep at least this much free. */
    const val MIN_FREE_BYTES = 200L * 1024 * 1024
    const val CHUNK = 64 * 1024
}

/** A video (or audio) message from a Portal, held on this phone sealed at rest. */
data class PhoneMessage(
    val id: String,
    /** The Portal's room name ("Kitchen") and its paired session here (null if no longer paired). */
    val fromName: String,
    val fromSession: String?,
    val atMs: Long,
    val durationMs: Long,
    val sealedBytes: Long,
    val audioOnly: Boolean,
    val seen: Boolean = false,
)

/** Pure rules for the phone's message list (JVM-tested), plus its strict codec. */
object PhoneMessageList {
    private val ID = Regex("^[a-f0-9]{16}$")

    /** Ids name files, so nothing but 16 lowercase hex is ever accepted. */
    fun isId(text: String?): Boolean = text != null && ID.matches(text)

    enum class Refusal { DUPLICATE, FULL, NO_SPACE, TOO_LARGE }

    fun canAccept(list: List<PhoneMessage>, n: MessageNotice, freeBytes: Long): Refusal? = when {
        list.any { it.id == n.id } -> Refusal.DUPLICATE
        PhoneMessageCrypto.plainLength(n.sealedBytes).let { it <= 0 || it > PhoneMessageLimits.MAX_BYTES } -> Refusal.TOO_LARGE
        list.size >= PhoneMessageLimits.MAX_STORED -> Refusal.FULL
        freeBytes - n.sealedBytes < PhoneMessageLimits.MIN_FREE_BYTES -> Refusal.NO_SPACE
        else -> null
    }

    /** Newest first; anything older than 30 days drops off (the store deletes its file). */
    fun prune(list: List<PhoneMessage>, nowMs: Long): List<PhoneMessage> =
        list.filter { nowMs - it.atMs <= PhoneMessageLimits.KEEP_MS }.sortedByDescending { it.atMs }.take(PhoneMessageLimits.MAX_STORED)

    fun add(list: List<PhoneMessage>, m: PhoneMessage, nowMs: Long): List<PhoneMessage> =
        prune(listOf(m) + list.filterNot { it.id == m.id }, nowMs)

    fun markSeen(list: List<PhoneMessage>, id: String) = list.map { if (it.id == id) it.copy(seen = true) else it }

    fun unseen(list: List<PhoneMessage>) = list.count { !it.seen }

    /** "Expires in 29 days" / "Expires today". */
    fun expiresLine(m: PhoneMessage, nowMs: Long): String {
        val days = ((m.atMs + PhoneMessageLimits.KEEP_MS - nowMs) / DAY_MS).coerceAtLeast(0)
        return when (days) { 0L -> "Deletes itself today"; 1L -> "Deletes itself tomorrow"; else -> "Deletes itself in $days days" }
    }

    fun encode(list: List<PhoneMessage>): String = JSONArray(list.map { m ->
        JSONObject().put("i", m.id).put("fn", m.fromName).put("at", m.atMs).put("ms", m.durationMs).put("z", m.sealedBytes)
            .put("a", m.audioOnly).put("seen", m.seen).apply { m.fromSession?.let { put("s", it) } }
    }).toString()

    fun decode(raw: String?): List<PhoneMessage> {
        val arr = runCatching { JSONArray(raw ?: return emptyList()) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::entry) }
    }

    private fun entry(o: JSONObject): PhoneMessage? {
        val id = o.optString("i").takeIf(::isId) ?: return null
        val name = CallWire.cleanName(o.optString("fn")) ?: return null
        val at = (o.opt("at") as? Number)?.toLong() ?: return null
        val ms = (o.opt("ms") as? Number)?.toLong()?.takeIf { it > 0 } ?: return null
        val z = (o.opt("z") as? Number)?.toLong()?.takeIf { PhoneMessageCrypto.plainLength(it) > 0 } ?: return null
        val session = o.optString("s").takeIf { it.isNotBlank() && it.length <= MAX_SESSION }
        return PhoneMessage(id, name, session, at, ms, z, o.optBoolean("a"), o.optBoolean("seen"))
    }

    private const val DAY_MS = 24 * 60 * 60_000L
    private const val MAX_SESSION = 128
}

/** Recording a message on the phone: Ready → Recording → Review → Sending → Sent (or Failed). */
enum class ComposePhase { READY, RECORDING, REVIEW, SENDING, SENT, FAILED }

data class ComposeState(
    val phase: ComposePhase = ComposePhase.READY,
    val cameraOn: Boolean = true,
    val startedMs: Long? = null,
    val durationMs: Long = 0L,
    /** A take exists in private temp storage; it is deleted unless it was sent. */
    val hasTake: Boolean = false,
    val failure: String? = null,
) {
    fun elapsed(nowMs: Long): Long = startedMs?.let { (nowMs - it).coerceIn(0, PhoneMessageLimits.MAX_DURATION_MS) } ?: durationMs
}

sealed interface ComposeEvent {
    data object ToggleCamera : ComposeEvent
    data class Record(val now: Long) : ComposeEvent
    data class Stop(val now: Long) : ComposeEvent
    data class Tick(val now: Long) : ComposeEvent
    data class Recorded(val ok: Boolean, val durationMs: Long) : ComposeEvent
    data object RecordAgain : ComposeEvent
    data object Send : ComposeEvent
    data object SendOk : ComposeEvent
    data class SendFailed(val reason: String) : ComposeEvent
    /** Back, Close, or the screen went away: nothing is kept. */
    data object Discard : ComposeEvent
}

sealed interface ComposeEffect {
    data class OpenCamera(val on: Boolean) : ComposeEffect
    data class StartRecording(val cameraOn: Boolean) : ComposeEffect
    data object StopRecording : ComposeEffect
    data object CloseCamera : ComposeEffect
    data object DeleteTake : ComposeEffect
    data object Upload : ComposeEffect
    data object Finish : ComposeEffect
}

data class ComposeStep(val state: ComposeState, val effects: List<ComposeEffect> = emptyList())

/**
 * The same rules as the Portal's "Leave a message": the camera is open only on Ready and
 * Recording (with the visible self view), recording stops by itself at 2:00, a mis-tap under 1 s
 * goes back to Ready, and the take is deleted on Record again, Discard and after a confirmed send.
 */
object PhoneCompose {
    fun reduce(s: ComposeState, e: ComposeEvent): ComposeStep = when (e) {
        ComposeEvent.ToggleCamera -> if (s.phase == ComposePhase.READY)
            ComposeStep(s.copy(cameraOn = !s.cameraOn), listOf(ComposeEffect.OpenCamera(!s.cameraOn))) else ComposeStep(s)
        is ComposeEvent.Record -> if (s.phase == ComposePhase.READY)
            ComposeStep(s.copy(phase = ComposePhase.RECORDING, startedMs = e.now, durationMs = 0, hasTake = true, failure = null),
                listOf(ComposeEffect.StartRecording(s.cameraOn))) else ComposeStep(s)
        is ComposeEvent.Stop -> stop(s, e.now)
        is ComposeEvent.Tick -> if (s.phase == ComposePhase.RECORDING && s.startedMs != null &&
            e.now - s.startedMs >= PhoneMessageLimits.MAX_DURATION_MS) stop(s, e.now) else ComposeStep(s)
        is ComposeEvent.Recorded -> recorded(s, e)
        ComposeEvent.RecordAgain -> if (s.phase == ComposePhase.REVIEW || s.phase == ComposePhase.FAILED)
            ComposeStep(ComposeState(cameraOn = s.cameraOn), listOf(ComposeEffect.DeleteTake, ComposeEffect.OpenCamera(s.cameraOn)))
        else ComposeStep(s)
        ComposeEvent.Send -> if ((s.phase == ComposePhase.REVIEW || s.phase == ComposePhase.FAILED) && s.hasTake)
            ComposeStep(s.copy(phase = ComposePhase.SENDING, failure = null), listOf(ComposeEffect.Upload)) else ComposeStep(s)
        ComposeEvent.SendOk -> if (s.phase == ComposePhase.SENDING)
            ComposeStep(s.copy(phase = ComposePhase.SENT, hasTake = false), listOf(ComposeEffect.DeleteTake)) else ComposeStep(s)
        is ComposeEvent.SendFailed -> if (s.phase == ComposePhase.SENDING)
            ComposeStep(s.copy(phase = ComposePhase.FAILED, failure = e.reason)) else ComposeStep(s)
        ComposeEvent.Discard -> ComposeStep(s.copy(phase = ComposePhase.READY, startedMs = null, hasTake = false), buildList {
            if (s.phase == ComposePhase.RECORDING) add(ComposeEffect.StopRecording)
            add(ComposeEffect.CloseCamera)
            if (s.hasTake) add(ComposeEffect.DeleteTake)
            add(ComposeEffect.Finish)
        })
    }

    private fun stop(s: ComposeState, now: Long): ComposeStep {
        if (s.phase != ComposePhase.RECORDING || s.startedMs == null) return ComposeStep(s)
        val d = (now - s.startedMs).coerceIn(0, PhoneMessageLimits.MAX_DURATION_MS)
        return ComposeStep(s.copy(startedMs = null, durationMs = d), listOf(ComposeEffect.StopRecording))
    }

    private fun recorded(s: ComposeState, e: ComposeEvent.Recorded): ComposeStep {
        if (s.phase != ComposePhase.RECORDING) return ComposeStep(s)
        val d = if (e.durationMs > 0) e.durationMs else s.durationMs
        if (!e.ok || d < PhoneMessageLimits.MIN_DURATION_MS) {
            return ComposeStep(ComposeState(cameraOn = s.cameraOn, failure = if (e.ok) null else "recording_failed"),
                listOf(ComposeEffect.DeleteTake, ComposeEffect.OpenCamera(s.cameraOn)))
        }
        return ComposeStep(s.copy(phase = ComposePhase.REVIEW, startedMs = null, durationMs = d), listOf(ComposeEffect.CloseCamera))
    }
}

/** Compose and message copy for the phone (the Portal's wording, phone-sized). */
object PhoneMessageCopy {
    fun title(name: String) = "Message for $name"

    fun sub(phase: ComposePhase, failure: String?): String = when (phase) {
        ComposePhase.READY -> "Up to 2 minutes · they’ll get it on their Portal"
        ComposePhase.RECORDING -> "Recording · camera and mic on"
        ComposePhase.REVIEW -> "Only you have seen this"
        ComposePhase.SENDING -> "Sending over your home Wi-Fi…"
        ComposePhase.SENT -> "Sent · end-to-end encrypted"
        ComposePhase.FAILED -> failure(failure)
    }

    fun failure(code: String?): String = when (code) {
        "messages_off" -> "They’ve turned video messages off on that Portal"
        "full" -> "That Portal has too many messages waiting"
        "no_space" -> "That Portal is out of space"
        "too_large", "too_long" -> "That message is too long to send"
        "busy" -> "That Portal is taking another message. Try again in a moment"
        "recording_failed" -> "The recording didn’t work. Try again"
        "unreachable" -> "Couldn’t reach that Portal. Are you on the home Wi-Fi?"
        else -> "It didn’t send. Your message is still here"
    }

    fun stopLabel(elapsedMs: Long) = "Stop · ${clock(elapsedMs)} of 2:00"

    /** Mock `phnotifs`: "Video message from Grandpa Walt" / "1:12". */
    fun notifyTitle(m: PhoneMessage) = "${if (m.audioOnly) "Audio" else "Video"} message from ${m.fromName}"

    fun kind(m: PhoneMessage) = "${if (m.audioOnly) "Audio message" else "Video message"} · ${clock(m.durationMs)}"

    fun clock(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

    const val STORED = "Kept encrypted in Portal Toolkit · deletes itself after 30 days"
}
