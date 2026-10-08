/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.controlcenter

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import org.json.JSONArray
import org.json.JSONObject

/** Each result is measured and composed independently by the screen's lazy list. */
internal fun LazyListScope.activityRows(
    operations: JSONArray,
    working: Boolean,
    onRetry: (String) -> Unit,
    onCancel: (String) -> Unit,
) {
    val entries = operations.objects()
    if (entries.isEmpty()) item {
        EmptyMessage("No recent activity", "Completed actions and errors will appear here.")
    }
    itemsIndexed(entries, key = { index, operation ->
        operation.optString("id").ifBlank { "activity-row-$index" }
    }) { _, operation ->
        ActivityOperationCard(operation, working, onRetry, onCancel)
    }
}

@Composable
internal fun ActivityOperationCard(
    operation: JSONObject,
    working: Boolean,
    onRetry: (String) -> Unit,
    onCancel: (String) -> Unit,
) {
    GroupCard(Modifier.semantics(mergeDescendants = true) {}) {
        val id = operation.optString("id")
        val status = operation.optString("state", operation.optString("status"))
        Text(operation.optString("label", operation.optString("type", "Operation")),
            fontWeight = FontWeight.Medium)
        Text(status.replace('_', ' '), color = MaterialTheme.colorScheme.onSurfaceVariant)
        operation.optString("error").takeIf { it.isNotBlank() }?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        operation.optJSONArray("results").objects().forEach { result ->
            Text(result.optString("result", if (result.optBoolean("ok")) "Completed" else "Failed").replace('_', ' '),
                color = if (result.optBoolean("ok")) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
            if (result.optBoolean("backendMayContinue")) Text("The device may still be finishing this action.",
                style = MaterialTheme.typography.bodySmall)
        }
        if (status in setOf("running", "queued") && operation.optBoolean("cancelSupported")) {
            TextButton(onClick = { onCancel(id) }, enabled = !working) { Text("Cancel") }
        }
        if (operation.optBoolean("backendMayContinue")) Text(
            "The Portal is still confirming the result. Retry will be available when it is safe.",
            style = MaterialTheme.typography.bodySmall)
        if (status in setOf("failed", "cancelled", "interrupted") &&
            operation.optBoolean("retrySupported") && !operation.optBoolean("backendMayContinue")) {
            TextButton(onClick = { onRetry(id) }, enabled = !working) { Text("Retry") }
        }
    }
}
