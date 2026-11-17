/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.chores

import com.portalinstaller.data.routines.RoutinePictures
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * JSON form of a [ChoreState] — the same for storage, the Portal↔Portal exchange and the phone's
 * GET, so every side reads one shape:
 * `{v:1, kids:[{i,n,a,c,g,r,q,b,x}], chores:[{i,t,p,k,s,w,wd,od,ok,q,b,x}], ticks:[{i,c,k,d,s,st,q,b}]}`
 * (`k` "" = anyone; `w` d|w|o = daily, weekdays `wd`, once on epoch day `od`; `x` = tombstone).
 * Peers and files are untrusted: a bad entry is skipped, a bad envelope reads as null.
 */
object ChoreCodec {
    const val VERSION = 1
    private const val SEP = '\u0001'
    private const val DIGEST_CHARS = 12
    private const val MAX_SEQ = Long.MAX_VALUE / 2
    private const val MIN_DAY = 0
    private const val MAX_DAY = 200_000 // year 2517: anything past it is junk

    fun encodeState(s: ChoreState): JSONObject = JSONObject().put("v", VERSION)
            .put("kids", JSONArray(s.kids.values.sortedBy { it.id }.map(::kidJson)))
            .put("chores", JSONArray(s.chores.values.sortedBy { it.id }.map(::choreJson)))
            .put("ticks", JSONArray(s.ticks.values.sortedBy { it.id }.map(::tickJson)))

    fun decodeState(j: JSONObject?): ChoreState? {
        if (j == null || j.optInt("v", -1) != VERSION) return null
        val kids = j.optJSONArray("kids") ?: return null
        val chores = j.optJSONArray("chores") ?: return null
        val ticks = j.optJSONArray("ticks") ?: return null
        return ChoreState(
                kids = objects(kids).mapNotNull(::kid).associateBy { it.id },
                chores = objects(chores).mapNotNull(::chore).associateBy { it.id },
                ticks = objects(ticks).mapNotNull(::tick).associateBy { it.id },
        )
    }

