/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.chores

/**
 * Chores & stars: the household-wide replica every Portal holds. The phone's copy of Hearthpane's
 * model (ChoreModel/ChoreBoard/ChoreMerge/ChoreCodec): keep the two identical apart from style.
 * The phone never edits it locally; it merges what Portals report and sends commands.
 *
 * CRDT-ish rules, kept pure so every device and the tests agree (see [ChoreMerge]):
 * - Kids and chores are last-writer-wins registers: the highest ([seq], deleted, [by]) wins.
 *   Deleting keeps a tombstone so a late, older copy cannot bring the entry back.
 * - Every tick has its own id and is a register too: AWARDED/PENDING → APPROVED/DECLINED/UNDONE.
 *   Two Portals ticking the same chore at once both keep their tick; views count one per
 *   chore and day (the lowest id wins), so stars are never doubled.
 * - Weeks start Monday 00:00 local; totals are derived from ticks, so the reset needs no message.
 *   Ticks older than the current week plus [ChoreLimits.HISTORY_WEEKS] are pruned.
 */
data class ChoreKid(
        val id: String,
        val name: String,
        val avatar: String,
        val color: Int,
        /** Weekly star goal; 0 means no goal. */
        val goal: Int,
        /** What the goal earns ("Movie night"); may be empty. */
        val reward: String,
        val seq: Long,
        val by: String,
        val deleted: Boolean = false,
)

/** When a chore is due. Days are epoch days (local); weekdays are ISO (Monday 1 … Sunday 7). */
sealed interface ChoreSchedule {
    data object Daily : ChoreSchedule
    data class Weekdays(val days: Set<Int>) : ChoreSchedule
    data class Once(val day: Int) : ChoreSchedule
}

data class Chore(
        val id: String,
        val title: String,
        /** A bundled [RoutinePictures] id. */
        val pic: String,
        /** The kid it is for, or null for "anyone". */
        val kid: String?,
        val stars: Int,
        val schedule: ChoreSchedule,
        /** Ticks wait for a parent's OK on the phone before their stars count. */
        val needsOk: Boolean,
        val seq: Long,
        val by: String,
        val deleted: Boolean = false,
)

/**
 * Lifecycle of a tick. [counts] = its stars are in the weekly total; [holds] = the chore shows as
 * done (or waiting) for that day. [rank] breaks a same-seq tie: an ending beats an approval.
 */
enum class TickStatus(val wire: String, val rank: Int, val counts: Boolean, val holds: Boolean) {
    AWARDED("ok", 0, true, true),
    PENDING("wait", 0, false, true),
    APPROVED("yes", 1, true, true),
    DECLINED("no", 2, false, false),
    UNDONE("undo", 2, false, false);

    companion object {
        fun fromWire(code: String?): TickStatus? = entries.firstOrNull { it.wire == code }
    }
}

data class ChoreTick(
        val id: String,
        val chore: String,
        val kid: String,
        val day: Int,
        /** The chore's stars when it was ticked (a later edit doesn't rewrite the past). */
        val stars: Int,
        val status: TickStatus,
        val seq: Long,
        val by: String,
)

data class ChoreState(
        val kids: Map<String, ChoreKid> = emptyMap(),
        val chores: Map<String, Chore> = emptyMap(),
        val ticks: Map<String, ChoreTick> = emptyMap(),
) {
    val liveKids: List<ChoreKid> get() = kids.values.filterNot { it.deleted }.sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
    val liveChores: List<Chore> get() = chores.values.filterNot { it.deleted }.sortedWith(compareBy({ it.title.lowercase() }, { it.id }))
}

object ChoreLimits {
    const val MAX_KIDS = 8
    const val MAX_CHORES = 40
    const val MIN_STARS = 1
    const val MAX_STARS = 5
    const val MAX_GOAL = 500
    const val NAME_MAX = 16
    const val TITLE_MAX = 24
    const val REWARD_MAX = 24
    const val COLORS = 8
    /** Weeks of history kept before the current one. */
    const val HISTORY_WEEKS = 4
    /** Tombstones kept per kind (kids, chores); the oldest beyond this are forgotten. */
    const val MAX_TOMBSTONES = 100
    /** Ticks kept at most (≈ 5 weeks of a busy household); the oldest days go first. */
    const val MAX_TICKS = 3_000
    const val PHONE = "phone"
}

/**
 * Kid avatars: simple animal emoji that every Portal (Android 10) and phone draws, plus a colour
 * index into each app's own palette. Part of the wire contract: never rename or reorder.
 */
object ChoreAvatars {
    val all: List<String> = listOf("🦊", "🦉", "🐻", "🐼", "🐱", "🐶", "🐰", "🐸", "🦁", "🐯", "🐵", "🐧", "🐢", "🐙", "🦄", "🐝")
    fun isValid(avatar: String): Boolean = avatar in all
}

/** Shared text rules: printable, single line, trimmed, clipped by code points. */
object ChoreText {
    private val SPACES = Regex("\\s+")
    private val ID = Regex("^[a-z0-9]{4,24}$")
    private val DEVICE = Regex("^[A-Za-z0-9_-]{1,64}$")

    fun isId(text: String?): Boolean = text != null && ID.matches(text)
    fun isDevice(text: String?): Boolean = text != null && DEVICE.matches(text)

    /** Cleaned text, or null when nothing printable is left. */
    fun clean(raw: String?, max: Int): String? {
        if (raw == null) return null
        val printable = raw.take(max * 8).map { if (Character.isISOControl(it)) ' ' else it }.joinToString("").replace(SPACES, " ").trim()
        if (printable.isEmpty()) return null
        if (printable.codePointCount(0, printable.length) <= max) return printable
        return printable.substring(0, printable.offsetByCodePoints(0, max)).trimEnd()
    }
}
