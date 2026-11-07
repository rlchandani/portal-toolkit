/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.portalinstaller.data.list.ListItem
import com.portalinstaller.data.list.RowMeta
import com.portalinstaller.data.list.RowMetaKind
import kotlin.math.roundToInt

internal val TickGreen = Color(0xFF5BC69B)
internal val WaitAmber = Color(0xFFF2C46B)

/** Callbacks a row needs; one instance for the whole list. */
internal class RowActions(
    val onToggle: (String) -> Unit,
    val onOpen: (String) -> Unit,
    val onRemove: (String) -> Unit,
    val onMove: (id: String, beforeId: String?) -> Unit,
)

/** The open items card: swipe a row left to remove, long-press and drag to reorder. */
@Composable
internal fun OpenItemsCard(items: List<ListItem>, meta: (ListItem) -> RowMeta, actions: RowActions) {
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableFloatStateOf(1f) }
    ListCard {
        items.forEachIndexed { index, item ->
            val dragging = draggingId == item.id
            val drag = Modifier.pointerInput(item.id, items) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { draggingId = item.id; dragY = 0f },
                    onDragCancel = { draggingId = null; dragY = 0f },
                    onDragEnd = {
                        val target = (index + (dragY / rowHeight).roundToInt()).coerceIn(0, items.lastIndex)
                        val rest = items.filter { it.id != item.id }
                        if (target != index) actions.onMove(item.id, rest.getOrNull(target)?.id)
                        draggingId = null; dragY = 0f
                    },
                    onDrag = { change, amount -> change.consume(); dragY += amount.y },
                )
            }
            Box(Modifier.zIndex(if (dragging) 1f else 0f).graphicsLayer { translationY = if (dragging) dragY else 0f }
                .onSizeChanged { if (it.height > 0) rowHeight = it.height.toFloat() }) {
                SwipeToRemove(item, actions) { ItemRow(item, meta(item), actions, drag, lifted = dragging) }
            }
            if (index < items.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
internal fun DoneItemsCard(items: List<ListItem>, meta: (ListItem) -> RowMeta, actions: RowActions) {
    ListCard {
        items.forEachIndexed { index, item ->
            SwipeToRemove(item, actions) { ItemRow(item, meta(item), actions, Modifier, lifted = false) }
            if (index < items.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
internal fun ListCard(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(26.dp))
        .padding(vertical = 4.dp)) { content() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToRemove(item: ListItem, actions: RowActions, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        if (value == SwipeToDismissBoxValue.EndToStart) actions.onRemove(item.id)
        false // the row leaves when the list updates; Undo can bring it back
    })
    SwipeToDismissBox(state, enableDismissFromStartToEnd = false, backgroundContent = {
        Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.error.copy(alpha = .85f), RoundedCornerShape(18.dp))
            .padding(horizontal = 22.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            ListGlyph("trash", Color.White)
            Spacer(Modifier.width(8.dp))
            Text("Remove", color = Color.White)
        }
    }) { Box(Modifier.background(MaterialTheme.colorScheme.surface)) { content() } }
}

@Composable
private fun ItemRow(item: ListItem, meta: RowMeta, actions: RowActions, drag: Modifier, lifted: Boolean) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
        .background(if (lifted) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
        .padding(start = 14.dp, end = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        TickBox(item, onClick = { actions.onToggle(item.id) })
        Column(drag.weight(1f).clickable { actions.onOpen(item.id) }.padding(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(item.text, style = MaterialTheme.typography.titleMedium,
                color = if (item.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (item.done) TextDecoration.LineThrough else null)
            MetaLine(meta)
        }
    }
}

@Composable
private fun MetaLine(meta: RowMeta) {
    val color = when (meta.kind) {
        RowMetaKind.WAITING -> WaitAmber
        RowMetaKind.NOTE -> WaitAmber
        RowMetaKind.WHO -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (meta.kind == RowMetaKind.WAITING) ListGlyph("clock", color, Modifier.size(16.dp))
        Text(meta.text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
private fun TickBox(item: ListItem, onClick: () -> Unit) {
    val label = (if (item.done) "Untick " else "Tick ") + item.text
    Box(Modifier.size(52.dp).clickable(onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center) {
        val shape = RoundedCornerShape(8.dp)
        Box(Modifier.size(26.dp).then(if (item.done) Modifier.background(TickGreen, shape)
            else Modifier.border(2.dp, MaterialTheme.colorScheme.outline, shape)), contentAlignment = Alignment.Center) {
            if (item.done) ListGlyph("check", Color(0xFF14161B), Modifier.size(18.dp))
        }
    }
}
