/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.list

enum class RowMetaKind { WAITING, NOTE, WHO }

data class RowMeta(val kind: RowMetaKind, val text: String)

/** The second line under each row and the who/when lines in an item's sheet (mock copy). */
class HouseholdListRowText(private val format: HouseholdListFormat = HouseholdListFormat()) {
    fun meta(item: ListItem, waiting: Boolean, dismissed: Boolean, now: Long): RowMeta = when {
        waiting -> RowMeta(RowMetaKind.WAITING, "Waiting to send")
        item.note != null && !dismissed -> RowMeta(RowMetaKind.NOTE, item.note.text)
        item.done -> RowMeta(RowMetaKind.WHO, "Ticked by ${item.doneBy ?: "someone"}, ${format.short(item.doneAt ?: item.updatedAt, now)}")
        else -> RowMeta(RowMetaKind.WHO, "${item.addedBy}, ${format.short(item.addedAt, now)}")
    }

    fun sheetLines(item: ListItem, waiting: Boolean, now: Long): List<String> = buildList {
        add("Added by ${item.addedBy}, ${format.long(item.addedAt, now)}")
        if (item.renamedBy != null && item.renamedAt != null) add("Renamed by ${item.renamedBy}, ${format.long(item.renamedAt, now)}")
        if (item.done) add("Ticked by ${item.doneBy ?: "someone"}, ${format.long(item.doneAt ?: item.updatedAt, now)}")
        if (waiting) add("Waiting to send to the Portals")
    }
}
