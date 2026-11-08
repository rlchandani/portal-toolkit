/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.share.music

import com.portalinstaller.share.ShareRoom
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Where a send ended up, across every chosen room. */
sealed interface MusicSendSummary {
    data class Done(val played: List<Pair<ShareRoom, SendResult.Played>>, val dead: List<ShareRoom>,
        val problems: List<String>) : MusicSendSummary
    /** The Portal said quiet hours (its clock knows best); ask, then resend to [rooms] with playAnyway. */
    data class AskQuiet(val rooms: List<ShareRoom>, val until: String?, val played: List<Pair<ShareRoom, SendResult.Played>>) : MusicSendSummary
    data class NoAnswer(val rooms: List<ShareRoom>) : MusicSendSummary
    data class Refused(val message: String) : MusicSendSummary
}

/** Confirmation copy for [MusicSendSummary.Done]. */
data class DoneCopy(val title: String, val body: String, val deadLine: String?)

object MusicSendRules {
    /** Sends to every room at once; Both rooms start together and may drift (no sync in v1). */
    suspend fun sendAll(rooms: List<ShareRoom>, send: suspend (ShareRoom) -> SendResult): List<Pair<ShareRoom, SendResult>> =
        coroutineScope { rooms.map { room -> async { room to send(room) } }.awaitAll() }

    fun summarize(results: List<Pair<ShareRoom, SendResult>>): MusicSendSummary {
        val played = results.mapNotNull { (room, r) -> (r as? SendResult.Played)?.let { room to it } }
        val quiet = results.filter { it.second is SendResult.Quiet }
        val dead = results.filter { it.second == SendResult.Offline }.map { it.first }
        val problems = results.mapNotNull { (_, r) -> (r as? SendResult.Declined)?.reason ?: (r as? SendResult.Failed)?.message }
        return when {
            quiet.isNotEmpty() -> MusicSendSummary.AskQuiet(quiet.map { it.first }, (quiet.first().second as SendResult.Quiet).until, played)
            played.isNotEmpty() -> MusicSendSummary.Done(played, dead, problems)
            problems.isNotEmpty() -> MusicSendSummary.Refused(problems.first())
            else -> MusicSendSummary.NoAnswer(dead.ifEmpty { results.map { it.first } })
        }
    }

    fun done(item: MusicItem, summary: MusicSendSummary.Done, requested: MusicAction, shuffle: Boolean, count: Int?): DoneCopy {
        val title = item.title
        val deadLine = summary.dead.firstOrNull()?.let { "${it.name} isn't answering. Nothing played there." }
            ?: summary.problems.firstOrNull()
        if (summary.played.size > 1) {
            val names = summary.played.joinToString(" and ") { it.first.name }
            return DoneCopy("Playing in both rooms", "“$title” started on $names. They may drift a little apart.", deadLine)
        }
        val (room, result) = summary.played.single()
        return when (result.action) {
            MusicAction.NEXT -> DoneCopy("Up next on ${room.name}", "“$title” plays after “${result.before ?: "the current song"}”.", deadLine)
            MusicAction.END -> {
                val n = result.ahead + 1
                DoneCopy("Added to ${room.name}'s queue", "$n ${if (n == 1) "song" else "songs"} ahead of it, including the one playing now.", deadLine)
            }
            MusicAction.NOW -> DoneCopy("${if (result.quietly) "Playing quietly" else "Playing"} on ${room.name}",
                nowBody(item, result, requested, shuffle, count), deadLine)
        }
    }

    private fun nowBody(item: MusicItem, r: SendResult.Played, requested: MusicAction, shuffle: Boolean, count: Int?): String = when {
        r.interrupted != null -> "“${r.interrupted}” carries on after it."
        requested != MusicAction.NOW -> "Nothing else was playing, so it started right away."
        r.quietly -> "Started at low volume because of quiet hours."
        item.kind == MusicItem.Kind.PLAYLIST -> listOfNotNull(count?.let { "$it songs" }, if (shuffle) "shuffled" else "in order")
            .joinToString(", ").replaceFirstChar { it.uppercaseChar() } + "."
        else -> "“${item.title}” is playing now."
    }
}
