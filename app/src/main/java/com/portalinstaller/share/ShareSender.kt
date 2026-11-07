/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.share

import com.portalinstaller.data.companion.CompanionClient
import com.portalinstaller.data.companion.CompanionException
import com.portalinstaller.data.companion.CompanionPhotos
import com.portalinstaller.data.companion.CompanionSession
import com.portalinstaller.data.companion.CompanionUpload
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

enum class PhoneNetwork { WIFI, OTHER, NONE }

sealed interface Reach {
    data object Ready : Reach
    /** Not reachable right now; the files wait and send later by themselves. */
    data class Held(val reason: String) : Reach
    /** Reachable but will not accept this share until someone changes something. */
    data class Refused(val reason: String) : Reach
}

/** The seam the runner drives; [ShareSender] is the real one, tests use a fake. */
interface ShareTransport {
    suspend fun reach(roomId: String, kind: ShareKind = ShareKind.PHOTOS): Reach
    suspend fun photo(roomId: String, job: ShareJob, item: ShareItem, progress: (Long) -> Unit): ItemState
    suspend fun message(roomId: String, job: ShareJob): ItemState
    suspend fun check(roomId: String, job: ShareJob, item: ShareItem, state: ItemState): ItemState
}

/**
 * Sends through the existing companion pipeline: photos via [CompanionClient.uploadPhoto] and its
 * acknowledgement check (path and byte count must match), notes and links via `/remote/share`.
 */
