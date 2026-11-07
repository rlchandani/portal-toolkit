/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.list

import com.portalinstaller.data.companion.CompanionClient
import com.portalinstaller.data.companion.CompanionException
import com.portalinstaller.data.companion.CompanionSession
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** One paired Portal's /remote/list endpoint. */
interface ListPortal {
    val name: String
    suspend fun get(): JSONObject
    suspend fun post(body: JSONObject): JSONObject
}

/** The real endpoint over the paired companion session. */
class CompanionListPortal(private val client: CompanionClient, private val session: CompanionSession) : ListPortal {
    override val name: String get() = session.device.name
    override suspend fun get(): JSONObject = client.get(session, ROUTE)
    override suspend fun post(body: JSONObject): JSONObject = client.post(session, ROUTE, body)

    companion object { const val ROUTE = "list" }
}

data class SyncOutcome(
    val snapshot: ListSnapshot?,
    /** Queued actions a Portal accepted; safe to drop. */
    val acked: Set<String>,
    /** Queued actions a Portal refused as malformed; dropped so they cannot wedge the queue. */
    val dropped: Set<String>,
    val reached: List<String>,
    val unreached: List<String>,
)

/**
 * One sync pass: sends the whole queue (in chunks) to every paired Portal, or just reads when
 * the queue is empty. Posting to each Portal is safe because actions are idempotent; the Portals
 * also sync with each other, so one acceptance is enough to drop an action from the queue.
 */
object HouseholdListSync {
    suspend fun run(portals: List<ListPortal>, queue: List<PendingAction>, device: String, expectedRevision: String?): SyncOutcome {
        var snapshot: ListSnapshot? = null
        val acked = HashSet<String>(); val dropped = HashSet<String>()
        val reached = mutableListOf<String>(); val unreached = mutableListOf<String>()
        for (portal in portals) {
            try {
                val last = send(portal, queue, device, expectedRevision, acked, dropped) ?: portal.get()
                if (snapshot == null) snapshot = HouseholdListParser.snapshot(last)
                reached += portal.name
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                unreached += portal.name
            }
        }
        return SyncOutcome(snapshot, acked, dropped - acked, reached, unreached)
    }

    private suspend fun send(portal: ListPortal, queue: List<PendingAction>, device: String, expected: String?,
                             acked: MutableSet<String>, dropped: MutableSet<String>): JSONObject? {
        var last: JSONObject? = null
        for (chunk in HouseholdListQueue.chunks(queue)) {
            val body = JSONObject().put("device", device).put("actions", JSONArray(chunk.map(HouseholdListQueue::wire)))
            expected?.let { body.put("expectedRevision", it) }
            try {
                val response = portal.post(body)
                if (response.has("results")) acked += chunk.map { it.aid }
                last = response
            } catch (error: CompanionException) {
                if (error.error.status !in REJECTED) throw error
                dropped += chunk.map { it.aid }
            }
        }
        return last
    }

    private val REJECTED = setOf(400, 413)
}
