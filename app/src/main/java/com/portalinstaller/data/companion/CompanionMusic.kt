/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.companion

import org.json.JSONArray
import org.json.JSONObject

/**
 * A copied observation of /remote/music schema 1. Unknown timings and absent metadata stay null.
 * Artwork is deliberately absent from this wire protocol.
 */
data class CompanionMusic(
    val schemaVersion: Int,
    val sourceRevision: Long,
    val sourceId: String,
    val connection: String,
    val playback: String,
    val audioOwnedByOther: Boolean,
    val controlPlayerId: String?,
    val queueId: String?,
    val track: MusicTrack?,
    val positionMs: Long?,
    val capabilities: MusicCapabilities,
    val queue: List<MusicQueueItem>,
    val rooms: List<MusicRoom>,
    val lyrics: List<MusicLyric>,
    val pending: MusicPending?,
    val lastCommand: MusicResult?,
) {
    data class MusicTrack(
        val id: String,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long?,
        val isLive: Boolean,
    )

    data class MusicCapabilities(
        val play: Boolean,
        val pause: Boolean,
        val next: Boolean,
        val previous: Boolean,
        val seek: Boolean,
        val queue: Boolean,
        val lyrics: Boolean,
        val rooms: Boolean,
        val volume: Boolean,
        val selectPlayer: Boolean,
    )

    data class MusicQueueItem(
        val id: String,
        val title: String,
        val artist: String,
        val durationMs: Long?,
        val available: Boolean,
        val canSelect: Boolean,
    )

    data class MusicRoom(
        val id: String,
        val name: String,
        val available: Boolean,
        val volume: Int?,
        val muted: Boolean?,
        val groupId: String?,
        val inCurrentGroup: Boolean,
        val canSelect: Boolean,
        val canJoin: Boolean,
        val canRemove: Boolean,
        val canSetVolume: Boolean,
    )

    data class MusicLyric(val timeMs: Long, val text: String)
    data class MusicPending(val requestId: Long, val command: String, val startedAtElapsedMs: Long)
    data class MusicResult(val requestId: Long, val command: String, val outcome: String)

    companion object {
        /** Requires the route's root.music envelope, including on command readback responses. */
        fun fromResponse(response: JSONObject): CompanionMusic = fromJson(objectValue(response, "music"))

        /** Parses a music object directly. Malformed or unsupported data throws IllegalArgumentException. */
        fun fromJson(json: JSONObject): CompanionMusic {
            val schema = integer(json, "schemaVersion")
            require(schema == 1L) { "Unsupported music schema." }
            val revision = integer(json, "sourceRevision", 0)
            val source = string(json, "sourceId")
            val connection = string(json, "connection")
            require(connection in setOf("not_configured", "connecting", "ready", "offline",
                "auth_required", "incompatible")) { "Invalid music connection." }
            val playback = string(json, "playback")
            require(playback in setOf("playing", "paused", "stopped", "idle")) { "Invalid music playback." }
            val track = nullableObject(json, "track")?.let { raw ->
                MusicTrack(
                    string(raw, "id"), string(raw, "title"), string(raw, "artist"),
                    string(raw, "album"), nullableInteger(raw, "durationMs", 1),
                    boolean(raw, "isLive"),
                )
            }
            val position = nullableInteger(json, "positionMs", 0)
            // The server only publishes a position for non-live tracks with known duration.
            require(position == null || (track != null && !track.isLive &&
                track.durationMs != null && position <= track.durationMs)) { "Invalid music position." }
            require(track == null || !track.isLive || track.durationMs == null) { "Invalid live music duration." }
            val caps = objectValue(json, "capabilities")
            val capabilities = MusicCapabilities(
                boolean(caps, "play"), boolean(caps, "pause"), boolean(caps, "next"),
                boolean(caps, "previous"), boolean(caps, "seek"), boolean(caps, "queue"),
                boolean(caps, "lyrics"), boolean(caps, "rooms"), boolean(caps, "volume"),
                boolean(caps, "selectPlayer"),
            )
            val queue = rows(json, "queue", 500) { raw ->
                MusicQueueItem(
                    string(raw, "id"), string(raw, "title"), string(raw, "artist"),
                    nullableInteger(raw, "durationMs", 1), boolean(raw, "available"),
                    boolean(raw, "canSelect"),
                )
            }
            val rooms = rows(json, "rooms", 64) { raw ->
                MusicRoom(
                    string(raw, "id"), string(raw, "name"), boolean(raw, "available"),
                    nullableInteger(raw, "volume", 0, 100)?.toInt(), nullableBoolean(raw, "muted"),
                    nullableString(raw, "groupId"), boolean(raw, "inCurrentGroup"),
                    boolean(raw, "canSelect"), boolean(raw, "canJoin"), boolean(raw, "canRemove"),
                    boolean(raw, "canSetVolume"),
                )
            }
            val lyrics = rows(json, "lyrics", 1000) { raw ->
                MusicLyric(integer(raw, "timeMs", 0), string(raw, "text"))
            }
            val pending = nullableObject(json, "pending")?.let { raw ->
                MusicPending(integer(raw, "requestId", 1), string(raw, "command"),
                    integer(raw, "startedAtElapsedMs", 0))
            }
            val result = nullableObject(json, "lastCommand")?.let { raw ->
                val outcome = string(raw, "outcome")
                require(outcome in setOf("confirmed", "failed", "timed_out", "disconnected")) {
                    "Invalid music command outcome."
                }
                MusicResult(integer(raw, "requestId", 1), string(raw, "command"), outcome)
            }
            return CompanionMusic(
                1, revision, source, connection, playback, boolean(json, "audioOwnedByOther"),
                nullableString(json, "controlPlayerId"), nullableString(json, "queueId"),
                track, position, capabilities, queue, rooms, lyrics, pending, result,
            )
        }

        private fun required(json: JSONObject, key: String): Any {
            require(json.has(key)) { "Missing music field: $key." }
            return json.opt(key) ?: throw IllegalArgumentException("Missing music field: $key.")
        }

        private fun nullable(json: JSONObject, key: String): Any? =
            required(json, key).takeUnless { it === JSONObject.NULL }

        private fun objectValue(json: JSONObject, key: String): JSONObject =
            required(json, key) as? JSONObject
                ?: throw IllegalArgumentException("Invalid music object: $key.")

        private fun nullableObject(json: JSONObject, key: String): JSONObject? {
            val raw = nullable(json, key) ?: return null
            return raw as? JSONObject ?: throw IllegalArgumentException("Invalid music object: $key.")
        }

        private fun text(raw: Any, key: String): String {
            require(raw is String && raw.length <= COMPANION_MUSIC_MAX_TEXT) { "Invalid music text: $key." }
            return raw
        }

        private fun string(json: JSONObject, key: String): String = text(required(json, key), key)
        private fun nullableString(json: JSONObject, key: String): String? =
            nullable(json, key)?.let { text(it, key) }

        private fun boolean(json: JSONObject, key: String): Boolean =
            required(json, key) as? Boolean ?: throw IllegalArgumentException("Invalid music boolean: $key.")

        private fun nullableBoolean(json: JSONObject, key: String): Boolean? {
            val raw = nullable(json, key) ?: return null
            return raw as? Boolean ?: throw IllegalArgumentException("Invalid music boolean: $key.")
        }

        // Match MusicRemoteProtocol's runtime integer types; JSON coercing getters are forbidden.
        private fun whole(raw: Any, key: String, min: Long, max: Long): Long {
            val value = when (raw) {
                is Byte -> raw.toLong()
                is Short -> raw.toLong()
                is Int -> raw.toLong()
                is Long -> raw
                else -> throw IllegalArgumentException("Invalid music integer: $key.")
            }
            require(value in min..max) { "Music integer out of range: $key." }
            return value
        }

        private fun integer(json: JSONObject, key: String, min: Long = Long.MIN_VALUE,
            max: Long = Long.MAX_VALUE): Long = whole(required(json, key), key, min, max)

        private fun nullableInteger(json: JSONObject, key: String, min: Long,
            max: Long = Long.MAX_VALUE): Long? = nullable(json, key)?.let { whole(it, key, min, max) }

        private fun <T> rows(json: JSONObject, key: String, limit: Int, parse: (JSONObject) -> T): List<T> {
            val array = required(json, key) as? JSONArray
                ?: throw IllegalArgumentException("Invalid music list: $key.")
            require(array.length() <= limit) { "Music list too large: $key." }
            return List(array.length()) { index ->
                val row = array.opt(index) as? JSONObject
                    ?: throw IllegalArgumentException("Invalid music row: $key.")
                parse(row)
            }
        }
    }
}

