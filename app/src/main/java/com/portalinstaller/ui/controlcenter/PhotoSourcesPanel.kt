/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.controlcenter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.portalinstaller.data.companion.CompanionPhotos
import com.portalinstaller.ui.common.InlineSettingRow
import com.portalinstaller.ui.common.SettingValueButton
import org.json.JSONObject

private data class SourceField(
    val key: String,
    val title: String,
    val secret: Boolean = false,
    val url: Boolean = false,
)
private val sourceLabels = linkedMapOf(
    "folder" to "Photos on this Portal",
    "immich" to "Immich",
    "dav" to "WebDAV",
    "smb" to "Network folder",
    "album" to "Remote album",
    "web" to "Web image",
    "default" to "Built-in photos",
)
private fun fieldsFor(source: String): List<SourceField> = when (source) {
    "folder" -> listOf(SourceField("folderPath", "Folder on Portal"))
    "immich" -> listOf(SourceField("immichUrl", "Server address", url = true),
        SourceField("immichKey", "API key", true), SourceField("immichAlbumId", "Album ID (optional)"),
        SourceField("immichAlbumName", "Album name (optional)"))
    "dav" -> listOf(SourceField("davUrl", "Folder address", url = true), SourceField("davUser", "Username"),
        SourceField("davPass", "Password", true))
    "smb" -> listOf(SourceField("smbHost", "Server"), SourceField("smbShare", "Share"),
        SourceField("smbPath", "Folder (optional)"), SourceField("smbUser", "Username"),
        SourceField("smbPass", "Password", true))
    "album" -> listOf(SourceField("albumUrl", "Album address", url = true))
    "web" -> listOf(SourceField("webUrl", "Image address", url = true))
    else -> emptyList()
}

private val secretSourceKeys = sourceLabels.keys.flatMap(::fieldsFor)
    .filter { it.secret }.map { it.key }.toSet()
private val urlSourceKeys = sourceLabels.keys.flatMap(::fieldsFor)
    .filter { it.url }.map { it.key }.toSet()
private fun canSaveSourceDraft(key: String, value: String): Boolean =
    key !in secretSourceKeys && (key !in urlSourceKeys || nonSecretUrlDraft(value) != null)
private val sourceDraftSaver = mapSaver<SnapshotStateMap<String, String>>(
    save = { drafts -> drafts.filter { (key, value) -> canSaveSourceDraft(key, value) } },
    restore = { saved ->
        mutableStateMapOf<String, String>().apply {
            saved.forEach { (key, value) ->
                if (value is String && canSaveSourceDraft(key, value)) put(key, value)
            }
        }
    },
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PhotoSourcesPanel(
    sources: JSONObject,
    working: Boolean,
    onSave: (JSONObject) -> Unit,
    onPreview: () -> Unit,
) {
    var selected by rememberSaveable(sources.toString()) {
        mutableStateOf(sources.optString("source", "folder"))
    }
    val drafts = rememberSaveable(sources.toString(), saver = sourceDraftSaver) { mutableStateMapOf<String, String>().apply {
        fieldsFor("folder").plus(fieldsFor("immich")).plus(fieldsFor("dav"))
            .plus(fieldsFor("smb")).plus(fieldsFor("album")).plus(fieldsFor("web")).forEach { field ->
                put(field.key, sources.optString(field.key,
                    if (field.key == "folderPath") "/sdcard/Pictures/Immortal" else ""))
            }
    } }
    var expanded by remember { mutableStateOf(false) }
    fun configured(key: String): Boolean = sources.optBoolean("${key}Configured") ||
        sources.optBoolean("has${key.replaceFirstChar(Char::titlecase)}")
    fun sameEndpoint(): Boolean = when (selected) {
        "immich" -> drafts["immichUrl"].orEmpty().trim() == sources.optString("immichUrl")
        "smb" -> listOf("smbHost", "smbShare", "smbUser").all { drafts[it].orEmpty().trim() == sources.optString(it) }
        "dav" -> listOf("davUrl", "davUser").all { drafts[it].orEmpty().trim() == sources.optString(it) }
        else -> true
    }
    fun usableSecret(key: String): Boolean =
        drafts[key].orEmpty().isNotBlank() || configured(key) && sameEndpoint()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionTitle("Photo source")
        GroupCard {
            InlineSettingRow(title = "Source", help = "Choose what your Portal displays.") {
                Box {
                    SettingValueButton(
                        value = sourceLabels[selected] ?: selected,
                        onClick = { expanded = true },
                        enabled = !working,
                    )
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        sourceLabels.forEach { (id, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { selected = id; expanded = false })
                        }
                    }
                }
            }
            fieldsFor(selected).forEach { field ->
                OutlinedTextField(
                    value = drafts[field.key].orEmpty(), onValueChange = { drafts[field.key] = it },
                    label = { Text(field.title) }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true, enabled = !working,
                    placeholder = { if (field.secret && configured(field.key) && sameEndpoint()) Text("Keep saved credential") },
                    visualTransformation = if (field.secret) PasswordVisualTransformation() else VisualTransformation.None)
            }
            if (selected == "folder") {
                Text("Set the slideshow's folder here, or use the collection above. Photos stay on your Portal.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = {
                    val body = JSONObject()
                    if (selected == "default") body.put("source", "default")
                    fieldsFor(selected).forEach {
                        val draft = drafts[it.key].orEmpty()
                        if (!it.secret || draft.isNotEmpty()) body.put(it.key, if (it.secret) draft else draft.trim())
                    }
                    onSave(body)
                }, enabled = !working && when (selected) {
                    "folder" -> runCatching { CompanionPhotos.folder(drafts["folderPath"].orEmpty()) }.isSuccess
                    "immich" -> drafts["immichUrl"].orEmpty().isNotBlank() && usableSecret("immichKey")
                    "smb" -> drafts["smbHost"].orEmpty().isNotBlank() && drafts["smbShare"].orEmpty().isNotBlank()
                        && (!configured("smbPass") || usableSecret("smbPass"))
                    "dav" -> drafts["davUrl"].orEmpty().isNotBlank() && (!configured("davPass") || usableSecret("davPass"))
                    "album" -> drafts["albumUrl"].orEmpty().isNotBlank()
                    "web" -> drafts["webUrl"].orEmpty().isNotBlank()
                    else -> true
                }) { Text(if (selected == "folder") "Use this folder" else "Activate source") }
                TextButton(onClick = onPreview, enabled = !working) { Text("Preview on Portal") }
            }
        }
    }
}
