/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.share.music

import com.portalinstaller.share.ShareRoom
import org.junit.Assert.assertEquals
import org.junit.Test

/** The default-action rule and every sentence of the sheet (prototype copy). */
class MusicShareRulesTest {
    private val family = ShareRoom("fa", "Family Area")
    private val living = ShareRoom("la", "Living Area")
    private val playing = RoomStatus(RoomState.PLAYING, "Moss on the north wall", upNext = 3)
    private val idle = RoomStatus(RoomState.IDLE)

    @Test fun defaultAction_busyRoomPlaysNext_idlePlaysNow() {
        assertEquals(MusicAction.NEXT, MusicShareRules.defaultAction(listOf(MusicTarget(family, playing))))
        assertEquals(MusicAction.NOW, MusicShareRules.defaultAction(listOf(MusicTarget(family, idle))))
        assertEquals(MusicAction.NOW, MusicShareRules.defaultAction(listOf(MusicTarget(family, null))))
        assertEquals(MusicAction.NEXT, MusicShareRules.defaultAction(listOf(MusicTarget(family, idle), MusicTarget(living, playing))))
        assertEquals(MusicAction.NOW, MusicShareRules.defaultAction(listOf(MusicTarget(family, RoomStatus(RoomState.QUIET)))))
    }

    @Test fun hints_nameTheSongThatIsPlaying() {
        val busy = listOf(MusicTarget(family, playing))
        assertEquals("Family Area is playing “Moss on the north wall”. Play now jumps in, and “Moss on the north wall” carries on after.",
            MusicShareRules.hint(MusicAction.NOW, busy))
        assertEquals("Plays on Family Area after “Moss on the north wall”.", MusicShareRules.hint(MusicAction.NEXT, busy))
        assertEquals("Joins the end of Family Area's queue, after 4 songs.", MusicShareRules.hint(MusicAction.END, busy))
        assertEquals("Joins the end of Family Area's queue, after 1 song.",
            MusicShareRules.hint(MusicAction.END, listOf(MusicTarget(family, playing.copy(upNext = 0)))))
    }

    @Test fun hints_forIdleQuietOfflineAndBoth() {
        assertEquals("Nothing is playing there. Living Area wakes and starts right away.",
            MusicShareRules.hint(MusicAction.NOW, listOf(MusicTarget(living, idle))))
        assertEquals("Nothing is playing there. Both Portals wake and start right away.",
            MusicShareRules.hint(MusicAction.NOW, listOf(MusicTarget(family, idle), MusicTarget(living, idle))))
        assertEquals("Living Area is in quiet hours until 7:00 AM.",
            MusicShareRules.hint(MusicAction.NOW, listOf(MusicTarget(living, RoomStatus(RoomState.QUIET, quietUntil = "7:00 AM")))))
        assertEquals("Living Area isn't answering right now. You can still try.",
            MusicShareRules.hint(MusicAction.NOW, listOf(MusicTarget(living, RoomStatus(RoomState.OFFLINE)))))
    }

    @Test fun primaryButton_mirrorsTheChoice() {
        assertEquals("Play on Living Area", MusicShareRules.sendLabel(MusicAction.NOW, listOf(living)))
        assertEquals("Play next on Family Area", MusicShareRules.sendLabel(MusicAction.NEXT, listOf(family)))
        assertEquals("Add to Family Area", MusicShareRules.sendLabel(MusicAction.END, listOf(family)))
        assertEquals("Play in both rooms", MusicShareRules.sendLabel(MusicAction.NOW, listOf(family, living)))
        assertEquals("Add in both rooms", MusicShareRules.sendLabel(MusicAction.END, listOf(family, living)))
        assertEquals("Waking Living Area…", MusicShareRules.sendingLabel(listOf(living)))
        assertEquals("Waking both rooms…", MusicShareRules.sendingLabel(listOf(family, living)))
    }

    @Test fun roomSubtitles() {
        assertEquals("Playing “Moss on the north wall”", MusicShareRules.roomSubtitle(playing))
        assertEquals("Quiet hours until 7:00 AM", MusicShareRules.roomSubtitle(RoomStatus(RoomState.QUIET, quietUntil = "7:00 AM")))
        assertEquals("Not answering", MusicShareRules.roomSubtitle(RoomStatus(RoomState.OFFLINE)))
        assertEquals("Nothing playing", MusicShareRules.roomSubtitle(idle))
        assertEquals("Checking…", MusicShareRules.roomSubtitle(null))
    }

    @Test fun quietAndOfflineCopy() {
        assertEquals("Living Area is in quiet hours. Play anyway?", MusicShareRules.quietQuestion(listOf(living)))
        assertEquals("Family Area and Living Area are in quiet hours. Play anyway?", MusicShareRules.quietQuestion(listOf(family, living)))
        assertEquals("Quiet hours end at 7:00 AM. Someone may be asleep. If you play anyway, it starts at low volume.",
            MusicShareRules.quietDetail("7:00 AM"))
        assertEquals("Living Area isn't answering", MusicShareRules.offlineTitle(listOf(living)))
    }

    @Test fun cardSubtitles() {
        val list = MusicItem(MusicItem.Kind.PLAYLIST, "PLx", "Sunday slow", "Rohit", null)
        assertEquals("Playlist · 24 songs", MusicShareRules.cardSubtitle(list, 24))
        assertEquals("Playlist", MusicShareRules.cardSubtitle(list, null))
        assertEquals("Playlist from YouTube Music", MusicShareRules.sheetSubtitle(list))
        assertEquals("From a YouTube Music mix",
            MusicShareRules.sheetSubtitle(MusicItem(MusicItem.Kind.SONG, "x", "t", "a", null, fromMix = true)))
    }
}
