/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.music

import com.portalinstaller.data.companion.CompanionClient
import com.portalinstaller.data.companion.CompanionException
import com.portalinstaller.data.companion.CompanionSession
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * One paired Portal's music library: YouTube search (uses the Portal's daily quota), local-only
 * suggestions, and the household "Recently played" history. Every call gives up after
 * [PortalMusicClient.ANSWER_MS] (search after [SEARCH_MS]: the Portal asks YouTube first).
 */
internal class MusicLibraryClient(private val client: CompanionClient) {
    suspend fun history(session: CompanionSession): LibraryResult<MusicHistory> =
        call(session, PortalMusicClient.ANSWER_MS, MusicLibraryParser::history) { client.get(session, HISTORY_ROUTE) }

    suspend fun removeFromHistory(session: CompanionSession, videoId: String): LibraryResult<Unit> =
        call(session, PortalMusicClient.ANSWER_MS, {}) {
            client.post(session, HISTORY_ROUTE, JSONObject().put("action", "remove").put("videoId", videoId))
        }

    suspend fun clearHistory(session: CompanionSession): LibraryResult<Unit> =
        call(session, PortalMusicClient.ANSWER_MS, {}) {
            client.post(session, HISTORY_ROUTE, JSONObject().put("action", "clear"))
        }

    suspend fun suggest(session: CompanionSession, query: String): LibraryResult<List<Suggestion>> =
        call(session, PortalMusicClient.ANSWER_MS, MusicLibraryParser::suggestions) {
            client.post(session, SUGGEST_ROUTE, JSONObject().put("q", query))
        }

    suspend fun search(session: CompanionSession, query: String): LibraryResult<SearchFound> =
        call(session, SEARCH_MS, MusicLibraryParser::search) { client.post(session, SEARCH_ROUTE, JSONObject().put("q", query)) }

    private suspend fun <T> call(
        session: CompanionSession,
        timeoutMs: Long,
        parse: (JSONObject) -> T,
        block: suspend () -> JSONObject,
    ): LibraryResult<T> {
        val room = session.device.name
        return try {
            LibraryResult.Ok(parse(withTimeout(timeoutMs) { block() }))
        } catch (e: TimeoutCancellationException) { LibraryResult.Failed(MusicCopy.offline(room)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (e: CompanionException) {
            when {
                e.error.status == NOT_FOUND -> LibraryResult.Legacy
                e.error.status == TOO_MANY || e.error.code == QUOTA -> LibraryResult.Quota
                e.error.code == UNAUTHORIZED -> LibraryResult.Failed(MusicCopy.repair(room))
                else -> LibraryResult.Failed(MusicCopy.failed(room))
            }
        }
        catch (_: IOException) { LibraryResult.Failed(MusicCopy.offline(room)) }
    }

    companion object {
        const val HISTORY_ROUTE = "ytmusic/history"
        const val SUGGEST_ROUTE = "ytmusic/suggest"
        const val SEARCH_ROUTE = "ytmusic/search"
        /** The Portal waits on YouTube's search API, so allow longer than a local answer. */
        const val SEARCH_MS = 10_000L
        private const val NOT_FOUND = 404
        private const val TOO_MANY = 429
        private const val QUOTA = "quota"
        private const val UNAUTHORIZED = "unauthorized"
    }
}
