/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.share

import java.util.Locale

data class ShareHeadline(val title: String, val body: String)

/** Every user-facing sentence of the share sheet, from the approved prototype. Pure. */
object ShareCopy {
    fun mb(bytes: Long): String = String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)

    private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

    fun percent(state: ItemState, item: ShareItem): Int =
        if (item.bytes <= 0) 0 else ((state.sentBytes * 100) / item.bytes).toInt().coerceIn(0, 100)

    fun row(job: ShareJob, room: ShareRoom, item: ShareItem): String {
        val s = job.state(room.id, item.id)
        return when (s.status) {
            ItemStatus.WAITING -> if (job.kind == ShareKind.PHOTOS) "Waiting · ${mb(item.bytes)}" else "Waiting"
            ItemStatus.HELD -> s.reason?.takeIf { it.isNotBlank() && job.rooms.size > 1 } ?: "Waiting for a connection"
            ItemStatus.SENDING -> "Sending · ${percent(s, item)}%"
            ItemStatus.CONFIRMING -> "Waiting for ${room.name} to confirm"
            ItemStatus.CONFIRMED -> when (s.remoteState) {
                "opening" -> "Opening on ${room.name}"
                "note" -> "Quiet hours on ${room.name}: saved as a link note on Home instead of opening."
                "dismissed" -> "Dismissed on ${room.name}"
                "cleared" -> "Cleared overnight on ${room.name}"
                "canceled" -> "Someone at ${room.name} tapped Cancel."
                "opened" -> "Opened in ${room.name}’s browser."
                "retracted" -> "Taken back from the phone"
                else -> "On ${room.name}"
            }
            ItemStatus.UNCONFIRMED -> "Sent, not confirmed. ${room.name} hasn’t said it saved this."
            ItemStatus.FAILED -> "Didn’t arrive. ${s.reason ?: "${room.name} didn’t accept it."}"
            ItemStatus.STOPPED -> s.reason ?: "Not sent. You stopped sending."
        }
    }

    private fun what(job: ShareJob) = when (job.kind) {
        ShareKind.PHOTOS -> plural(job.items.size, "photo")
        ShareKind.NOTE -> "Note"
        ShareKind.LINK -> "Link"
    }

    fun resultTitle(job: ShareJob): String {
        val sm = job.summary()
        val dest = ShareRooms.destination(job.rooms)
        if (job.kind == ShareKind.NOTE) return if (sm.allConfirmed) "Note on $dest" else "Note not confirmed on $dest"
        if (job.kind == ShareKind.LINK) return if (sm.allConfirmed) "Link sent to $dest" else "Link not confirmed on $dest"
        if (sm.allConfirmed) return if (job.rooms.size > 1) "${what(job)} on ${if (job.rooms.size == 2) "both" else "all"} Portals"
            else "${what(job)} on $dest"
        if (job.rooms.size > 1) return "${sm.confirmed} of ${sm.total} confirmed across $dest"
        return "${sm.confirmed} of ${what(job)} on $dest"
    }

    fun headline(job: ShareJob): ShareHeadline {
        val sm = job.summary()
        val dest = ShareRooms.destination(job.rooms)
        if (job.cancelled) return ShareHeadline("Send canceled", "Canceled. Nothing more will be sent.")
        if (sm.active) return ShareHeadline("Sending to $dest", "Each one counts only after the Portal confirms it’s saved.")
        if (sm.held) {
            val reason = job.cells().firstOrNull { it.third.status == ItemStatus.HELD }?.third?.reason.orEmpty()
            val these = if (job.kind == ShareKind.PHOTOS && job.items.size > 1) "these" else "it"
            val heldDest = ShareRooms.destination(job.rooms.filter { r -> job.items.any { job.state(r.id, it.id).status == ItemStatus.HELD } })
            val prefix = if (sm.confirmed > 0) "" else "Nothing has been sent yet. "
            return ShareHeadline("Waiting to send",
                "${if (reason.isBlank()) "" else "$reason "}${prefix}Toolkit sends $these as soon as it can reach $heldDest, and tells you when the Portal confirms.")
        }
        if (sm.allConfirmed) return ShareHeadline(resultTitle(job), when (job.kind) {
            ShareKind.PHOTOS -> "${if (job.rooms.size > 1) "Each Portal" else "The Portal"} confirmed ${if (job.items.size == 1) "it" else "every one"}. They show next in the slideshow."
            ShareKind.NOTE -> "It’s on Home now."
            ShareKind.LINK -> "Delivered."
        })
        val named = job.cells().filter { it.third.status in setOf(ItemStatus.FAILED, ItemStatus.UNCONFIRMED, ItemStatus.STOPPED) }
        if (named.size == 1) {
            val (room, item, s) = named.single()
            val label = if (job.kind == ShareKind.PHOTOS) item.name else "The ${job.kind.name.lowercase()}"
            return when (s.status) {
                ItemStatus.UNCONFIRMED -> ShareHeadline("Sent, not confirmed",
                    "$label reached ${room.name}, but it hasn’t confirmed saving it. It may still show up.")
                ItemStatus.STOPPED -> ShareHeadline(resultTitle(job), "You stopped sending. The ones marked Not sent stayed on your phone.")
                else -> ShareHeadline(resultTitle(job), "$label didn’t arrive on ${room.name}. ${s.reason.orEmpty()}".trim())
            }
        }
        if (sm.unconfirmed == named.size) return ShareHeadline("Sent, not confirmed",
            "${named.size} reached the Portal but weren’t confirmed. Check again to ask the Portal.")
        return ShareHeadline(resultTitle(job), "${named.size} didn’t arrive or weren’t confirmed. Each one is named below.")
    }

    fun retryLabel(job: ShareJob): String {
        val n = job.retryCount()
        return if (job.kind == ShareKind.PHOTOS) "Retry ${plural(n, "photo")}" else "Try again"
    }

    /** One line for Toolkit's recent-shares list and the notification shade. */
    fun line(job: ShareJob): String {
        val sm = job.summary()
        return when {
            job.cancelled -> "Canceled. Nothing more will be sent."
            sm.held && !sm.active -> if (sm.confirmed == 0) "Waiting to send. Nothing sent yet." else "Waiting to send the rest · ${sm.confirmed} of ${sm.total} confirmed"
            sm.active -> "Sending · ${sm.confirmed} of ${sm.total} confirmed"
            sm.allConfirmed -> "Confirmed by the Portal"
            else -> "${sm.confirmed} of ${sm.total} confirmed"
        }
    }
}
