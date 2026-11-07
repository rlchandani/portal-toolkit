/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.list

/** A snackbar message; [undoable] shows Undo. [key] makes repeats of the same text distinct. */
data class ListSnack(val text: String, val undoable: Boolean, val key: Long)

data class HouseholdListUiState(
    val loaded: Boolean = false,
    val view: ListView = ListView.EMPTY,
    val status: StatusLine = StatusLine(StatusKind.UNPAIRED, "Getting the list…"),
    val usedBefore: List<String> = emptyList(),
    val pending: Int = 0,
    val syncing: Boolean = false,
    val snack: ListSnack? = null,
    /** Notes the person already acknowledged here (shown once). */
    val dismissedNotes: Set<String> = emptySet(),
)

/** How to take back the last change: cancel it if it never left the phone, otherwise send [inverse]. */
internal data class UndoPlan(val cancel: Set<String>, val inverse: List<PendingAction>)
