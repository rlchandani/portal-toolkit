/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.music

import com.portalinstaller.data.companion.CompanionPlayback
import com.portalinstaller.data.companion.PlaybackVolume
import org.json.JSONArray
import org.json.JSONObject

/** One song in a Portal's YouTube queue. [entryId] is the Portal's queue slot id (not the video id). */
internal data class PortalTrack(
    val entryId: Long,
    val videoId: String,
    val title: String,
    val artist: String,
    val thumb: String?,
    val durationMs: Long?,
    /** Why the Portal's official player refused it, or null. */
    val unplayable: String?,
)

internal enum class PlayerState { IDLE, PLAYING, PAUSED }

/**
 * One Portal's YouTube player as `/remote/ytmusic` reported it. [receivedAtMs] is the phone's
 * monotonic clock when the answer arrived; [livePositionMs] ticks from there while playing.
 */
internal data class PortalPlayer(
    val state: PlayerState,
    val onScreen: Boolean,
    val ad: Boolean,
    val current: PortalTrack?,
    val positionMs: Long,
    val durationMs: Long,
    val upNext: List<PortalTrack>,
    val upNextTotal: Int,
    val label: String,
    val halt: String?,
    val account: String?,
    val quiet: Boolean,
    val quietUntil: String?,
    val volume: PlaybackVolume?,
    val receivedAtMs: Long,
    /** This Hearthpane reports a sleep timer (and so accepts `{cmd:"sleep"}`). */
    val sleepSupported: Boolean = false,
    /** The Portal's wall-clock time music stops, or null when no timed sleep is set. */
    val sleepUntilMs: Long? = null,
    val sleepEndOfSong: Boolean = false,
    val nightCap: NightCap? = null,
) {
    val playing: Boolean get() = state == PlayerState.PLAYING

    fun livePositionMs(nowMs: Long): Long {
        val moved = if (playing && !ad) (nowMs - receivedAtMs).coerceAtLeast(0) else 0
        val raw = positionMs + moved
        return if (durationMs > 0) raw.coerceAtMost(durationMs) else raw
    }

    fun withVolume(next: PlaybackVolume?): PortalPlayer = copy(volume = next)
}

/** "Quieter music during quiet hours": while [active], music volume stops at [maxLevel]. */
internal data class NightCap(val active: Boolean, val maxLevel: Int)

/** The sleep timer choices, as minutes on the wire (0 = off, -1 = end of song). */
internal enum class SleepChoice(val minutes: Int, val title: String) {
    OFF(0, "Off"),
    MIN_15(15, "15 min"),
    MIN_30(30, "30 min"),
    MIN_60(60, "60 min"),
    END_OF_SONG(-1, "End of song"),
}

/** What the Music page knows about one room. */
internal sealed interface RoomMusic {
    val volume: PlaybackVolume? get() = null

    data object Loading : RoomMusic
    data class Ready(val player: PortalPlayer) : RoomMusic {
        override val volume: PlaybackVolume? get() = player.volume
    }
    /** An older Hearthpane without `/remote/ytmusic`: volume still works through `/remote/nowplaying`. */
    data class Legacy(override val volume: PlaybackVolume?) : RoomMusic
    data object Offline : RoomMusic
    data class Failed(val message: String) : RoomMusic
}

/** The Portal's answer to a transport or queue command. */
internal sealed interface CommandResult {
    data object Done : CommandResult
    /** Play arrived while Music wasn't on screen: the Portal is opening it. */
    data object Opening : CommandResult
    data class Quiet(val until: String?) : CommandResult
    data class Declined(val reason: String) : CommandResult
    data class Failed(val message: String) : CommandResult
}

/** A transport or queue command, as the Portal's wire names it. */
internal sealed interface MusicCommand {
    val startsPlayback: Boolean get() = false
    fun body(playAnyway: Boolean = false): JSONObject

    data object Play : MusicCommand {
        override val startsPlayback = true
        override fun body(playAnyway: Boolean) = cmd("play").put("playAnyway", playAnyway)
    }
    data object Pause : MusicCommand { override fun body(playAnyway: Boolean) = cmd("pause") }
    data object Next : MusicCommand { override fun body(playAnyway: Boolean) = cmd("next") }
    data object Previous : MusicCommand { override fun body(playAnyway: Boolean) = cmd("previous") }
    data class Seek(val positionMs: Long) : MusicCommand {
        override fun body(playAnyway: Boolean) = cmd("seek").put("positionMs", positionMs.coerceAtLeast(0))
    }
    data class Jump(val entryId: Long) : MusicCommand {
        override val startsPlayback = true
        override fun body(playAnyway: Boolean) = cmd("jump").put("entryId", entryId).put("playAnyway", playAnyway)
    }
    data class Remove(val entryId: Long) : MusicCommand {
        override fun body(playAnyway: Boolean) = cmd("remove").put("entryId", entryId)
    }
    data class PlayNext(val entryId: Long) : MusicCommand {
        override fun body(playAnyway: Boolean) = cmd("playnext").put("entryId", entryId)
    }
    /** Plays a search result or a recently played song now, replacing what's playing. */
    data class PlayTrack(val song: SongRef) : MusicCommand {
        override val startsPlayback = true
        override fun body(playAnyway: Boolean) = song.into(cmd("playtrack")).put("playAnyway", playAnyway)
    }
    /** Queues a song right after the current one ([next]) or at the end. */
    data class QueueTrack(val song: SongRef, val next: Boolean) : MusicCommand {
        override fun body(playAnyway: Boolean) = song.into(cmd("queuetrack")).put("position", if (next) "next" else "end")
    }
    data class Sleep(val choice: SleepChoice) : MusicCommand {
        override fun body(playAnyway: Boolean) = cmd("sleep").put("minutes", choice.minutes)
    }

