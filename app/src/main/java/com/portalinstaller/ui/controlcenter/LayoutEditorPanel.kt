/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.controlcenter

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import com.portalinstaller.ui.common.ChoiceDropdown
import com.portalinstaller.ui.common.ChoiceOption
import com.portalinstaller.ui.common.InlineSettingRow
import com.portalinstaller.ui.common.PremiumDropdownMenu
import com.portalinstaller.ui.common.SettingStepper

/** All edits copy the complete wire document; fields this editor doesn't own survive a save. */
internal object LayoutPanelDraft {
    val kinds = linkedMapOf(
        "today" to "Today", "weather" to "Weather", "world_clock" to "World clock",
        "calendar" to "Calendar", "timers" to "Timers", "alarm" to "Alarm",
        "quick_controls" to "Quick controls", "photo_memory" to "Photo memory",
        "todo" to "To-do list", "news" to "News", "stocks" to "Stocks",
        "crypto" to "Crypto", "options" to "Options", "ha" to "Home Assistant",
    )

    fun widgetKey(widget: JSONObject): String = if (widget.optString("kind") == "appwidget")
        "widget-tile:widget:${widget.optInt("id")}"
    else "widget-tile:custom:${widget.optString("customId")}"

    fun resizeWidget(layout: JSONObject, index: Int, x: Int, y: Int): JSONObject {
        val copy = JSONObject(layout.toString())
        if (x !in 1..4 || y !in 1..4) return copy
        copy.optJSONArray("widgets")?.optJSONObject(index)
            ?.put("spanX", x)?.put("spanY", y)?.put("explicitSize", true)
        return copy
    }

    fun move(layout: JSONObject, field: String, from: Int, to: Int): JSONObject {
        val copy = JSONObject(layout.toString())
        if (field !in listOf("widgets", "homeOrder", "gridOrder", "gridSlots")) return copy
        val array = copy.optJSONArray(field) ?: return copy
        if (from !in 0 until array.length() || to !in 0 until array.length() || from == to) return copy
        val entries = (0 until array.length()).map { array.get(it) }.toMutableList()
        entries.add(to, entries.removeAt(from))
        copy.put(field, JSONArray(entries))
        if (field == "widgets") {
            // Explicit grid order takes precedence on Portal. Reorder only its widget positions,
            // retaining apps, built-ins, folders and holes, including partially specified orders.
            val keys = entries.filterIsInstance<JSONObject>().map(::widgetKey)
            for (orderField in listOf("gridOrder", "gridSlots")) {
                val order = copy.optJSONArray(orderField) ?: continue
                val present = (0 until order.length()).mapNotNull { order.opt(it) as? String }.toSet()
                val replacement = keys.filter { it in present }.iterator()
                for (i in 0 until order.length()) {
                    if (order.opt(i) in keys && replacement.hasNext()) order.put(i, replacement.next())
                }
            }
        }
        return copy
    }

    fun removeWidget(layout: JSONObject, index: Int): JSONObject {
        val copy = JSONObject(layout.toString())
        val widgets = copy.optJSONArray("widgets") ?: return copy
        val removed = widgets.optJSONObject(index) ?: return copy
        val key = widgetKey(removed)
        widgets.remove(index)
        copy.optJSONArray("gridOrder")?.let { order ->
            copy.put("gridOrder", JSONArray((0 until order.length())
                .map { order.get(it) }.filterNot { it == key }))
        }
        copy.optJSONArray("gridSlots")?.let { slots ->
            for (i in 0 until slots.length()) if (slots.opt(i) == key) slots.put(i, JSONObject.NULL)
        }
        return copy
    }

    fun addWidget(layout: JSONObject, kind: String): JSONObject {
        val copy = JSONObject(layout.toString())
        val widgets = copy.optJSONArray("widgets") ?: return copy
        if (kind !in kinds || widgets.length() >= 64) return copy
        val gridOrder = copy.optJSONArray("gridOrder")
        val slots = copy.optJSONArray("gridSlots")
        if (gridOrder != null && gridOrder.length() >= 512) return copy
        if (slots != null && slots.length() >= 512 &&
            (0 until slots.length()).none { slots.isNull(it) || slots.opt(it) == "" }) return copy
        val widget = JSONObject().put("id", 0).put("package", "").put("class", "")
            .put("kind", kind).put("customId", "$kind:${UUID.randomUUID()}")
            .put("spanX", 2).put("spanY", 2).put("explicitSize", true)
        widgets.put(widget)
        val key = widgetKey(widget)
        // Empty orders mean automatic placement. Do not replace them with a one-item order.
        copy.optJSONArray("gridOrder")?.takeIf { it.length() > 0 }?.put(key)
        copy.optJSONArray("gridSlots")?.takeIf { it.length() > 0 }?.let { slots ->
            val blank = (0 until slots.length()).firstOrNull { slots.isNull(it) || slots.opt(it) == "" }
            if (blank == null) slots.put(key) else slots.put(blank, key)
        }
        return copy
    }
}

