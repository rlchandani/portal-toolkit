/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.share.music

import com.portalinstaller.data.companion.CompanionClient
import com.portalinstaller.data.companion.CompanionException
import com.portalinstaller.data.companion.CompanionSession
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

enum class MusicAction(val wire: String) { NOW("now"), NEXT("next"), END("end") }

enum class RoomState { IDLE, PLAYING, QUIET, ROUTINE, OFFLINE, UNSUPPORTED }

/** One Portal's answer to the quick status call that colours the room list. */
data class RoomStatus(
    val state: RoomState,
    val nowPlaying: String? = null,
    val upNext: Int = 0,
    val quietUntil: String? = null,
) {
    val busy: Boolean get() = state == RoomState.PLAYING
}

/** What the phone sends; [kind] is "video" or "playlist". */
data class MusicSendRequest(
    val kind: String,
    val id: String,
    val action: MusicAction,
    val shuffle: Boolean,
    val from: String,
    val title: String,
    val artist: String,
    val thumb: String?,
    val playAnyway: Boolean,
    val shareId: String,
)

sealed interface SendResult {
    data class Played(val action: MusicAction, val quietly: Boolean, val interrupted: String?, val before: String?,
        val ahead: Int, val count: Int?) : SendResult
    data class Quiet(val until: String?) : SendResult
    data class Declined(val reason: String) : SendResult
    data object Offline : SendResult
    data class Failed(val message: String) : SendResult
}

/** Later news about a share: how many songs a playlist had, or why the player refused it. */
data class ShareFollowUp(val count: Int?, val skipped: String?)

/**
 * `/remote/musicshare` on each paired Portal directly (like dinner/guest requests). Every call
 * gives up after [ANSWER_MS]: music is a "now" intent, so a room that doesn't answer is reported,
 * never queued for later.
 */
class MusicShareClient(
    private val client: CompanionClient,
    private val sessionFor: suspend (String) -> CompanionSession?,
) {
    suspend fun status(roomId: String): RoomStatus {
        val session = sessionFor(roomId) ?: return RoomStatus(RoomState.OFFLINE)
        return try {
            parseStatus(withTimeout(ANSWER_MS) { client.get(session, ROUTE) })
        } catch (e: TimeoutCancellationException) { RoomStatus(RoomState.OFFLINE) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (e: CompanionException) { RoomStatus(if (e.error.status == 404) RoomState.UNSUPPORTED else RoomState.OFFLINE) }
        catch (_: IOException) { RoomStatus(RoomState.OFFLINE) }
    }

    suspend fun send(roomId: String, request: MusicSendRequest): SendResult {
        val session = sessionFor(roomId) ?: return SendResult.Failed(UNPAIRED)
        val room = session.device.name
        return try {
            parseSend(withTimeout(ANSWER_MS) { client.post(session, ROUTE, encode(request)) }, room)
        } catch (e: TimeoutCancellationException) { SendResult.Offline }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (e: CompanionException) { failure(e, room) }
        catch (_: IOException) { SendResult.Offline }
    }

    /** The share's later news, or null when the Portal no longer knows [shareId] or can't be asked. */
    suspend fun followUp(roomId: String, shareId: String): ShareFollowUp? {
        val session = sessionFor(roomId) ?: return null
        return try {
            val last = withTimeout(ANSWER_MS) { client.get(session, ROUTE) }.optJSONObject("lastShare") ?: return null
            if (last.optString("shareId") != shareId) return null
            ShareFollowUp(last.optInt("count", -1).takeIf { it >= 0 }, text(last, "skipped"))
        } catch (e: TimeoutCancellationException) { null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IOException) { null }
    }

    companion object {
        const val ROUTE = "musicshare"
        const val ANSWER_MS = 3_500L
        const val UNPAIRED = "This Portal isn’t paired with this phone any more."

        fun parseStatus(json: JSONObject): RoomStatus {
            val state = when (json.optString("state")) {
                "playing" -> RoomState.PLAYING
                "quiet" -> RoomState.QUIET
                "routine" -> RoomState.ROUTINE
                else -> RoomState.IDLE
            }
            val playing = json.optJSONObject("nowPlaying")?.let { text(it, "title") }
            return RoomStatus(state, playing, json.optInt("upNext", 0).coerceAtLeast(0), text(json, "quietUntil"))
        }

        fun parseSend(json: JSONObject, room: String): SendResult = when (json.optString("status")) {
            "playing", "queued" -> SendResult.Played(
                MusicAction.entries.firstOrNull { it.wire == json.optString("action") } ?: MusicAction.NOW,
                json.optBoolean("quietly"), text(json, "interrupted"), text(json, "before"),
                json.optInt("ahead", 0).coerceAtLeast(0), json.optInt("count", -1).takeIf { it >= 0 })
            "quiet" -> SendResult.Quiet(text(json, "quietUntil"))
            "declined" -> SendResult.Declined(text(json, "reason") ?: "$room couldn’t play it. Nothing was played.")
            else -> SendResult.Failed("$room couldn’t play it. Nothing was played.")
        }

        fun encode(r: MusicSendRequest): JSONObject = JSONObject().put("kind", r.kind).put("id", r.id)
            .put("action", r.action.wire).put("shuffle", r.shuffle).put("from", r.from)
            .put("title", r.title).put("artist", r.artist).put("thumb", r.thumb ?: JSONObject.NULL)
            .put("playAnyway", r.playAnyway).put("shareId", r.shareId)

        private fun failure(e: CompanionException, room: String): SendResult = when {
            e.error.status == 404 -> SendResult.Failed("Update Hearthpane on $room to play shared music.")
            e.error.code == "unauthorized" -> SendResult.Failed("Pair this phone with $room again.")
            e.error.code == "mix_not_supported" -> SendResult.Failed("Mixes can't play on a Portal.")
            else -> SendResult.Failed("$room couldn’t play it. Nothing was played.")
        }

        private fun text(json: JSONObject, key: String): String? =
            if (json.isNull(key)) null else json.optString(key).trim().take(200).ifEmpty { null }
    }
}
