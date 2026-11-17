/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.timers

import com.portalinstaller.data.companion.CompanionClient
import com.portalinstaller.data.companion.CompanionDevice
import com.portalinstaller.data.companion.CompanionRequest
import com.portalinstaller.data.companion.CompanionResponse
import com.portalinstaller.data.companion.CompanionSession
import com.portalinstaller.data.companion.CompanionSessionStore
import com.portalinstaller.data.companion.CompanionTransport
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reads every paired Portal, keeps the newest version of each timer, sends a command to one Portal. */
class TimersRepositoryTest {
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

    private fun timer(id: String, seq: Long, state: String = "run") = JSONObject().put("id", id).put("name", "Pasta").put("room", "Kitchen")
        .put("durationMs", 600_000).put("remainingMs", 300_000).put("state", state).put("paused", state == "pause").put("seq", seq)

    private fun list(vararg timers: JSONObject) = CompanionResponse(200,
        JSONObject().put("ok", true).put("timers", JSONArray(timers.toList())).toString())

    private fun repo(http: Http) = TimersRepository(CompanionClient(http), store, "Rohit’s Pixel", clock = { 0L })

    @Test fun mergesEveryPortalKeepingTheNewestVersion() = runTest {
        val http = Http { r ->
            if (r.url.startsWith("http://kitchen")) list(timer("aaaaaaaa", 3, "pause"))
            else list(timer("aaaaaaaa", 2), timer("bbbbbbbb", 1))
        }
        val repository = repo(http)
        repository.refresh()
        val state = repository.state.value
        assertEquals(2, state.timers.size)
        assertEquals(TimerState.PAUSED, state.timers.first { it.id == "aaaaaaaa" }.state)
        assertFalse(state.notOnHomeWifi)
    }

    @Test fun commandGoesToThePortalWithTheNewestCopyAndFoldsTheAnswer() = runTest {
        val http = Http { r ->
            when {
                r.method == "POST" -> CompanionResponse(200, JSONObject().put("ok", true).put("timer", timer("bbbbbbbb", 2, "pause")).toString())
                r.url.startsWith("http://kitchen") -> list()
                else -> list(timer("bbbbbbbb", 1))
            }
        }
        val repository = repo(http)
        repository.refresh()
        assertTrue(repository.command(TimerCommand.Pause("bbbbbbbb")))
        val post = http.calls.last { it.method == "POST" }
        assertTrue(post.url == "http://den:8723/remote/timers")
        assertEquals("pause", JSONObject(post.body!!).getString("action"))
        assertEquals(TimerState.PAUSED, repository.state.value.timers.single().state)
        assertTrue(repository.state.value.pending.isEmpty())
    }

    @Test fun anUnreachablePortalIsSkippedForTheNextOne() = runTest {
        val http = Http { r ->
            if (r.url.startsWith("http://kitchen")) throw IOException("down")
            if (r.method == "POST") CompanionResponse(200, JSONObject().put("ok", true).put("timer", timer("cccccccc", 1)).toString())
            else list()
        }
        val repository = repo(http)
        repository.refresh()
        assertTrue(repository.command(TimerCommand.Start("Tea", 180_000L)))
        assertEquals("from", "Rohit’s Pixel", JSONObject(http.calls.last().body!!).getString("from"))
        assertEquals(listOf("cccccccc"), repository.state.value.timers.map { it.id })
    }

    @Test fun refusalShowsANoticeAndRereads() = runTest {
        val http = Http { r ->
            if (r.method == "POST") CompanionResponse(409, JSONObject().put("ok", false).put("error", "invalid_state").toString())
            else list(timer("aaaaaaaa", 1))
        }
        val repository = repo(http)
        repository.refresh()
        assertFalse(repository.command(TimerCommand.Stop("aaaaaaaa")))
        assertEquals("That timer already changed in another room.", repository.state.value.notice?.text)
    }

    @Test fun noAnswerAnywhereMeansAwayFromHomeAndOlderPortalsNeedAnUpdate() = runTest {
        val away = repo(Http { throw IOException("no route") })
        away.refresh()
        assertTrue(away.state.value.notOnHomeWifi)
        assertFalse(away.command(TimerCommand.Start("Tea", 60_000L)))
        assertEquals(TimersRepository.OFFLINE, away.state.value.notice?.text)
        val old = repo(Http { CompanionResponse(404, JSONObject().put("ok", false).put("error", "not_found").toString()) })
        old.refresh()
        assertTrue(old.state.value.needsUpdate)
        assertFalse(old.state.value.notOnHomeWifi)
    }
}
