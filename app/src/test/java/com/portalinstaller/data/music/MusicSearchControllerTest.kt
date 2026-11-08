/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.music

import com.portalinstaller.data.companion.CompanionClient
import com.portalinstaller.data.companion.CompanionDevice
import com.portalinstaller.data.companion.CompanionRequest
import com.portalinstaller.data.companion.CompanionResponse
import com.portalinstaller.data.companion.CompanionSession
import com.portalinstaller.data.companion.CompanionTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MusicSearchControllerTest {
    private val kitchen = CompanionSession(CompanionDevice("k-id", "Kitchen", "http://kitchen:8723"), "t1")
    private val den = CompanionSession(CompanionDevice("d-id", "Den", "http://den:8723"), "t2")

    private class Http(val handler: (CompanionRequest) -> CompanionResponse) : CompanionTransport {
        val calls = mutableListOf<CompanionRequest>()
        override suspend fun execute(request: CompanionRequest): CompanionResponse { calls += request; return handler(request) }
        fun to(route: String) = calls.filter { it.url.endsWith("/remote/$route") }
    }

    private fun ok(json: JSONObject = JSONObject()) = CompanionResponse(200, json.put("ok", true).toString())
    private fun error(status: Int, code: String) = CompanionResponse(status, JSONObject().put("ok", false).put("error", code).toString())

    private fun history(vararg ids: String) = ok(JSONObject().put("total", ids.size).put("history", JSONArray().apply {
        ids.forEachIndexed { i, id -> put(JSONObject().put("videoId", id).put("title", "Song $id").put("playedAtMs", 1_000L - i).put("room", "Kitchen")) }
    }))

    private fun suggestions(vararg texts: String) = ok(JSONObject().put("suggestions", JSONArray().apply {
        texts.forEach { put(JSONObject().put("text", it).put("kind", "search")) }
    }))

    private fun tracks() = ok(JSONObject().put("quotaLeft", 3).put("tracks", JSONArray()
        .put(JSONObject().put("videoId", "v1").put("title", "Hello").put("artist", "Adele"))))

    private fun routes(
        suggest: (String) -> CompanionResponse = { suggestions("from portal") },
        search: () -> CompanionResponse = { tracks() },
        history: () -> CompanionResponse = { history("a", "b") },
    ) = Http { r ->
        when {
            r.url.endsWith("/suggest") -> suggest(JSONObject(r.body!!).getString("q"))
            r.url.endsWith("/search") -> search()
            r.url.endsWith("/history") && r.method == "POST" -> ok()
            r.url.endsWith("/history") -> history()
            else -> error(404, "not_found")
        }
    }

    private fun CoroutineScope.controller(http: Http, store: RecentSearchStore = MemoryRecentSearchStore()) =
        MusicSearchController(MusicLibraryClient(CompanionClient(http)), { id -> listOf(kitchen, den).firstOrNull { it.device.id == id } },
            store, this)

    @Test fun typingWaitsForAPauseAndAsksOnlyForTheLastText() = runTest {
        val http = routes()
        val c = controller(http)
        c.attach("k-id")
        advanceUntilIdle()
        c.type("a")
        advanceTimeBy(100)
        c.type("ad")
        advanceTimeBy(100)
        c.type("ade")
        advanceTimeBy(MusicSuggestions.DEBOUNCE_MS - 1)
        runCurrent()
        assertTrue(http.to("ytmusic/suggest").isEmpty())
        advanceUntilIdle()
        val sent = http.to("ytmusic/suggest").map { JSONObject(it.body!!).getString("q") }
        assertEquals(listOf("ade"), sent)
        assertEquals(listOf("from portal"), c.state.value.suggestions.map { it.text })
    }

    @Test fun blankTextNeverAsksThePortalButShowsRecentSearches() = runTest {
        val http = routes()
        val c = controller(http, MemoryRecentSearchStore(listOf("queen", "abba")))
        c.attach("k-id")
        c.type("   ")
        advanceUntilIdle()
        assertTrue(http.to("ytmusic/suggest").isEmpty())
        assertEquals(listOf("queen", "abba"), c.state.value.suggestions.map { it.text })
    }

    @Test fun anAnswerForOlderTextIsDropped() = runTest {
        lateinit var c: MusicSearchController
        val http = routes(suggest = { q -> if (q == "ab") c.type("abc"); suggestions("stale for $q") })
        c = controller(http)
        c.attach("k-id")
        c.type("ab")
        advanceTimeBy(MusicSuggestions.DEBOUNCE_MS + 1)
        runCurrent()
        assertEquals("abc", c.state.value.query)
        assertTrue(c.state.value.suggestions.none { it.text == "stale for ab" })
    }

    @Test fun submitSearchesRemembersTheQueryAndShowsResults() = runTest {
        val store = MemoryRecentSearchStore()
        val http = routes()
        val c = controller(http, store)
        c.attach("k-id")
        c.type(" hello  adele ")
        c.submit()
        advanceUntilIdle()
        assertEquals("hello adele", JSONObject(http.to("ytmusic/search").single().body!!).getString("q"))
        assertEquals(listOf("hello adele"), store.load())
        val found = c.state.value.results as SearchResults.Found
        assertEquals(listOf("Hello"), found.tracks.map { it.title })
        assertEquals(3, found.quotaLeft)
        assertTrue(c.state.value.suggestions.isEmpty())
    }

    @Test fun usedUpQuotaSaysSoForTheRoom() = runTest {
        val c = controller(routes(search = { error(429, "quota") }))
        c.attach("k-id")
        c.submit("hello")
        advanceUntilIdle()
        assertEquals(SearchResults.Message(MusicCopy.quota("Kitchen")), c.state.value.results)
        assertEquals("YouTube searches are used up for today on Kitchen. Try again after midnight.", MusicCopy.quota("Kitchen"))
    }

    @Test fun noResultsSaysSo() = runTest {
        val c = controller(routes(search = { ok(JSONObject().put("tracks", JSONArray())) }))
        c.attach("k-id")
        c.submit("zzz")
        advanceUntilIdle()
        assertEquals(SearchResults.Message(MusicCopy.noResults("zzz")), c.state.value.results)
    }

    @Test fun olderHearthpaneIsMarkedUnsupportedAndStopsAsking() = runTest {
        val http = routes(history = { error(404, "not_found") })
        val c = controller(http)
        c.attach("k-id")
        advanceUntilIdle()
        assertTrue(c.state.value.unsupported)
        c.type("abc")
        advanceUntilIdle()
        assertTrue(http.to("ytmusic/suggest").isEmpty())
    }

    @Test fun historyLoadsOnAttachAndResetsForAnotherRoom() = runTest {
        val http = routes()
        val c = controller(http)
        c.attach("k-id")
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), (c.state.value.history as HistoryView.Ready).history.entries.map { it.song.videoId })
        c.type("x")
        c.attach("d-id")
        assertEquals("", c.state.value.query)
        assertEquals(HistoryView.Loading, c.state.value.history)
        advanceUntilIdle()
        assertTrue(http.to("ytmusic/history").last().url.startsWith("http://den"))
        c.attach(null)
        assertEquals(MusicSearchState(), c.state.value)
    }

    @Test fun removeDropsTheRowAndTellsThePortal() = runTest {
        val http = routes(history = { history("b") })
        val c = controller(http)
        c.attach("k-id")
        advanceUntilIdle()
        c.remove("a")
        advanceUntilIdle()
        val post = http.to("ytmusic/history").single { it.method == "POST" }
        assertEquals("remove", JSONObject(post.body!!).getString("action"))
        assertEquals("a", JSONObject(post.body!!).getString("videoId"))
    }

    @Test fun clearAsksFirstAndCancelSendsNothing() = runTest {
        val http = routes()
        val c = controller(http)
        c.attach("k-id")
        advanceUntilIdle()
        c.askClear()
        assertTrue(c.state.value.confirmClear)
        c.cancelClear()
        assertFalse(c.state.value.confirmClear)
        assertTrue(http.calls.none { it.method == "POST" })
        c.askClear()
        c.confirmClear()
        advanceUntilIdle()
        assertEquals("clear", JSONObject(http.to("ytmusic/history").single { it.method == "POST" }.body!!).getString("action"))
        assertEquals(MusicCopy.HISTORY_CLEARED, c.state.value.notice?.text)
    }

    @Test fun aNewSongPlayingRefreshesHistory() = runTest {
        val http = routes()
        val c = controller(http)
        c.attach("k-id")
        advanceUntilIdle()
        c.playingChanged("v1")
        c.playingChanged("v1")
        advanceUntilIdle()
        assertEquals(1, http.to("ytmusic/history").size)
        c.playingChanged("v2")
        advanceUntilIdle()
        assertEquals(2, http.to("ytmusic/history").size)
    }

    @Test fun clearEmptiesTheFieldAndResults() = runTest {
        val c = controller(routes())
        c.attach("k-id")
        c.submit("hello")
        advanceUntilIdle()
        c.clear()
        assertEquals("", c.state.value.query)
        assertEquals(SearchResults.Idle, c.state.value.results)
    }
}
