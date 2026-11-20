/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.reminders

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shows one Portal's replica; a command goes to that Portal first, person-bound ones only there. */
class RemindersRepositoryTest {
    private val kitchen = CompanionSession(CompanionDevice("kitchen", "Kitchen", "http://kitchen:8723"), "t1")
    private val den = CompanionSession(CompanionDevice("den", "Den", "http://den:8723"), "t2")
    private fun store(vararg sessions: CompanionSession) = object : CompanionSessionStore {
        override suspend fun load() = sessions.toList()
        override suspend fun save(sessions: List<CompanionSession>) {}
    }

    private class Http(val handler: (CompanionRequest) -> CompanionResponse) : CompanionTransport {
        val calls = mutableListOf<CompanionRequest>()
        override suspend fun execute(request: CompanionRequest): CompanionResponse { calls += request; return handler(request) }
    }

    private fun answer(portal: String, vararg titles: String, ready: Boolean = true) = CompanionResponse(200, JSONObject()
        .put("ok", true).put("ready", ready).put("portal", portal)
        .put("reminders", JSONArray(titles.mapIndexed { i, t -> JSONObject().put("id", "r$i").put("title", t).put("seq", i) }))
        .put("people", JSONArray().put(JSONObject().put("id", "p_maya").put("name", "Maya").put("kid", false)))
        .put("privateCounts", JSONObject().put("Maya", 1)).toString())

    private fun refusal(status: Int, code: String) = CompanionResponse(status, JSONObject().put("ok", false).put("error", code).toString())

    private fun repo(http: Http, vararg sessions: CompanionSession = arrayOf(kitchen, den)) =
        RemindersRepository(CompanionClient(http), store(*sessions))

    @Test fun showsTheFirstAnsweringPortal() = runTest {
        val http = Http { r -> if (r.url.startsWith("http://kitchen")) answer("Kitchen", "Bins out") else answer("Den", "Other") }
        val repository = repo(http)
        repository.refresh()
        val s = repository.state.value
        assertEquals("Kitchen", s.board?.portal)
        assertEquals(listOf("Bins out"), s.board?.reminders?.map { it.title })
        assertEquals(mapOf("Maya" to 1), s.board?.privateCounts)
        assertTrue(s.loaded && !s.loading && !s.notReady)
        assertEquals(2, s.portals)
        assertEquals("http://kitchen:8723/remote/reminders", http.calls.first { it.url.startsWith("http://kitchen") }.url)
    }

    @Test fun aCommandGoesToThePortalOnScreenAndItsAnswerReplacesTheBoard() = runTest {
        val http = Http { r ->
            when {
                r.url.startsWith("http://kitchen") -> throw IOException("down")
                r.method == "POST" -> answer("Den", "Bins out", "Vet")
                else -> answer("Den", "Bins out")
            }
        }
        val repository = repo(http)
        repository.refresh()
        assertTrue(repository.command(ReminderCommand.save(ReminderDraft(title = "Vet"))))
        val post = http.calls.last { it.method == "POST" }
        assertEquals("http://den:8723/remote/reminders", post.url)
        assertEquals("add", JSONObject(post.body!!).getString("action"))
        assertEquals(listOf("Bins out", "Vet"), repository.state.value.board?.reminders?.map { it.title })
        assertFalse(repository.state.value.sending)
    }

    @Test fun householdCommandsFallOverButPersonCommandsDoNot() = runTest {
        var kitchenUp = true
        val http = Http { r ->
            if (r.url.startsWith("http://kitchen") && !kitchenUp) throw IOException("down")
            if (r.url.startsWith("http://kitchen")) answer("Kitchen", "Bins out") else answer("Den", "Bins out")
        }
        val repository = repo(http)
        repository.refresh()
        kitchenUp = false
        assertFalse(repository.command(ReminderCommand.save(ReminderDraft(title = "Gift").forPerson(ReminderPerson("p_maya", "Maya", false)))))
        assertEquals(ReminderCopy.OFFLINE, repository.state.value.notice?.text)
        assertTrue(http.calls.none { it.method == "POST" && it.url.startsWith("http://den") })
        assertTrue(repository.command(ReminderCommand.Done("r0", 0)))
        assertEquals("http://den:8723/remote/reminders", http.calls.last { it.method == "POST" }.url)
    }

    @Test fun aRefusalShowsFriendlyCopyAndRereads() = runTest {
        val http = Http { r -> if (r.method == "POST") refusal(404, "unknown_reminder") else answer("Kitchen", "Bins out") }
        val repository = repo(http)
        repository.refresh()
        val reads = http.calls.count { it.method == "GET" }
        assertFalse(repository.command(ReminderCommand.Delete("r9", 3)))
        assertEquals(ReminderCopy.refused("unknown_reminder"), repository.state.value.notice?.text)
        assertTrue(http.calls.count { it.method == "GET" } > reads)
    }

    @Test fun anUnreadableSuccessRereads() = runTest {
        val http = Http { r ->
            if (r.method == "POST") CompanionResponse(200, JSONObject().put("ok", true).toString()) else answer("Kitchen", "Bins out")
        }
        val repository = repo(http, kitchen)
        repository.refresh()
        assertTrue(repository.command(ReminderCommand.Undo("r0", 0)))
        assertEquals(listOf("Bins out"), repository.state.value.board?.reminders?.map { it.title })
    }

    @Test fun notReadyAwayAndOlderPortals() = runTest {
        val locked = repo(Http { answer("Kitchen", ready = false) }, kitchen)
        locked.refresh()
        assertTrue(locked.state.value.notReady)

        val away = repo(Http { throw IOException("no route") })
        away.refresh()
        assertTrue(away.state.value.notOnHomeWifi)
        assertNull(away.state.value.board)
        assertFalse(away.command(ReminderCommand.Done("r1", 1)))
        assertEquals(ReminderCopy.OFFLINE, away.state.value.notice?.text)

        val old = repo(Http { refusal(404, "not_found") })
        old.refresh()
        assertTrue(old.state.value.needsUpdate)
        assertFalse(old.command(ReminderCommand.Done("r1", 1)))

        val none = repo(Http { answer("Kitchen") }, *emptyArray())
        none.refresh()
        assertEquals(0, none.state.value.portals)
        assertFalse(none.state.value.notOnHomeWifi)
    }

    @Test fun aBrokenSessionStoreShowsANotice() = runTest {
        val broken = object : CompanionSessionStore {
            override suspend fun load(): List<CompanionSession> = throw IllegalStateException("corrupt")
            override suspend fun save(sessions: List<CompanionSession>) {}
        }
        val logged = mutableListOf<String>()
        val repository = RemindersRepository(CompanionClient(Http { answer("Kitchen") }), broken) { logged += it }
        repository.refresh()
        assertTrue(repository.state.value.loaded)
        assertEquals("Couldn’t read your paired Portals.", repository.state.value.notice?.text)
        assertEquals(1, logged.size)
    }

    @Test fun anErrorStatusReadsAsOffline() = runTest {
        val repository = repo(Http { refusal(500, "boom") }, kitchen)
        repository.refresh()
        assertTrue(repository.state.value.notOnHomeWifi)
    }
}
