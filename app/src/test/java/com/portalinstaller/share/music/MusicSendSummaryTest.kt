/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.share.music

import com.portalinstaller.share.ShareRoom
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** One room or both: what the confirmation says, including partial failures. */
class MusicSendSummaryTest {
    private val family = ShareRoom("fa", "Family Area")
    private val living = ShareRoom("la", "Living Area")
    private val song = MusicItem(MusicItem.Kind.SONG, "dQw4w9WgXcQ", "Saltwater hours", "Marrow & Vale", null)
    private fun played(action: MusicAction = MusicAction.NOW, interrupted: String? = null, before: String? = null,
        ahead: Int = 0, quietly: Boolean = false) = SendResult.Played(action, quietly, interrupted, before, ahead, 1)
    private fun done(vararg results: Pair<ShareRoom, SendResult>) = MusicSendRules.summarize(results.toList()) as MusicSendSummary.Done

    @Test fun playNow_copy() {
        assertEquals(DoneCopy("Playing on Living Area", "“Saltwater hours” is playing now.", null),
            MusicSendRules.done(song, done(living to played()), MusicAction.NOW, false, null))
        assertEquals("“Moss on the north wall” carries on after it.",
            MusicSendRules.done(song, done(family to played(interrupted = "Moss on the north wall")), MusicAction.NOW, false, null).body)
        assertEquals("Nothing else was playing, so it started right away.",
            MusicSendRules.done(song, done(family to played()), MusicAction.NEXT, false, null).body)
        val quiet = MusicSendRules.done(song, done(living to played(quietly = true)), MusicAction.NOW, false, null)
        assertEquals("Playing quietly on Living Area", quiet.title)
        assertEquals("Started at low volume because of quiet hours.", quiet.body)
    }

    @Test fun nextAndQueue_copy() {
        assertEquals(DoneCopy("Up next on Family Area", "“Saltwater hours” plays after “Moss on the north wall”.", null),
            MusicSendRules.done(song, done(family to played(MusicAction.NEXT, before = "Moss on the north wall")), MusicAction.NEXT, false, null))
        assertEquals(DoneCopy("Added to Family Area's queue", "4 songs ahead of it, including the one playing now.", null),
            MusicSendRules.done(song, done(family to played(MusicAction.END, ahead = 3)), MusicAction.END, false, null))
    }

    @Test fun playlist_copy() {
        val list = song.copy(kind = MusicItem.Kind.PLAYLIST, title = "Sunday slow")
        assertEquals("24 songs, shuffled.", MusicSendRules.done(list, done(family to played()), MusicAction.NOW, true, 24).body)
        assertEquals("In order.", MusicSendRules.done(list, done(family to played()), MusicAction.NOW, false, null).body)
    }

    @Test fun bothRooms_andPartialFailure() {
        val both = MusicSendRules.done(song, done(family to played(), living to played()), MusicAction.NOW, false, null)
        assertEquals("Playing in both rooms", both.title)
        assertEquals("“Saltwater hours” started on Family Area and Living Area. They may drift a little apart.", both.body)
        val partial = MusicSendRules.done(song, done(family to played(), living to SendResult.Offline), MusicAction.NOW, false, null)
        assertEquals("Playing on Family Area", partial.title)
        assertEquals("Living Area isn't answering. Nothing played there.", partial.deadLine)
    }

    @Test fun summaries() {
        assertEquals(MusicSendSummary.NoAnswer(listOf(living)), MusicSendRules.summarize(listOf(living to SendResult.Offline)))
        assertEquals(MusicSendSummary.Refused("A routine is on screen in Living Area. Nothing was played."),
            MusicSendRules.summarize(listOf(living to SendResult.Declined("A routine is on screen in Living Area. Nothing was played."))))
        val ask = MusicSendRules.summarize(listOf(family to played(), living to SendResult.Quiet("7:00 AM"))) as MusicSendSummary.AskQuiet
        assertEquals(listOf(living), ask.rooms)
        assertEquals("7:00 AM", ask.until)
        assertEquals(1, ask.played.size)
    }

    @Test fun sendAll_sendsToEveryRoom() = runTest {
        val sent = mutableListOf<String>()
        val results = MusicSendRules.sendAll(listOf(family, living)) { room -> sent += room.id; played() }
        assertEquals(setOf("fa", "la"), sent.toSet())
        assertTrue(results.all { it.second is SendResult.Played })
    }
}
