/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.chores

import com.portalinstaller.data.companion.CompanionClient
import com.portalinstaller.data.companion.CompanionDevice
import com.portalinstaller.data.companion.CompanionRequest
import com.portalinstaller.data.companion.CompanionResponse
import com.portalinstaller.data.companion.CompanionSession
import com.portalinstaller.data.companion.CompanionSessionStore
import com.portalinstaller.data.companion.CompanionTransport
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reads every paired Portal and merges their replicas; a command goes to one Portal. */
class ChoresRepositoryTest {
    private val kitchen = CompanionSession(CompanionDevice("kitchen", "Kitchen", "http://kitchen:8723"), "t1")
    private val den = CompanionSession(CompanionDevice("den", "Den", "http://den:8723"), "t2")
    private val store = object : CompanionSessionStore {
        override suspend fun load() = listOf(kitchen, den)
        override suspend fun save(sessions: List<CompanionSession>) {}
    }

    private class Http(val handler: (CompanionRequest) -> CompanionResponse) : CompanionTransport {
        val calls = mutableListOf<CompanionRequest>()
        override suspend fun execute(request: CompanionRequest): CompanionResponse { calls += request; return handler(request) }
    }

    private fun kid(id: String, name: String, goal: Int, seq: Long) =
        ChoreKid(id, name, "🦊", 0, goal, "", seq, "kitchen")

    private fun answer(vararg kids: ChoreKid) = CompanionResponse(200, JSONObject().put("ok", true).put("ready", true)
        .put("state", ChoreCodec.encodeState(ChoreState(kids = kids.associateBy { it.id }))).toString())

    private fun repo(http: Http) = ChoresRepository(CompanionClient(http), store)

    @Test fun mergesEveryPortalsReplica() = runTest {
        val repository = repo(Http { r ->
            if (r.url.startsWith("http://kitchen")) answer(kid("kidmia", "Mia", 10, 3))
            else answer(kid("kidmia", "Mia", 6, 2), kid("kidleo", "Leo", 0, 1))
        })
        repository.refresh()
        val board = repository.state.value.board
        assertEquals(listOf("Leo", "Mia"), board.liveKids.map { it.name })
        assertEquals(10, board.kids.getValue("kidmia").goal)
    }

    @Test fun aCommandGoesToOnePortalAndItsAnswerIsMerged() = runTest {
        val http = Http { r ->
            when {
                r.url.startsWith("http://kitchen") -> throw IOException("down")
                r.method == "POST" -> answer(kid("kidzoe1", "Zoe", 5, 1))
                else -> answer()
            }
        }
        val repository = repo(http)
        repository.refresh()
        assertTrue(repository.command(ChoreCommand.SaveKid(KidDraft(name = "Zoe", goal = 5))))
        val post = http.calls.last { it.method == "POST" }
        assertEquals("http://den:8723/remote/chores", post.url)
        assertEquals("kid_save", JSONObject(post.body!!).getString("action"))
        assertEquals(listOf("Zoe"), repository.state.value.board.liveKids.map { it.name })
        assertFalse(repository.state.value.sending)
    }

    @Test fun aRefusalShowsFriendlyCopy() = runTest {
        val repository = repo(Http { r ->
            if (r.method == "POST") CompanionResponse(409, JSONObject().put("ok", false).put("error", "too_many_kids").toString())
            else answer()
        })
        repository.refresh()
        assertFalse(repository.command(ChoreCommand.SaveKid(KidDraft(name = "Zoe"))))
        assertEquals(ChoreCopy.refused("too_many_kids"), repository.state.value.notice?.text)
    }

    @Test fun awayFromHomeAndOlderPortals() = runTest {
        val away = repo(Http { throw IOException("no route") })
        away.refresh()
        assertTrue(away.state.value.notOnHomeWifi)
        assertFalse(away.command(ChoreCommand.Approve("tick0001")))
        assertEquals(ChoreCopy.OFFLINE, away.state.value.notice?.text)
        val old = repo(Http { CompanionResponse(404, JSONObject().put("ok", false).put("error", "not_found").toString()) })
        old.refresh()
        assertTrue(old.state.value.needsUpdate)
    }
}
