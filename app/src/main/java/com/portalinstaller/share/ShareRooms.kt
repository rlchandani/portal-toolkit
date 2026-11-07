/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.share

/** A paired Portal the phone can share to. [id] is the companion device id; never a token. */
data class ShareRoom(val id: String, val name: String)

/** One row of the room picker: a single room, or every paired room ("Both rooms"). */
data class RoomOption(val key: String, val title: String, val subtitle: String?, val rooms: List<ShareRoom>)

object ShareRooms {
    const val ALL_KEY = "all"

    fun options(rooms: List<ShareRoom>): List<RoomOption> {
        val singles = rooms.map { RoomOption(it.id, it.name, null, listOf(it)) }
        if (rooms.size < 2) return singles
        val title = if (rooms.size == 2) "Both rooms" else "All ${rooms.size} rooms"
        val subtitle = if (rooms.size == 2) "${rooms[0].name} and ${rooms[1].name}" else rooms.joinToString { it.name }
        return singles + RoomOption(ALL_KEY, title, subtitle, rooms)
    }

    /** The last-used choice when it still exists, otherwise the first room. */
    fun defaultKey(options: List<RoomOption>, lastUsed: String?): String? =
        options.firstOrNull { it.key == lastUsed }?.key ?: options.firstOrNull()?.key

    fun destination(rooms: List<ShareRoom>): String = when (rooms.size) {
        0 -> "the Portal"
        1 -> rooms.single().name
        2 -> "both rooms"
        else -> "all ${rooms.size} rooms"
    }

    fun sendLabel(kind: ShareKind, rooms: List<ShareRoom>): String = when (kind) {
        ShareKind.PHOTOS -> "Send to ${destination(rooms).replaceFirstChar { it.uppercaseChar() }}"
        ShareKind.NOTE -> "Send note to ${destination(rooms)}"
        ShareKind.LINK -> "Open on ${destination(rooms)}"
    }
}
