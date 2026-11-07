/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.list

enum class StatusKind { OK, PARTIAL, AWAY, OFFLINE, UNPAIRED }

data class StatusLine(val kind: StatusKind, val text: String) {
    val warning: Boolean get() = kind == StatusKind.AWAY || kind == StatusKind.OFFLINE
}

/** The List screen's status line and the "sent" snackbar, from the last sync pass. */
object HouseholdListStatus {
    fun line(reached: List<String>, unreached: List<String>, pending: Int, network: NetworkKind): StatusLine = when {
        reached.isEmpty() && unreached.isEmpty() -> StatusLine(StatusKind.UNPAIRED, "Pair a Portal to share the list")
        reached.isEmpty() && network == NetworkKind.NONE -> StatusLine(StatusKind.OFFLINE, "No connection")
        reached.isEmpty() -> StatusLine(StatusKind.AWAY,
            if (pending > 0) "Away from home. ${changes(pending)} waiting to send." else "Away from home. The list may be out of date.")
        unreached.isEmpty() -> StatusLine(StatusKind.OK, "Up to date with ${portals(reached)}")
        else -> StatusLine(StatusKind.PARTIAL, "Up to date with ${portals(reached)}. ${unreached.joinToString(" and ")} " +
            (if (unreached.size > 1) "are" else "is") + " offline and will catch up.")
    }

    fun sent(count: Int, reached: List<String>): String = "Sent ${changes(count)} to ${portals(reached)}."

    private fun changes(n: Int) = if (n == 1) "1 change" else "$n changes"

    private fun portals(names: List<String>): String = when (names.size) {
        1 -> names.single()
        2 -> "both Portals"
        else -> "all ${names.size} Portals"
    }
}