/** Actions contain intent only; observation bindings always come from the captured music model. */
sealed interface CompanionMusicAction {
    data class Transport(val command: String) : CompanionMusicAction
    data class Seek(val positionMs: Long) : CompanionMusicAction
    data class QueueItem(val id: String) : CompanionMusicAction
    data class Volume(val roomId: String, val percent: Int) : CompanionMusicAction
    data class ToggleRoom(val roomId: String) : CompanionMusicAction
    data class SelectPlayer(val roomId: String) : CompanionMusicAction
}

/**
 * Returns exactly the server's command key set, or null when the observed state disallows it.
 * The caller must retire stale observations before sending and refresh after command admission.
 */
fun CompanionMusic.commandBody(action: CompanionMusicAction): JSONObject? {
    if (schemaVersion != 1 || sourceRevision < 0 || connection != "ready" ||
        pending != null || audioOwnedByOther || !companionMusicValidId(sourceId)) return null

    fun base(command: String) = JSONObject().put("command", command)
        .put("sourceRevision", sourceRevision).put("sourceId", sourceId)
    fun observedRoom(id: String) = if (companionMusicValidId(id))
        rooms.take(64).firstOrNull { it.id == id }?.takeIf { it.available } else null

    return when (action) {
        is CompanionMusicAction.Transport -> {
            val media = track?.takeIf { companionMusicValidId(it.id) } ?: return null
            val allowed = when (action.command) {
                "play" -> capabilities.play
                "pause" -> capabilities.pause && playback in setOf("playing", "paused")
                "next" -> capabilities.next && !media.isLive
                "previous" -> capabilities.previous && !media.isLive
                else -> false
            }
            if (!allowed) null else base(action.command).put("trackId", media.id)
        }
        is CompanionMusicAction.Seek -> {
            val media = track?.takeIf { companionMusicValidId(it.id) } ?: return null
            val duration = media.durationMs?.takeIf { it > 0 } ?: return null
            if (!capabilities.seek || media.isLive || action.positionMs !in 0..duration) null
            else base("seek").put("trackId", media.id).put("positionMs", action.positionMs)
        }
        is CompanionMusicAction.QueueItem -> {
            if (!capabilities.queue || !companionMusicValidId(queueId) ||
                !companionMusicValidId(action.id) ||
                queue.take(500).none { it.id == action.id && it.available && it.canSelect }) null
            else base("queue-item").put("queueId", queueId).put("queueItemId", action.id)
        }
        is CompanionMusicAction.Volume -> {
            val room = observedRoom(action.roomId) ?: return null
            if (!capabilities.volume || !room.canSetVolume || action.percent !in 0..100) null
            else base("volume").put("roomId", room.id).put("percent", action.percent)
        }
        is CompanionMusicAction.ToggleRoom -> {
            val room = observedRoom(action.roomId) ?: return null
            val leader = controlPlayerId?.takeIf(::companionMusicValidId) ?: return null
            if (!capabilities.rooms || room.id == leader ||
                !(if (room.inCurrentGroup) room.canRemove else room.canJoin)) null
            else base("toggle-room").put("roomId", room.id).put("leaderId", leader)
                .put("wasInGroup", room.inCurrentGroup)
        }
        is CompanionMusicAction.SelectPlayer -> {
            val room = observedRoom(action.roomId) ?: return null
            if (!capabilities.selectPlayer || !room.canSelect) null
            else base("select-player").put("roomId", room.id)
        }
    }
}

fun CompanionMusic.can(action: CompanionMusicAction): Boolean = commandBody(action) != null

private const val COMPANION_MUSIC_MAX_TEXT = 4096
private fun companionMusicValidId(value: String?): Boolean =
    value != null && value.isNotBlank() && value.length <= COMPANION_MUSIC_MAX_TEXT