    private companion object {
        fun cmd(name: String): JSONObject = JSONObject().put("cmd", name)
    }
}

/** Relative or absolute STREAM_MUSIC change for the existing `/remote/volume` route. */
internal sealed interface VolumeChange {
    fun body(): JSONObject

    data object Up : VolumeChange { override fun body(): JSONObject = JSONObject().put("dir", "up") }
    data object Down : VolumeChange { override fun body(): JSONObject = JSONObject().put("dir", "down") }
    data object ToggleMute : VolumeChange { override fun body(): JSONObject = JSONObject().put("dir", "mute") }
    data class Set(val level: Int) : VolumeChange {
        override fun body(): JSONObject = JSONObject().put("dir", "set").put("level", level)
    }

    /** True when this makes the room louder (asked about first during quiet hours). */
    fun raises(current: PlaybackVolume?): Boolean = when (this) {
        Up -> true
        Down -> false
        ToggleMute -> current?.muted == true
        is Set -> current == null || current.muted || level > current.level
    }

    /** The level to show right away, before the next poll confirms it. */
    fun expected(current: PlaybackVolume?): PlaybackVolume? = when {
        current == null || !current.canSet -> current
        this is Set -> current.copy(level = level.coerceIn(0, current.max), muted = false)
        this == ToggleMute -> current.copy(muted = !current.muted)
        else -> current
    }
}

internal object PortalMusicParser {
    /** Upper bound for durations/positions from the wire (a week), like [CompanionPlayback]. */
    const val MAX_MS = 7L * 24 * 60 * 60 * 1000
    private const val MAX_TEXT = 200
    private const val MAX_QUEUE = 200
    /** STREAM_MUSIC never has more steps than this; anything larger is a bad answer. */
    private const val MAX_LEVEL = 100

    fun player(json: JSONObject, receivedAtMs: Long): PortalPlayer {
        val p = json.optJSONObject("player") ?: json
        val current = p.optJSONObject("current")?.let(::track)
        val state = when {
            current == null -> PlayerState.IDLE
            p.optString("state") == "playing" -> PlayerState.PLAYING
            else -> PlayerState.PAUSED
        }
        val duration = p.optLong("durationMs", 0).coerceIn(0, MAX_MS)
        val upNext = tracks(p.optJSONArray("upNext"))
        return PortalPlayer(
            state = state,
            onScreen = p.optBoolean("onScreen"),
            ad = p.optBoolean("ad"),
            current = current,
            positionMs = p.optLong("positionMs", 0).coerceIn(0, if (duration > 0) duration else MAX_MS),
            durationMs = duration,
            upNext = upNext,
            upNextTotal = p.optInt("upNextTotal", upNext.size).coerceAtLeast(upNext.size),
            label = text(p, "label").orEmpty(),
            halt = p.optJSONObject("halt")?.let { text(it, "title") },
            account = text(p, "account"),
            quiet = p.optBoolean("quiet"),
            quietUntil = text(p, "quietUntil"),
            volume = volume(p.optJSONObject("volume")),
            receivedAtMs = receivedAtMs,
            sleepSupported = p.has("sleepUntilMs") || p.has("sleepEndOfSong"),
            sleepUntilMs = if (p.isNull("sleepUntilMs")) null else p.optLong("sleepUntilMs", 0).takeIf { it > 0 },
            sleepEndOfSong = p.optBoolean("sleepEndOfSong"),
            nightCap = p.optJSONObject("nightCap")?.let { nightCap(it) },
        )
    }

    private fun nightCap(json: JSONObject): NightCap? {
        val level = json.optInt("maxLevel", -1).takeIf { it in 0..MAX_LEVEL } ?: return null
        return NightCap(json.optBoolean("active"), level)
    }

    /** Reuses the Remote tab's validated volume parsing (`np.volume` and `player.volume` share a shape). */
    fun volume(json: JSONObject?): PlaybackVolume? =
        json?.let { CompanionPlayback.fromJson(JSONObject().put("volume", it)).volume }

    /** `/remote/nowplaying` answer → its volume (older Portals). */
    fun nowPlayingVolume(json: JSONObject): PlaybackVolume? = volume(json.optJSONObject("np")?.optJSONObject("volume"))

    fun command(json: JSONObject, room: String): CommandResult = when (json.optString("status")) {
        "done" -> CommandResult.Done
        "opening" -> CommandResult.Opening
        "quiet" -> CommandResult.Quiet(text(json, "quietUntil"))
        "declined" -> CommandResult.Declined(text(json, "reason") ?: MusicCopy.declined(room))
        else -> CommandResult.Failed(MusicCopy.failed(room))
    }

    private fun tracks(array: JSONArray?): List<PortalTrack> =
        if (array == null) emptyList()
        else (0 until minOf(array.length(), MAX_QUEUE)).mapNotNull { array.optJSONObject(it)?.let(::track) }

    private fun track(json: JSONObject): PortalTrack? {
        val id = json.optLong("entryId", 0).takeIf { it > 0 } ?: return null
        return PortalTrack(
            entryId = id,
            videoId = json.optString("videoId"),
            title = text(json, "title") ?: "YouTube video",
            artist = text(json, "artist").orEmpty(),
            thumb = text(json, "thumb")?.takeIf { it.startsWith("https://") },
            durationMs = if (json.isNull("durationMs")) null else json.optLong("durationMs", 0).takeIf { it in 1..MAX_MS },
            unplayable = text(json, "unplayable"),
        )
    }

    internal fun text(json: JSONObject, key: String): String? =
        if (!json.has(key) || json.isNull(key)) null else json.optString(key).trim().take(MAX_TEXT).ifEmpty { null }
}
