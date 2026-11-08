/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.share.music

import com.portalinstaller.share.ShareRoom
import com.portalinstaller.share.ShareRooms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The picker remembers only the room; the action follows the live rule until the user picks one. */
class MusicSheetStateTest {
    private val family = ShareRoom("fa", "Family Area")
    private val living = ShareRoom("la", "Living Area")
    private val options = ShareRooms.options(listOf(family, living))
    private val playing = RoomStatus(RoomState.PLAYING, "Moss on the north wall")

    @Test fun lastUsedRoomIsPreselected_elseTheFirst() {
        assertEquals("la", MusicPick().withRooms(options, "la").selected)
        assertEquals(ShareRooms.ALL_KEY, MusicPick().withRooms(options, ShareRooms.ALL_KEY).selected)
        assertEquals("fa", MusicPick().withRooms(options, "gone").selected)
    }

    @Test fun actionFollowsTheBusyRule_untilTouched() {
        val pick = MusicPick().withRooms(options, "fa")
        assertEquals(MusicAction.NOW, pick.action)
        val busy = pick.withStatus("fa", playing)
        assertEquals(MusicAction.NEXT, busy.action)
        assertEquals(MusicAction.NOW, busy.select("la").action)
        assertEquals(MusicAction.NEXT, busy.select(ShareRooms.ALL_KEY).action)
        val chosen = busy.choose(MusicAction.NOW)
        assertEquals(MusicAction.NOW, chosen.withStatus("la", playing).select("la").action)
    }

    @Test fun quietRoomsAreAskedAboutFirst() {
        val pick = MusicPick().withRooms(options, ShareRooms.ALL_KEY).withStatus("la", RoomStatus(RoomState.QUIET, quietUntil = "7:00 AM"))
        assertEquals(listOf(living), pick.quietRooms)
    }

    @Test fun itemOf_prefersOEmbedThenSubject() {
        val video = MusicLink.Video("dQw4w9WgXcQ", false, "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        val meta = MusicMeta("Saltwater hours", "Marrow & Vale", null)
        assertEquals("Saltwater hours", MusicPick.itemOf(video, meta, "ignored")?.title)
        assertEquals("From subject", MusicPick.itemOf(video, null, " From subject ")?.title)
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", MusicPick.itemOf(video, meta, null)?.thumb)
        assertNull(MusicPick.itemOf(MusicLink.Mix("RDAMVMdQw4w9WgXcQ", null, "u"), meta, null))
    }

    @Test fun request_shufflesOnlyPlaylists() {
        val song = MusicItem(MusicItem.Kind.SONG, "dQw4w9WgXcQ", "t", "a", null)
        val pick = MusicPick(item = song, shuffle = true, action = MusicAction.END)
        val r = pick.request("Rohit's phone", "s1abc", playAnyway = true)!!
        assertEquals("video", r.kind)
        assertFalse(r.shuffle)
        assertEquals(MusicAction.END, r.action)
        val list = pick.copy(item = song.copy(kind = MusicItem.Kind.PLAYLIST)).request("p", "s1abc", false)!!
        assertEquals("playlist", list.kind)
        assertEquals(true, list.shuffle)
        assertNull(MusicPick().request("p", "s", false))
    }
}
