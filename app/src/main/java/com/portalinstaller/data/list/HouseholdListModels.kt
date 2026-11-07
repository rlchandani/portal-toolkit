/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.list

/** One live item on the shared household list, as a Portal reports it (validated). */
data class ListItem(
    val id: String,
    val text: String,
    val done: Boolean,
    val order: Double,
    val addedBy: String,
    val addedAt: Long,
    val updatedAt: Long,
    val doneBy: String? = null,
    val doneAt: Long? = null,
    val renamedBy: String? = null,
    val renamedAt: Long? = null,
    val note: ItemNote? = null,
)

enum class NoteKind { KEPT, EDITED }

/** A once-shown merge note: an item kept after a remove/change race, or renamed in two places. */
data class ItemNote(val kind: NoteKind, val text: String, val alt: String? = null, val altBy: String? = null)

enum class PortalSyncState { OK, PARTIAL, SYNCING, OFFLINE, FAILING, FIRST }

data class PeerStatus(val name: String, val synced: Boolean)

/** The list as one Portal last reported it. */
data class ListSnapshot(
    val revision: String,
    val portal: String,
    val items: List<ListItem>,
    val usedBefore: List<String>,
    val syncState: PortalSyncState = PortalSyncState.OK,
    val peers: List<PeerStatus> = emptyList(),
)

enum class ListOp(val wire: String) {
    ADD("add"), TICK("tick"), UNTICK("untick"), REMOVE("remove"), RESTORE("restore"),
    RENAME("rename"), MOVE("move"), CLEAR_DONE("clear_done");

    companion object {
        fun fromWire(value: String): ListOp? = entries.firstOrNull { it.wire == value }
    }
}

/** A change made on this phone, queued until a Portal accepts it. [aid] makes a resend safe. */
data class PendingAction(
    val aid: String,
    val op: ListOp,
    val at: Long,
    val id: String? = null,
    val text: String? = null,
    val order: Double? = null,
    val ids: List<String> = emptyList(),
)

enum class ActionStatus { APPLIED, DUPLICATE, MISSING, INVALID }

/** What the phone shows: the last Portal state with the queued changes applied on top. */
data class ListView(
    val open: List<ListItem>,
    val done: List<ListItem>,
    /** Ids of items touched by a change still waiting to send. */
    val waiting: Set<String>,
) {
    fun find(id: String): ListItem? = open.firstOrNull { it.id == id } ?: done.firstOrNull { it.id == id }

    companion object { val EMPTY = ListView(emptyList(), emptyList(), emptySet()) }
}

enum class NetworkKind { WIFI, OTHER, NONE }
