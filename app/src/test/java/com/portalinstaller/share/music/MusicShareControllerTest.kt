/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.share.music

import com.portalinstaller.data.companion.CompanionClient
import com.portalinstaller.data.companion.CompanionDevice
import com.portalinstaller.data.companion.CompanionRequest
import com.portalinstaller.data.companion.CompanionResponse
import com.portalinstaller.data.companion.CompanionSession
import com.portalinstaller.data.companion.CompanionTransport
import com.portalinstaller.share.ShareRoom
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The whole sheet flow against fake Portals: check → pick → (quiet ask) → send → confirmation. */
@OptIn(ExperimentalCoroutinesApi::class)
class MusicShareControllerTest {
    private val family = ShareRoom("fa", "Family Area")
    private val living = ShareRoom("la", "Living Area")
    private val sessions = listOf(family, living).associate { it.id to CompanionSession(CompanionDevice(it.id, it.name, "http://${it.id}:8723"), "t") }
    private val link = MusicLink.Video("dQw4w9WgXcQ", true, "https://music.youtube.com/watch?v=dQw4w9WgXcQ")
    private val posts = mutableListOf<JSONObject>()
    private val remembered = mutableListOf<String>()

    private fun ok(json: JSONObject) = CompanionResponse(200, json.put("ok", true).toString())

    private fun TestScope.controller(room: (String, CompanionRequest) -> CompanionResponse): MusicShareController {
        val transport = CompanionTransport { r ->
            if (r.method == "POST") posts += JSONObject(r.body!!)
            room(r.url.substringAfter("//").substringBefore(':'), r)
        }
        return MusicShareController(link, null, this, MusicShareClient(CompanionClient(transport)) { sessions[it] },
            rooms = { listOf(family, living) },
            checker = MusicLinkChecker { 200 to """{"title":"Saltwater hours","author_name":"Marrow & Vale"}""" },
            from = { "Rohit's phone" }, shareId = "s1abc", rememberRoom = { remembered += it })
    }

    @Test fun idleRoom_playsNow_andRemembersTheRoom() = runTest {
        val c = controller { _, r -> if (r.method == "GET") ok(JSONObject().put("state", "idle"))
            else ok(JSONObject().put("status", "playing").put("action", "now")) }
        c.load("la")
        advanceUntilIdle()
        assertEquals(MusicScreen.Pick, c.screen)
        assertEquals("Saltwater hours", c.pick.item?.title)
        assertEquals(MusicAction.NOW, c.pick.action)
        c.startSend()
        advanceUntilIdle()
        val done = c.screen as MusicScreen.Done
        assertEquals("Living Area", done.summary.played.single().first.name)
        assertEquals(listOf("la"), remembered)
        assertEquals("now", posts.single().getString("action"))
    }

    @Test fun quietRoom_asksFirst_thenPlaysAnywayQuietly() = runTest {
        val c = controller { _, r -> if (r.method == "GET") ok(JSONObject().put("state", "quiet").put("quietUntil", "7:00 AM"))
            else ok(JSONObject().put("status", "playing").put("action", "now").put("quietly", true)) }
        c.load("la")
        advanceUntilIdle()
        c.startSend()
        val ask = c.screen as MusicScreen.AskQuiet
        assertEquals("7:00 AM", ask.until)
        assertTrue(posts.isEmpty())
        c.quietNo(ask)
        assertEquals(MusicScreen.Pick, c.screen)
        c.startSend()
        c.quietYes(c.screen as MusicScreen.AskQuiet)
        advanceUntilIdle()
        assertTrue(posts.single().getBoolean("playAnyway"))
        assertTrue((c.screen as MusicScreen.Done).summary.played.single().second.quietly)
    }

    @Test fun offlineRoom_saysSo_andOffersTheOtherRoom() = runTest {
        val c = controller { host, r ->
            if (host == "la") throw IOException("down")
            if (r.method == "GET") ok(JSONObject().put("state", "playing").put("nowPlaying", JSONObject().put("title", "Moss")))
            else ok(JSONObject().put("status", "queued").put("action", "next").put("before", "Moss"))
        }
        c.load("la")
        advanceUntilIdle()
        c.startSend()
        advanceUntilIdle()
        val none = c.screen as MusicScreen.NoAnswer
        assertEquals(listOf(living), none.rooms)
        assertEquals(family, c.otherRoom(none.rooms))
        c.playElsewhere(family)
        advanceUntilIdle()
        // Family Area is busy, so the default flipped to Play next.
        assertEquals("next", posts.last().getString("action"))
        assertTrue(c.screen is MusicScreen.Done)
    }

    @Test fun blockedLink_neverReachesThePicker() = runTest {
        val c = MusicShareController(link, null, this, MusicShareClient(CompanionClient { error("unused") }) { null },
            rooms = { emptyList() }, checker = MusicLinkChecker { 401 to null }, from = { "p" }, shareId = "s1abc", rememberRoom = {})
        c.load(null)
        advanceUntilIdle()
        assertEquals(Unplayable.EMBED, (c.screen as MusicScreen.Cant).reason)
    }
}