@Composable
internal fun LayoutEditorPanel(
    layout: JSONObject,
    working: Boolean,
    onSave: (JSONObject) -> Unit,
    onPreview: () -> Unit,
) {
    val received = layout.toString()
    var draftText by rememberSaveable(received) { mutableStateOf(received) }
    val draft = remember(draftText) { JSONObject(draftText) }
    val changed = draftText != received
    val complete = listOf("homeOrder", "gridOrder", "gridSlots", "widgets")
        .all { draft.optJSONArray(it) != null } && draft.optJSONObject("assignments") != null
    val enabled = !working && complete
    fun edit(value: JSONObject) { draftText = value.toString() }
    fun move(field: String, from: Int, to: Int) {
        // Pointer events may arrive before recomposition. Always move the current draft.
        edit(LayoutPanelDraft.move(JSONObject(draftText), field, from, to))
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GroupCard {
            Text("Home layout", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("Hold a row to drag it, or use Move up and Move down. Size is measured in Portal grid cells.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!complete) Text("Refresh the Portal layout before editing.",
                color = MaterialTheme.colorScheme.error)
            Text(if (changed) "Unsaved changes" else "Layout from Portal",
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Button(onClick = { onSave(JSONObject(draftText)) }, enabled = enabled && changed,
                modifier = Modifier.fillMaxWidth()) { Text(if (working) "Working…" else "Save layout") }
            OutlinedButton(onClick = onPreview, enabled = !working, modifier = Modifier.fillMaxWidth()) {
                Text("Preview on Portal")
            }
            Text(if (changed) "Save first to preview these changes on the Portal."
                else "Opens the saved layout on the Portal's own screen.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (changed) TextButton(onClick = { draftText = received }, enabled = !working) {
                Text("Discard changes")
            }
        }

        SectionTitle("Widgets")
        GroupCard {
            val widgets = draft.optJSONArray("widgets") ?: JSONArray()
            if (widgets.length() == 0) Text("Add a Hearthpane widget to get started.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            LayoutReorderRows(
                count = widgets.length(), resetKey = "widgets", enabled = enabled,
                onMove = { from, to -> move("widgets", from, to) },
            ) { index ->
                val widget = widgets.optJSONObject(index)
                if (widget != null) {
                    val kind = widget.optString("kind")
                    Text(LayoutPanelDraft.kinds[kind] ?: widget.optString("package", "App widget"),
                        fontWeight = FontWeight.Medium)
                    if (kind == "appwidget") Text("Bound app widget ${widget.optInt("id")} · ${widget.optString("class")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LayoutSpanControl("Width", widget.optInt("spanX", 2), enabled, Modifier) {
                        edit(LayoutPanelDraft.resizeWidget(draft, index, it, widget.optInt("spanY", 2)))
                    }
                    LayoutSpanControl("Height", widget.optInt("spanY", 2), enabled, Modifier) {
                        edit(LayoutPanelDraft.resizeWidget(draft, index, widget.optInt("spanX", 2), it))
                    }
                    TextButton(onClick = { edit(LayoutPanelDraft.removeWidget(draft, index)) }, enabled = enabled) {
                        Text("Remove widget", color = MaterialTheme.colorScheme.error)
                    }
                } else Text("Unrecognized widget · preserved", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            var adding by remember { mutableStateOf(false) }
            val orderFull = draft.optJSONArray("gridOrder")?.length()?.let { it >= 512 } == true
            val slots = draft.optJSONArray("gridSlots")
            val slotsFull = slots != null && slots.length() >= 512 &&
                (0 until slots.length()).none { slots.isNull(it) || slots.opt(it) == "" }
            Box {
                OutlinedButton(onClick = { adding = true },
                    enabled = enabled && widgets.length() < 64 && !orderFull && !slotsFull,
                    modifier = Modifier.fillMaxWidth()) { Text("Add Hearthpane widget") }
                PremiumDropdownMenu(expanded = adding, onDismissRequest = { adding = false }) {
                    LayoutPanelDraft.kinds.forEach { (kind, title) ->
                        DropdownMenuItem(text = { Text(title) }, onClick = {
                            adding = false
                            edit(LayoutPanelDraft.addWidget(draft, kind))
                        }, enabled = enabled)
                    }
                }
            }
            if (orderFull || slotsFull) Text("The grid is full. Remove a tile before adding a widget.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Add third-party widgets on the Portal to grant Android binding consent.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        LayoutOrderGroup("Home app order", "homeOrder", draft, enabled, ::move)
        LayoutOrderGroup("All tile order", "gridOrder", draft, enabled, ::move)
        LayoutOrderGroup("Grid positions", "gridSlots", draft, enabled, ::move)
        LayoutAssignments(draft, enabled, ::edit)
    }
}

@Composable
private fun LayoutSpanControl(
    title: String,
    value: Int,
    enabled: Boolean,
    modifier: Modifier,
    onChange: (Int) -> Unit,
) {
    InlineSettingRow(title, modifier = modifier) {
        SettingStepper(value.toString(), { onChange(value - 1) }, { onChange(value + 1) },
            decreaseEnabled = enabled && value > 1, increaseEnabled = enabled && value < 4,
            title = title)
    }
}

@Composable
private fun LayoutOrderGroup(
    title: String,
    field: String,
    draft: JSONObject,
    enabled: Boolean,
    onMove: (String, Int, Int) -> Unit,
) {
    var expanded by rememberSaveable(field) { mutableStateOf(false) }
    val entries = draft.optJSONArray(field) ?: JSONArray()
    GroupCard {
        ActionRow(title, "${entries.length()} ${if (field == "gridSlots") "positions" else "items"}",
            glyph = "layout", onClick = { expanded = !expanded })
        if (expanded) {
            if (entries.length() == 0) Text("Automatic order on Portal.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (field == "gridSlots") Text("Empty positions stay empty. Moving a row moves its grid position.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LayoutReorderRows(entries.length(), field, enabled, { from, to ->
                onMove(field, from, to)
            }) { index ->
                Text(layoutTileTitle(entries.opt(index), draft),
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun layoutTileTitle(value: Any?, layout: JSONObject): String {
    val key = value as? String ?: return "Empty position"
    if (key.isBlank()) return "Empty position"
    return when {
        key.startsWith("widget-tile:") -> layout.optJSONArray("widgets").objects()
            .firstOrNull { LayoutPanelDraft.widgetKey(it) == key }?.let {
                LayoutPanelDraft.kinds[it.optString("kind")] ?: it.optString("package")
            } ?: key
        key.startsWith("builtin:") -> key.removePrefix("builtin:").replaceFirstChar { it.uppercase() }
        key.startsWith("folder:") -> "Folder: ${key.removePrefix("folder:")}"
        key.startsWith("app:") -> key.removePrefix("app:")
        else -> key
    }
}

/** A bounded page avoids composing hundreds of slots inside the host's scrolling column. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LayoutReorderRows(
    count: Int,
    resetKey: String,
    enabled: Boolean,
    onMove: (Int, Int) -> Unit,
    content: @Composable (Int) -> Unit,
) {
    var page by rememberSaveable(resetKey) { mutableStateOf(0) }
    val lastPage = ((count - 1).coerceAtLeast(0)) / 8
    val currentPage = page.coerceAtMost(lastPage)
    val start = currentPage * 8
    val end = (start + 8).coerceAtMost(count)
    val bounds = remember(resetKey) { mutableStateMapOf<Int, Rect>() }
    var rootTop by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf<Int?>(null) }
    var dragY by remember { mutableStateOf(0f) }
    val latestMove by rememberUpdatedState(onMove)
    val latestStart by rememberUpdatedState(start)
    val latestEnd by rememberUpdatedState(end)
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().onGloballyPositioned { rootTop = it.boundsInRoot().top }
            .pointerInput(enabled, resetKey) {
                if (enabled) detectDragGesturesAfterLongPress(
                    onDragStart = { point ->
                        dragY = rootTop + point.y
                        dragging = (latestStart until latestEnd).firstOrNull {
                            bounds[it]?.let { rect -> dragY in rect.top..rect.bottom } == true
                        }
                    },
                    onDragEnd = { dragging = null },
                    onDragCancel = { dragging = null },
                    onDrag = { change, amount ->
                        val from = dragging
                        if (from != null) {
                            change.consume()
                            dragY += amount.y
                            val next = when {
                                amount.y > 0 && from + 1 < latestEnd &&
                                    dragY > (bounds[from + 1]?.center?.y ?: Float.MAX_VALUE) -> from + 1
                                amount.y < 0 && from > latestStart &&
                                    dragY < (bounds[from - 1]?.center?.y ?: -Float.MAX_VALUE) -> from - 1
                                else -> from
                            }
                            if (next != from) { latestMove(from, next); dragging = next }
                        }
                    },
                )
            },
    ) {
        for (index in start until end) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
                    .background(if (dragging == index) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                    .onGloballyPositioned { bounds[index] = it.boundsInRoot() }
                    .semantics {
                        customActions = buildList {
                            if (enabled && index > 0) add(CustomAccessibilityAction("Move up") {
                                latestMove(index, index - 1); true
                            })
                            if (enabled && index < count - 1) add(CustomAccessibilityAction("Move down") {
                                latestMove(index, index + 1); true
                            })
                        }
                    }.padding(8.dp),
            ) {
                content(index)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = { latestMove(index, index - 1) },
                        enabled = enabled && index > 0) { Text("Move up") }
                    TextButton(onClick = { latestMove(index, index + 1) },
                        enabled = enabled && index < count - 1) { Text("Move down") }
                }
            }
            if (index < end - 1) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
    if (lastPage > 0) FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = { page = currentPage - 1 }, enabled = currentPage > 0) { Text("Previous") }
        Text("${start + 1}–$end of $count", style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.align(Alignment.CenterVertically))
        TextButton(onClick = { page = currentPage + 1 }, enabled = currentPage < lastPage) { Text("Next") }
    }
}

@Composable
private fun LayoutAssignments(draft: JSONObject, enabled: Boolean, onEdit: (JSONObject) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val assignments = draft.optJSONObject("assignments") ?: JSONObject()
    val packages = buildSet {
        assignments.keys().forEach { add(it) }
        draft.optJSONArray("homeOrder").strings().forEach { add(it) }
        for (field in listOf("gridOrder", "gridSlots")) draft.optJSONArray(field).strings()
            .filter { it.startsWith("app:") }.forEach { add(it.removePrefix("app:")) }
    }.sorted()
    var selected by rememberSaveable { mutableStateOf("") }
    var folder by rememberSaveable(selected) { mutableStateOf(assignments.optString(selected)) }
    GroupCard {
        ActionRow("Folders", "${assignments.length()} app assignments", glyph = "apps",
            onClick = { expanded = !expanded })
        if (expanded) {
            Text("Choose a home app and set its folder. An empty folder places it outside folders.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ChoiceDropdown(
                value = selected,
                options = packages.map { ChoiceOption(it, it) },
                onSelect = { pkg -> selected = pkg; folder = assignments.optString(pkg) },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled && packages.isNotEmpty(),
                placeholder = "Choose an app",
                accessibilityLabel = "Home app",
                matchAnchorWidth = true,
            )
            if (packages.isEmpty()) Text("No app placements are in this layout yet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (selected in packages) {
                OutlinedTextField(value = folder, onValueChange = { if (it.length <= 48) folder = it },
                    label = { Text("Folder name") }, enabled = enabled, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                TextButton(onClick = {
                    val copy = JSONObject(draft.toString())
                    copy.getJSONObject("assignments").put(selected, folder.trim())
                    onEdit(copy)
                }, enabled = enabled && folder.none { it.code < 32 } &&
                    folder.trim() != assignments.optString(selected)) { Text("Set folder in draft") }
                if (assignments.has(selected)) TextButton(onClick = {
                    val copy = JSONObject(draft.toString())
                    copy.getJSONObject("assignments").remove(selected)
                    onEdit(copy); folder = ""
                }, enabled = enabled) { Text("Use default folder") }
            }
        }
    }
}