    /** 12 hex chars that change whenever anything in the replica does (bus signal). */
    fun digest(s: ChoreState): String {
        val lines = s.kids.values.map(::canonicalKid) + s.chores.values.map(::canonicalChore) + s.ticks.values.map(::canonicalTick)
        val bytes = MessageDigest.getInstance("SHA-256").digest(lines.sorted().joinToString("\n").toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.take(DIGEST_CHARS)
    }

    fun canonicalKid(k: ChoreKid): String = listOf("k", k.id, k.seq, k.by, k.deleted, k.name, k.avatar, k.color, k.goal, k.reward).joinToString("$SEP")
    fun canonicalChore(c: Chore): String = listOf("c", c.id, c.seq, c.by, c.deleted, c.title, c.pic, c.kid.orEmpty(), c.stars,
            scheduleText(c.schedule), c.needsOk).joinToString("$SEP")
    fun canonicalTick(t: ChoreTick): String = listOf("t", t.id, t.seq, t.by, t.status.wire, t.chore, t.kid, t.day, t.stars).joinToString("$SEP")

    fun kidJson(k: ChoreKid): JSONObject = JSONObject().put("i", k.id).put("n", k.name).put("a", k.avatar).put("c", k.color)
            .put("g", k.goal).put("r", k.reward).put("q", k.seq).put("b", k.by).put("x", k.deleted)

    fun choreJson(c: Chore): JSONObject = JSONObject().put("i", c.id).put("t", c.title).put("p", c.pic).put("k", c.kid.orEmpty())
            .put("s", c.stars).put("ok", c.needsOk).put("q", c.seq).put("b", c.by).put("x", c.deleted).also { o ->
                when (val s = c.schedule) {
                    ChoreSchedule.Daily -> o.put("w", "d")
                    is ChoreSchedule.Weekdays -> o.put("w", "w").put("wd", JSONArray(s.days.sorted()))
                    is ChoreSchedule.Once -> o.put("w", "o").put("od", s.day)
                }
            }

    fun tickJson(t: ChoreTick): JSONObject = JSONObject().put("i", t.id).put("c", t.chore).put("k", t.kid).put("d", t.day)
            .put("s", t.stars).put("st", t.status.wire).put("q", t.seq).put("b", t.by)

    private fun kid(o: JSONObject): ChoreKid? {
        val id = (o.opt("i") as? String)?.takeIf(ChoreText::isId) ?: return null
        val name = ChoreText.clean(o.opt("n") as? String, ChoreLimits.NAME_MAX) ?: return null
        val avatar = (o.opt("a") as? String)?.takeIf(ChoreAvatars::isValid) ?: return null
        val color = int(o.opt("c"))?.takeIf { it in 0 until ChoreLimits.COLORS } ?: return null
        val goal = int(o.opt("g"))?.takeIf { it in 0..ChoreLimits.MAX_GOAL } ?: return null
        val reward = (o.opt("r") as? String)?.let { ChoreText.clean(it, ChoreLimits.REWARD_MAX) }.orEmpty()
        val (seq, by, deleted) = stamp(o) ?: return null
        return ChoreKid(id, name, avatar, color, goal, reward, seq, by, deleted)
    }

    private fun chore(o: JSONObject): Chore? {
        val id = (o.opt("i") as? String)?.takeIf(ChoreText::isId) ?: return null
        val title = ChoreText.clean(o.opt("t") as? String, ChoreLimits.TITLE_MAX) ?: return null
        val pic = (o.opt("p") as? String)?.takeIf(RoutinePictures::isValid) ?: return null
        val kid = (o.opt("k") as? String ?: return null).ifEmpty { null }
        if (kid != null && !ChoreText.isId(kid)) return null
        val stars = int(o.opt("s"))?.takeIf { it in ChoreLimits.MIN_STARS..ChoreLimits.MAX_STARS } ?: return null
        val schedule = schedule(o) ?: return null
        val needsOk = o.opt("ok") as? Boolean ?: return null
        val (seq, by, deleted) = stamp(o) ?: return null
        return Chore(id, title, pic, kid, stars, schedule, needsOk, seq, by, deleted)
    }

    private fun tick(o: JSONObject): ChoreTick? {
        val id = (o.opt("i") as? String)?.takeIf(ChoreText::isId) ?: return null
        val chore = (o.opt("c") as? String)?.takeIf(ChoreText::isId) ?: return null
        val kid = (o.opt("k") as? String)?.takeIf(ChoreText::isId) ?: return null
        val day = int(o.opt("d"))?.takeIf(::isDay) ?: return null
        val stars = int(o.opt("s"))?.takeIf { it in ChoreLimits.MIN_STARS..ChoreLimits.MAX_STARS } ?: return null
        val status = TickStatus.fromWire(o.opt("st") as? String) ?: return null
        val seq = long(o.opt("q"))?.takeIf { it in 1L..MAX_SEQ } ?: return null
        val by = (o.opt("b") as? String)?.takeIf(ChoreText::isDevice) ?: return null
        return ChoreTick(id, chore, kid, day, stars, status, seq, by)
    }

    /** Daily, weekdays (1..7, at least one) or once on a day. */
    fun schedule(o: JSONObject): ChoreSchedule? = when (o.opt("w")) {
        "d" -> ChoreSchedule.Daily
        "w" -> {
            val arr = o.optJSONArray("wd")
            val days = arr?.let { a -> (0 until a.length()).map { int(a.opt(it)) } }
            if (days == null || days.isEmpty() || days.any { it == null || it !in 1..7 }) null
            else ChoreSchedule.Weekdays(days.filterNotNull().toSet())
        }
        "o" -> int(o.opt("od"))?.takeIf(::isDay)?.let { ChoreSchedule.Once(it) }
        else -> null
    }

    fun isDay(day: Int): Boolean = day in MIN_DAY..MAX_DAY

    private fun scheduleText(s: ChoreSchedule): String = when (s) {
        ChoreSchedule.Daily -> "d"
        is ChoreSchedule.Weekdays -> "w" + s.days.sorted().joinToString(",")
        is ChoreSchedule.Once -> "o${s.day}"
    }

    private fun stamp(o: JSONObject): Triple<Long, String, Boolean>? {
        val seq = long(o.opt("q"))?.takeIf { it in 1L..MAX_SEQ } ?: return null
        val by = (o.opt("b") as? String)?.takeIf(ChoreText::isDevice) ?: return null
        val deleted = o.opt("x") as? Boolean ?: return null
        return Triple(seq, by, deleted)
    }

    private fun objects(arr: JSONArray): List<JSONObject> = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }

    /** Whole numbers only (org.json yields Int/Long for them); fractions and strings are junk. */
    fun long(raw: Any?): Long? = when (raw) {
        is Int -> raw.toLong()
        is Long -> raw
        else -> null
    }

    fun int(raw: Any?): Int? = long(raw)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
}
