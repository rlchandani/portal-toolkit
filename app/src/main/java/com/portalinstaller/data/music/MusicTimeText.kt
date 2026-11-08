/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.music

/**
 * Time lines on the Music page. Times from the Portal ([PortalPlayer.sleepUntilMs],
 * [HistoryEntry.playedAtMs]) are wall-clock, so these compare them with the phone's wall clock;
 * phones and Portals both keep network time, so a few seconds of skew don't change a minute count.
 */
internal object MusicTimeText {
    private const val SECOND_MS = 1_000L
    private const val MINUTE_MS = 60 * SECOND_MS
    private const val HOUR_MS = 60 * MINUTE_MS
    private const val DAY_MS = 24 * HOUR_MS
    private const val WEEK_DAYS = 7

    /** "Just now" · "5 min ago" · "2 h ago" · "Yesterday" · "3 days ago" · "Over a week ago". */
    fun ago(thenMs: Long, nowMs: Long): String {
        val gone = (nowMs - thenMs).coerceAtLeast(0)
        return when {
            gone < MINUTE_MS -> "Just now"
            gone < HOUR_MS -> "${gone / MINUTE_MS} min ago"
            gone < DAY_MS -> "${gone / HOUR_MS} h ago"
            gone < 2 * DAY_MS -> "Yesterday"
            gone < WEEK_DAYS * DAY_MS -> "${gone / DAY_MS} days ago"
            else -> "Over a week ago"
        }
    }

    /** "Living Area · 2 h ago" (just the time when the room isn't known). */
    fun historyDetail(entry: HistoryEntry, nowMs: Long): String =
        listOfNotNull(entry.song.artist.ifBlank { null }, entry.room.ifBlank { null }, ago(entry.playedAtMs, nowMs))
            .joinToString(" · ")

    /** "Stops in 23 min" / "Stops after this song", or null when no sleep timer is running. */
    fun sleepLine(player: PortalPlayer, nowMs: Long): String? {
        if (player.sleepEndOfSong) return "Stops after this song"
        val until = player.sleepUntilMs ?: return null
        val left = until - nowMs
        if (left <= 0) return null
        return "Stops in ${(left + MINUTE_MS - 1) / MINUTE_MS} min"
    }

    fun nightCap(cap: NightCap?): String? = cap?.takeIf { it.active }?.let { "Quieter at night: max ${it.maxLevel}" }
}
