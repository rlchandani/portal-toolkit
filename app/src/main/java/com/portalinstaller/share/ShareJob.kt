/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.share

enum class ShareKind { PHOTOS, NOTE, LINK }

/**
 * Per (room, file) delivery state. Only [CONFIRMED] means "on the Portal": it is set solely from
 * the Portal's acknowledgement (or a later ledger lookup that finds the file), never from bytes sent.
 */
enum class ItemStatus { WAITING, HELD, SENDING, CONFIRMING, CONFIRMED, UNCONFIRMED, FAILED, STOPPED }

data class ItemState(
    val status: ItemStatus = ItemStatus.WAITING,
    val sentBytes: Long = 0,
    val reason: String? = null,
    /** Where the Portal says it saved the file; used by "Check again". */
    val portalPath: String? = null,
    /** The Portal's own word for a note/link: shown, opening, note, dismissed, cleared, canceled. */
    val remoteState: String? = null,
)

data class ShareItem(val id: String, val name: String, val bytes: Long)
data class ShareLink(val url: String, val title: String?)

data class ShareSummary(
    val total: Int, val confirmed: Int, val failed: Int, val unconfirmed: Int, val stopped: Int,
    val active: Boolean, val held: Boolean,
) {
    val done: Boolean get() = !active && !held
    val allConfirmed: Boolean get() = total > 0 && confirmed == total
}

data class ShareJob(
    val id: String,
    val kind: ShareKind,
    val from: String,
    val createdAt: Long,
    val rooms: List<ShareRoom>,
    val items: List<ShareItem>,
    val text: String? = null,
    val link: ShareLink? = null,
    val states: Map<String, ItemState> = emptyMap(),
    val cancelled: Boolean = false,
    val notified: Boolean = false,
) {
    fun state(roomId: String, itemId: String): ItemState = states[key(roomId, itemId)] ?: ItemState()

    fun with(roomId: String, itemId: String, state: ItemState): ShareJob =
        copy(states = states + (key(roomId, itemId) to state))

    private fun mapStates(change: (ShareRoom, ShareItem, ItemState) -> ItemState): ShareJob =
        copy(states = rooms.flatMap { room -> items.map { item ->
            key(room.id, item.id) to change(room, item, state(room.id, item.id))
        } }.toMap())

    fun cells(): List<Triple<ShareRoom, ShareItem, ItemState>> =
        rooms.flatMap { room -> items.map { item -> Triple(room, item, state(room.id, item.id)) } }

    fun summary(): ShareSummary {
        val all = cells().map { it.third.status }
        return ShareSummary(
            total = all.size,
            confirmed = all.count { it == ItemStatus.CONFIRMED },
            failed = all.count { it == ItemStatus.FAILED },
            unconfirmed = all.count { it == ItemStatus.UNCONFIRMED },
            stopped = all.count { it == ItemStatus.STOPPED },
            active = all.any { it in ACTIVE },
            held = all.any { it == ItemStatus.HELD },
        )
    }

    fun roomSummary(roomId: String): Pair<Int, Int> {
        val mine = items.map { state(roomId, it.id).status }
        return mine.count { it == ItemStatus.CONFIRMED } to mine.size
    }

    /** Files "Retry" would resend: only those that did not arrive. Confirmed files are never resent. */
    fun retryCount(): Int = cells().count { it.third.status in RETRYABLE }

    fun retryFailed(): ShareJob = mapStates { _, _, s ->
        if (s.status in RETRYABLE) ItemState() else s
    }.copy(cancelled = false, notified = false)

    fun stop(): ShareJob = mapStates { _, _, s ->
        if (s.status == ItemStatus.WAITING || s.status == ItemStatus.HELD)
            ItemState(ItemStatus.STOPPED, reason = "Not sent. You stopped sending.") else s
    }

    fun cancelWaiting(): ShareJob = stop().copy(cancelled = true, notified = true)

    fun hold(roomId: String, reason: String): ShareJob = mapStates { room, _, s ->
        if (room.id == roomId && s.status == ItemStatus.WAITING) s.copy(status = ItemStatus.HELD, reason = reason) else s
    }

    fun release(roomId: String): ShareJob = mapStates { room, _, s ->
        if (room.id == roomId && s.status == ItemStatus.HELD) ItemState() else s
    }

    /** After the process died mid-transfer, nobody knows whether the Portal saved it. Say so. */
    fun recoverAfterRestart(): ShareJob = mapStates { room, _, s ->
        if (s.status == ItemStatus.SENDING || s.status == ItemStatus.CONFIRMING)
            s.copy(status = ItemStatus.UNCONFIRMED, reason = "Toolkit stopped while sending. Check again to see if ${room.name} saved it.")
        else s
    }

    fun pending(roomId: String): List<ShareItem> = items.filter { state(roomId, it.id).status == ItemStatus.WAITING }
    fun hasHeld(): Boolean = cells().any { it.third.status == ItemStatus.HELD }
    fun hasUnconfirmed(): Boolean = cells().any { it.third.status == ItemStatus.UNCONFIRMED }

    companion object {
        val ACTIVE = setOf(ItemStatus.WAITING, ItemStatus.SENDING, ItemStatus.CONFIRMING)
        val RETRYABLE = setOf(ItemStatus.FAILED, ItemStatus.STOPPED)
        fun key(roomId: String, itemId: String) = "$roomId|$itemId"
    }
}