class ShareSender(
    private val client: CompanionClient,
    private val sessionFor: suspend (String) -> CompanionSession?,
    private val uploads: (ShareJob, ShareItem) -> CompanionUpload,
    private val network: () -> PhoneNetwork,
) : ShareTransport {

    override suspend fun reach(roomId: String, kind: ShareKind): Reach {
        when (network()) {
            PhoneNetwork.NONE -> return Reach.Held("Your phone is offline.")
            PhoneNetwork.OTHER -> return Reach.Held("Your phone isn’t on home Wi-Fi.")
            PhoneNetwork.WIFI -> Unit
        }
        val session = sessionFor(roomId) ?: return Reach.Refused(UNPAIRED)
        val room = session.device.name
        return try {
            val status = client.get(session, ROUTE)
            if (status.optBoolean("sharing", true)) Reach.Ready else Reach.Refused("Sharing is turned off on $room.")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: CompanionException) {
            when {
                error.error.code == "sharing_off" -> Reach.Refused("Sharing is turned off on $room.")
                error.error.status == 404 && kind == ShareKind.PHOTOS -> Reach.Ready // older Portal: photos still work
                else -> Reach.Refused(ShareFailures.reason(error, room))
            }
        } catch (_: IOException) {
            Reach.Held("$room isn’t responding. It may be off or offline.")
        }
    }

    override suspend fun photo(roomId: String, job: ShareJob, item: ShareItem, progress: (Long) -> Unit): ItemState {
        val session = sessionFor(roomId) ?: return ItemState(ItemStatus.FAILED, reason = UNPAIRED)
        val room = session.device.name
        val folder = CompanionPhotos.exactFolder(session.device.photoFolder)
        var sent = 0L
        suspend fun attempt(name: String): String {
            val base = uploads(job, item)
            val upload = CompanionUpload(name, base.contentType, base.length, base.dispose, base.open)
            try {
                client.uploadPhoto(session, folder, upload) { bytes -> sent = maxOf(sent, bytes); progress(sent) }
            } finally { runCatching { upload.dispose() } }
            return "$folder/$name"
        }
        val path = try {
            try { attempt(item.name) }
            catch (collision: CompanionException) {
                if (collision.error.code != "collision") throw collision
                sent = 0
                attempt(uniqueName(item.name, job.id))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { return ShareFailures.upload(error, room, sent, item.bytes) }
        markFresh(session, job, item, path)
        return ItemState(ItemStatus.CONFIRMED, item.bytes, portalPath = path)
    }

    /** Best effort: tells the Portal who sent the saved photo, for the "New from" turn. */
    private suspend fun markFresh(session: CompanionSession, job: ShareJob, item: ShareItem, path: String) {
        try {
            client.post(session, ROUTE, JSONObject().put("type", "photo").put("id", "${job.id}-${item.id}")
                .put("from", job.from).put("path", path).put("bytes", item.bytes))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* The photo is saved and acknowledged; it simply joins the library unbadged. */ }
    }

    override suspend fun message(roomId: String, job: ShareJob): ItemState {
        val session = sessionFor(roomId) ?: return ItemState(ItemStatus.FAILED, reason = UNPAIRED)
        val body = JSONObject().put("id", job.id).put("from", job.from)
        when (job.kind) {
            ShareKind.NOTE -> body.put("type", "note").put("text", job.text.orEmpty())
            ShareKind.LINK -> body.put("type", "link").put("url", job.link?.url.orEmpty()).put("title", job.link?.title ?: JSONObject.NULL)
            ShareKind.PHOTOS -> error("photos use photo()")
        }
        return try {
            val reply = client.post(session, ROUTE, body)
            if (reply.optString("id") != job.id) ItemState(ItemStatus.UNCONFIRMED)
            else ItemState(ItemStatus.CONFIRMED, remoteState = reply.optString("state").ifBlank { null })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { ShareFailures.message(error, session.device.name) }
    }

    /** "Check again": ask the Portal what it actually holds instead of resending. */
    override suspend fun check(roomId: String, job: ShareJob, item: ShareItem, state: ItemState): ItemState {
        val session = sessionFor(roomId) ?: return state
        val room = session.device.name
        return try {
            if (job.kind == ShareKind.PHOTOS) {
                val folder = CompanionPhotos.exactFolder(session.device.photoFolder)
                val listing = CompanionPhotos.parse(client.photos(session, folder))
                val wanted = state.portalPath ?: "$folder/${item.name}"
                val hit = listing.firstOrNull { it.path == wanted && it.size == item.bytes }
                if (hit != null) ItemState(ItemStatus.CONFIRMED, item.bytes, portalPath = hit.path)
                else ItemState(ItemStatus.FAILED, state.sentBytes, "$room has no record of it.")
            } else {
                val shares = client.get(session, ROUTE).optJSONArray("recent")
                val hit = (0 until (shares?.length() ?: 0)).mapNotNull { shares?.optJSONObject(it) }.firstOrNull { it.optString("id") == job.id }
                if (hit != null) ItemState(ItemStatus.CONFIRMED, remoteState = hit.optString("state").ifBlank { null })
                else ItemState(ItemStatus.FAILED, reason = "$room has no record of it.")
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { state.copy(reason = "Can’t reach $room to check.") }
    }

    /** Asks the Portal for the current state of earlier notes and links (dismissed, canceled…). */
    suspend fun remoteStates(roomId: String): Map<String, String> {
        val session = sessionFor(roomId) ?: return emptyMap()
        val shares = runCatching { client.get(session, ROUTE).optJSONArray("recent") }.getOrNull() ?: return emptyMap()
        return (0 until shares.length()).mapNotNull { shares.optJSONObject(it) }
            .mapNotNull { o -> o.optString("id").takeIf { it.isNotBlank() }?.let { it to o.optString("state") } }.toMap()
    }

    /** Takes a note back from the phone (the Undo on the "Note on Family Area" snackbar). */
    suspend fun retract(roomId: String, jobId: String): Boolean {
        val session = sessionFor(roomId) ?: return false
        return runCatching { client.post(session, ROUTE, JSONObject().put("type", "retract").put("id", jobId)).optBoolean("ok") }
            .getOrDefault(false)
    }

    companion object {
        const val ROUTE = "share"
        const val UNPAIRED = "This Portal isn’t paired with this phone any more."
        fun uniqueName(name: String, jobId: String): String {
            val ext = name.substringAfterLast('.', "")
            val stem = name.substringBeforeLast('.').take(100)
            val tag = jobId.filter { it.isLetterOrDigit() }.takeLast(6)
            return if (ext.isEmpty()) "$stem-$tag" else "$stem-$tag.$ext"
        }
    }
}
