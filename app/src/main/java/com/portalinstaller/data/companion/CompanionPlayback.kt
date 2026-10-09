/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.companion

import org.json.JSONObject

/** Capability-driven UI data; older Portals retain their relative volume controls. */
internal data class CompanionPlayback(
    val active: Boolean,
    val playing: Boolean,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val positionMs: Long,
    val canSeek: Boolean,
    val artVersion: String?,
    val trackKey: String,
    val volume: PlaybackVolume?,
) {
    companion object {
        fun fromJson(json: JSONObject?): CompanionPlayback {
            val active = json?.optBoolean("active") == true
            val duration = json?.optLong("durationMs", 0)?.coerceIn(0, 7 * 24 * 60 * 60 * 1000L) ?: 0
            val title = json?.optString("title").orEmpty()
            val artist = json?.optString("artist").orEmpty()
            val album = json?.optString("album").orEmpty()
            val art = json?.takeIf { active && it.optBoolean("hasArt") && it.has("artVersion") }
                ?.opt("artVersion")?.takeUnless { it == JSONObject.NULL }?.toString()
            val volume = json?.optJSONObject("volume")?.let { raw ->
                val max = integerIndex(raw.opt("max")) ?: return@let null
                val level = integerIndex(raw.opt("level")) ?: return@let null
                if (max == 0 && level == 0 && raw.opt("canSet") == false)
                    PlaybackVolume(0, 0, raw.optBoolean("muted"), canSet = false)
                else if (max !in 1..1000 || level !in 0..max) null
                else PlaybackVolume(level, max, raw.optBoolean("muted"), raw.optBoolean("canSet"))
            }
            return CompanionPlayback(
                active, active && json?.optBoolean("playing") == true,
                if (active) title.ifBlank { "Audio on Portal" } else "Nothing playing",
                if (active) artist else "", if (active) album else "", duration,
                json?.optLong("positionMs", 0)?.coerceIn(0, duration) ?: 0,
                active && duration > 0 && json?.optBoolean("canSeek") == true,
                art, listOf(json?.optString("source").orEmpty(), title, artist, album, art).joinToString("\u0000"),
                volume,
            )
        }

        private fun integerIndex(value: Any?): Int? = when (value) {
            is Int -> value
            is Long -> value.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()
            else -> null
        }
    }
}

internal data class PlaybackVolume(val level: Int, val max: Int, val muted: Boolean, val canSet: Boolean)

internal fun playbackTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    val minutes = seconds / 60
    return if (minutes >= 60) "%d:%02d:%02d".format(java.util.Locale.ROOT, minutes / 60, minutes % 60, seconds % 60)
    else "%d:%02d".format(java.util.Locale.ROOT, minutes, seconds % 60)
}
