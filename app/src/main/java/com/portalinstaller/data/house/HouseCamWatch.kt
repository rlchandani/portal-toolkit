/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.house

import androidx.compose.runtime.Immutable

/**
 * What the phone has seen from one camera (pure, unit tested), judged on the pictures themselves
 * exactly as Hearthpane does (`CcCamWatch` / `CcCamLiveness`): LIVE needs the bytes to change twice
 * within 15 s; otherwise "Snapshot · 3 h ago" from the camera's own capture time, else
 * "unchanged …" with no made-up stamp. The fetch time is never shown as the picture's time.
 */
@Immutable
data class HouseCamWatch(
    val hash: Long = 0L,
    val imageSince: Long = 0L,
    val lastFrameAt: Long = 0L,
    val lastChange: Long = 0L,
    val previousChange: Long = 0L,
    val captured: Long? = null,
) {
    val hasFrame: Boolean get() = lastFrameAt > 0

    fun frame(hash: Long, now: Long, captured: Long?): HouseCamWatch {
        val first = !hasFrame
        val changed = !first && hash != this.hash
        return copy(hash = hash, imageSince = if (changed || first) now else imageSince, lastFrameAt = now,
            lastChange = if (changed) now else lastChange, previousChange = if (changed) lastChange else previousChange,
            captured = captured ?: this.captured.takeIf { !changed })
    }
}

/** The tag in the corner: [live], its words, and the time to stamp on the picture (null = no honest time). */
@Immutable
data class HouseCamLabel(val live: Boolean, val text: String, val stampMs: Long?)

object HouseCamLiveness {
    const val LIVE_WINDOW_MS = 15_000L
    private const val S = 1000L
    private const val MIN = 60 * S
    private const val HOUR = 60 * MIN
    private const val DAY = 24 * HOUR
    private const val JUST_NOW_MS = 10 * S

    fun isLive(w: HouseCamWatch, now: Long): Boolean = w.hasFrame && w.lastChange > 0 && w.previousChange > 0 &&
        now - w.lastChange <= LIVE_WINDOW_MS && w.lastChange - w.previousChange <= LIVE_WINDOW_MS

    fun label(w: HouseCamWatch, now: Long): HouseCamLabel {
        if (isLive(w, now)) return HouseCamLabel(true, "LIVE", w.lastFrameAt)
        val captured = w.captured
        if (captured != null && captured > 0) return HouseCamLabel(false, "Snapshot · ${ago(now - captured)}", captured)
        if (w.lastChange > 0) return HouseCamLabel(false, "Snapshot · ${ago(now - w.lastChange)}", w.lastChange)
        if (!w.hasFrame) return HouseCamLabel(false, "Loading…", null)
        val held = now - w.imageSince
        return if (held < LIVE_WINDOW_MS) HouseCamLabel(false, "Snapshot · checking", null)
        else HouseCamLabel(false, "Snapshot · unchanged ${duration(held)}", null)
    }

    fun ago(ms: Long): String = if (ms < JUST_NOW_MS) "just now" else "${duration(ms)} ago"

    fun duration(ms: Long): String {
        val v = ms.coerceAtLeast(0)
        return when {
            v < MIN -> "${v / S} s"
            v < HOUR -> "${v / MIN} min"
            v < DAY -> "${v / HOUR} h"
            else -> "${v / DAY} d"
        }
    }

    /** 64-bit FNV-1a of the picture bytes: equal bytes, equal picture. */
    fun hash(bytes: ByteArray): Long {
        var h = FNV_OFFSET
        for (b in bytes) { h = h xor (b.toLong() and BYTE); h *= FNV_PRIME }
        return h
    }

    private const val FNV_OFFSET = -0x340d631b7bdddcdbL
    private const val FNV_PRIME = 0x100000001b3L
    private const val BYTE = 0xFFL
}
