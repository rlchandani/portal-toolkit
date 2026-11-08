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
 * One paired Portal's YouTube player (`/remote/ytmusic`) and speaker volume (`/remote/volume`,
 * `/remote/nowplaying`). Every call gives up after [ANSWER_MS]: a room that doesn't answer is
 * reported as offline, never retried in the background.
 */
internal class PortalMusicClient(
    private val client: CompanionClient,
    private val clock: () -> Long,
) {
    suspend fun read(session: CompanionSession): RoomMusic {
        val room = session.device.name
        return try {
            RoomMusic.Ready(PortalMusicParser.player(call { client.get(session, ROUTE) }, clock()))
        } catch (e: TimeoutCancellationException) { RoomMusic.Offline }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (e: CompanionException) {
            when {
                e.error.status == NOT_FOUND -> RoomMusic.Legacy(legacyVolume(session))
                e.error.code == UNAUTHORIZED -> RoomMusic.Failed(MusicCopy.repair(room))
                else -> RoomMusic.Failed(MusicCopy.failed(room))
            }
        }
        catch (_: IOException) { RoomMusic.Offline }
    }

    suspend fun command(session: CompanionSession, command: MusicCommand, playAnyway: Boolean): CommandResult {
        val room = session.device.name
        return try {
            PortalMusicParser.command(call { client.post(session, ROUTE, command.body(playAnyway)) }, room)
        } catch (e: TimeoutCancellationException) { CommandResult.Failed(MusicCopy.offline(room)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (e: CompanionException) { CommandResult.Failed(failure(e, room)) }
        catch (_: IOException) { CommandResult.Failed(MusicCopy.offline(room)) }
    }

    /** Null on success, else the message to show. Works on every Hearthpane, playing or not. */
    suspend fun volume(session: CompanionSession, change: VolumeChange): String? {
        val room = session.device.name
        return try {
            call { client.post(session, VOLUME_ROUTE, change.body()) }
            null
        } catch (e: TimeoutCancellationException) { MusicCopy.offline(room) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (e: CompanionException) {
            if (e.error.code == VOLUME_FIXED) MusicCopy.volumeFixed(room) else failure(e, room)
        }
        catch (_: IOException) { MusicCopy.offline(room) }
    }

    private suspend fun legacyVolume(session: CompanionSession) = try {
        PortalMusicParser.nowPlayingVolume(call { client.get(session, NOW_PLAYING_ROUTE) })
    } catch (cancelled: CancellationException) {
        if (cancelled is TimeoutCancellationException) null else throw cancelled
    } catch (_: IOException) { null }

    private suspend fun call(block: suspend () -> JSONObject): JSONObject = withTimeout(ANSWER_MS) { block() }

    private fun failure(e: CompanionException, room: String): String = when {
        e.error.status == NOT_FOUND -> MusicCopy.update(room)
        e.error.code == UNAUTHORIZED -> MusicCopy.repair(room)
        e.error.code == "nothing_queued" -> MusicCopy.nothingQueued(room)
        e.error.code == "unknown_entry" -> MusicCopy.unknownEntry(room)
        else -> MusicCopy.failed(room)
    }

    companion object {
        const val ROUTE = "ytmusic"
        const val VOLUME_ROUTE = "volume"
        const val NOW_PLAYING_ROUTE = "nowplaying"
        const val ANSWER_MS = 3_500L
        private const val NOT_FOUND = 404
        private const val UNAUTHORIZED = "unauthorized"
        private const val VOLUME_FIXED = "volume_fixed"
    }
}
