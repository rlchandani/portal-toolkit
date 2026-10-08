/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.controlcenter

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

internal object ScenePanelDraft {
    val keys = linkedMapOf("home" to "Go home", "back" to "Go back", "apps" to "Open apps",
        "screensaver" to "Open photo frame", "wake" to "Wake screen", "sleep" to "Sleep screen")
    val sources = linkedMapOf("default" to "Built-in photos", "local" to "Local folder",
        "immich" to "Immich", "smb" to "SMB", "dav" to "WebDAV", "web" to "Web source",
        "album" to "Shared album")
    val types = linkedMapOf("key" to "Navigation", "launch" to "Launch an app",
        "settings" to "Device setting", "source" to "Photo source", "wait" to "Wait")
    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

    fun newStep(type: String): JSONObject = JSONObject().put("type", type).apply {
        when (type) {
            "key" -> put("action", "home")
            "launch" -> put("packageName", "")
            "settings" -> put("domain", "screensaver").put("values", JSONObject().put("enabled", true))
            "source" -> put("values", JSONObject().put("source", "default"))
            "wait" -> put("ms", 500)
        }
    }

    fun template(name: String): JSONObject {
        val steps = JSONArray()
        when (name) {
            "Evening" -> steps.put(newStep("settings")).put(newStep("key").put("action", "screensaver"))
            "Music" -> steps.put(newStep("key").put("action", "apps"))
            else -> steps.put(newStep("key").put("action", "home"))
        }
        return JSONObject().put("id", UUID.randomUUID().toString()).put("name", name).put("steps", steps)
    }

    /** UI bounds match the Portal policy; the Portal still validates current registry/app support. */
    fun validation(scenes: JSONArray, numericSettings: Set<String> = emptySet()): String? {
        if (scenes.length() > 20) return "Keep at most 20 scenes."
        val ids = mutableSetOf<String>()
        for (i in 0 until scenes.length()) {
            val scene = scenes.optJSONObject(i) ?: return "A scene could not be read."
            val id = scene.optString("id")
            if (!Regex("[A-Za-z0-9_-]{1,64}").matches(id) || !ids.add(id)) return "Scene IDs must be unique."
            val name = scene.opt("name") as? String ?: return "Give each scene a name."
            if (name.isBlank() || name.length > 64 || name.any { it.code < 32 })
                return "Give each scene a name of 1–64 characters."
            val steps = scene.optJSONArray("steps") ?: return "Add an action to ${name}."
            if (steps.length() !in 1..16) return "$name needs 1–16 actions."
            var waits = 0L
            for (j in 0 until steps.length()) {
                val step = steps.optJSONObject(j) ?: return "Action ${j + 1} in $name could not be read."
                val valid = when (step.optString("type")) {
                    "key" -> step.opt("action") in keys
                    "launch" -> (step.opt("packageName") as? String)?.let {
                        it.length <= 200 && packagePattern.matches(it)
                    } == true
                    "settings" -> step.optString("domain").isNotBlank() &&
                        step.optJSONObject("values")?.let { values ->
                            values.length() > 0 && values.keys().asSequence().all { setting ->
                                "${step.optString("domain")}:$setting" !in numericSettings ||
                                    integer(values.opt(setting)) != null
                            }
                        } == true
                    "source" -> step.optJSONObject("values")?.let { values ->
                        val source = values.opt("source")
                        source in sources && values.keys().asSequence().all { it in listOf("source", "folderPath") } &&
                            if (source == "local") (values.opt("folderPath") as? String)?.let {
                                it.length <= 512 && it.startsWith("/sdcard/") &&
                                    it.split('/').none { part -> part == ".." || part == "." } &&
                                    it.none { ch -> ch.code < 32 }
                            } == true else !values.has("folderPath")
                    } == true
                    "wait" -> integer(step.opt("ms"))?.let { waits += it; it in 0L..5000L } == true
                    else -> false
                }
                if (!valid) return "Check action ${j + 1} in $name."
            }
            if (waits > 5000) return "Total waits in $name must be 5 seconds or less."
            if (scene.has("schedule") && !scene.isNull("schedule")) {
                val schedule = scene.optJSONObject("schedule") ?: return "Check the schedule in $name."
                val days = schedule.optJSONArray("days") ?: return "Choose schedule days in $name."
                val dayList = (0 until days.length()).map { integer(days.opt(it)) }
                if (schedule.opt("enabled") !is Boolean ||
                    integer(schedule.opt("hour")) !in 0L..23L ||
                    integer(schedule.opt("minute")) !in 0L..59L ||
                    dayList.size !in 1..7 || dayList.distinct().size != dayList.size ||
                    dayList.any { it !in 1L..7L }) return "Choose a valid time and days in $name."
            }
        }
        return null
    }

    private fun integer(value: Any?): Long? = when (value) {
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScenesPanel(
    scenes: JSONArray,
    working: Boolean,
    onSave: (JSONArray) -> Unit,
    onRun: (String) -> Unit,
) {
    val received = scenes.toString()
    var draftText by rememberSaveable(received) { mutableStateOf(received) }
    val draft = remember(draftText) { JSONArray(draftText) }
    var selectedId by rememberSaveable { mutableStateOf("") }
    val objects = draft.objects()
    val selected = objects.firstOrNull { it.optString("id") == selectedId } ?: objects.firstOrNull()
    val index = (0 until draft.length()).firstOrNull { draft.optJSONObject(it) === selected }
    val changed = received != draftText
    val numericSettings = remember(received) {
        scenes.objects().flatMap { it.optJSONArray("steps").objects() }
            .filter { it.optString("type") == "settings" }.flatMap { step ->
                val values = step.optJSONObject("values") ?: JSONObject()
                values.keys().asSequence().filter { values.opt(it) is Number }
                    .map { "${step.optString("domain")}:$it" }.toList()
            }.toSet()
    }
    val error = ScenePanelDraft.validation(draft, numericSettings)
    fun editScene(edit: (JSONObject) -> Unit) {
        if (index != null) {
            val copy = JSONArray(draftText)
            edit(copy.getJSONObject(index))
            draftText = copy.toString()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GroupCard {
            Text("Scenes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("Run a sequence on this Portal, or let the Portal run it on selected days.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (changed) Text("Unsaved changes", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
            if (error != null) Text(error, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
            Button(onClick = { onSave(JSONArray(draftText)) }, enabled = !working && changed && error == null,
                modifier = Modifier.fillMaxWidth()) { Text(if (working) "Working…" else "Save scenes") }
            if (changed) TextButton(onClick = { draftText = received }, enabled = !working) { Text("Discard changes") }
        }

        SectionTitle("Start with a template")
        GroupCard {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Evening", "Music", "Dashboard").forEach { name ->
                    OutlinedButton(onClick = {
                        val scene = ScenePanelDraft.template(name)
                        draftText = JSONArray(draftText).put(scene).toString()
                        selectedId = scene.getString("id")
                    }, enabled = !working && draft.length() < 20) { Text(name) }
                }
            }
            Text("Evening opens the photo frame. Music opens Apps so you can choose your player. Dashboard opens Home.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected == null) {
            EmptyMessage("No scenes yet", "Choose a template, then edit its name and actions before saving.")
        } else {
            GroupCard {
                SceneChoice("Scene", selected.optString("id"),
                    objects.map { it.optString("id") to it.optString("name") }, !working,
                    onSelect = { selectedId = it })
                OutlinedTextField(value = selected.optString("name"), onValueChange = { name ->
                    if (name.length <= 64) editScene { it.put("name", name) }
                }, label = { Text("Scene name") }, singleLine = true, enabled = !working,
                    modifier = Modifier.fillMaxWidth())
                val saved = scenes.objects().firstOrNull { it.optString("id") == selected.optString("id") }
                val canRun = saved != null && saved.toString() == selected.toString()
                OutlinedButton(onClick = { onRun(selected.getString("id")) },
                    enabled = !working && canRun, modifier = Modifier.fillMaxWidth()) { Text("Run on Portal") }
                if (!canRun) Text("Save this scene before running it.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = {
                    if (index != null) { val copy = JSONArray(draftText); copy.remove(index); draftText = copy.toString() }
                }, enabled = !working) { Text("Remove scene", color = MaterialTheme.colorScheme.error) }
            }

            SectionTitle("Actions")
            GroupCard {
                val steps = selected.optJSONArray("steps") ?: JSONArray()
                val knownPackages = scenes.objects().flatMap { it.optJSONArray("steps").objects() }
                    .filter { it.optString("type") == "launch" }.map { it.optString("packageName") }
                    .filter { it.isNotBlank() }.distinct()
                steps.objects().forEachIndexed { stepIndex, step ->
                    if (stepIndex > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text("Action ${stepIndex + 1}", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    key(selected.optString("id"), stepIndex) {
                        SceneStepEditor(step, !working, knownPackages, numericSettings) { replacement ->
                            editScene { it.getJSONArray("steps").put(stepIndex, replacement) }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { editScene { scene -> scene.put("steps", moveSceneStep(steps, stepIndex, stepIndex - 1)) } },
                            enabled = !working && stepIndex > 0) { Text("Move up") }
                        TextButton(onClick = { editScene { scene -> scene.put("steps", moveSceneStep(steps, stepIndex, stepIndex + 1)) } },
                            enabled = !working && stepIndex < steps.length() - 1) { Text("Move down") }
                        TextButton(onClick = { editScene { it.getJSONArray("steps").remove(stepIndex) } },
                            enabled = !working) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                    }
                }
                if (steps.length() == 0) Text("Add at least one action.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                var adding by remember { mutableStateOf(false) }
                Column {
                    OutlinedButton(onClick = { adding = true }, enabled = !working && steps.length() < 16,
                        modifier = Modifier.fillMaxWidth()) { Text("Add action") }
                    DropdownMenu(expanded = adding, onDismissRequest = { adding = false }) {
                        ScenePanelDraft.types.forEach { (type, title) ->
                            DropdownMenuItem(text = { Text(title) }, enabled = !working, onClick = {
                                adding = false
                                editScene { scene ->
                                    val array = scene.optJSONArray("steps") ?: JSONArray()
                                    scene.put("steps", array.put(ScenePanelDraft.newStep(type)))
                                }
                            })
                        }
                    }
                }
            }
            SectionTitle("Schedule")
            GroupCard {
                SceneScheduleEditor(selected.optJSONObject("schedule"), !working) { schedule ->
                    editScene { it.put("schedule", schedule) }
                }
            }
        }
    }
}

private fun moveSceneStep(steps: JSONArray, from: Int, to: Int): JSONArray {
    val entries = (0 until steps.length()).map { steps.get(it) }.toMutableList()
    if (from in entries.indices && to in entries.indices) entries.add(to, entries.removeAt(from))
    return JSONArray(entries)
}

@Composable
private fun SceneChoice(
    title: String,
    value: String,
    options: List<Pair<String, String>>,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { expanded = true }, enabled = enabled,
            modifier = Modifier.fillMaxWidth()) {
            Text(options.firstOrNull { it.first == value }?.second ?: value.ifBlank { "Choose" })
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, label) ->
                DropdownMenuItem(text = { Text(label) }, enabled = enabled,
                    onClick = { expanded = false; onSelect(id) })
            }
        }
    }
}

@Composable
private fun SceneStepEditor(
    step: JSONObject,
    enabled: Boolean,
    knownPackages: List<String>,
    numericSettings: Set<String>,
    onEdit: (JSONObject) -> Unit,
) {
    val type = step.optString("type")
    SceneChoice("Action type", type, ScenePanelDraft.types.toList(), enabled) {
        if (it != type) onEdit(ScenePanelDraft.newStep(it))
    }
    fun edit(key: String, value: Any) { onEdit(JSONObject(step.toString()).put(key, value)) }
    when (type) {
        "key" -> SceneChoice("Navigation", step.optString("action"), ScenePanelDraft.keys.toList(), enabled) {
            edit("action", it)
        }
        "launch" -> {
            if (knownPackages.isNotEmpty()) SceneChoice("Previously used app", step.optString("packageName"),
                knownPackages.map { it to it }, enabled) { edit("packageName", it) }
            OutlinedTextField(value = step.optString("packageName"), onValueChange = {
                if (it.length <= 200) edit("packageName", it.trim())
            }, label = { Text("Installed app package") }, singleLine = true, enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth())
            Text("Use a package installed on this Portal. Find its name in Apps.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        "settings" -> {
            val values = step.optJSONObject("values") ?: JSONObject()
            Text("Settings: ${step.optString("domain")}", fontWeight = FontWeight.Medium)
            Text("Edit the existing setting values. Add other settings from the Portal's live settings controls.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            values.keys().asSequence().take(32).forEach { key ->
                SceneSettingValue(key, values.opt(key), enabled,
                    "${step.optString("domain")}:$key" in numericSettings) { value ->
                    edit("values", JSONObject(values.toString()).put(key, value))
                }
            }
        }
        "source" -> {
            val values = step.optJSONObject("values") ?: JSONObject()
            val source = values.optString("source")
            SceneChoice("Photo source", source, ScenePanelDraft.sources.toList(), enabled) { next ->
                // A selection is an explicit source change, not a credential editor.
                edit("values", JSONObject().put("source", next).apply {
                    if (next == "local") put("folderPath", values.optString("folderPath", "/sdcard/Pictures"))
                })
            }
            if (source == "local") OutlinedTextField(value = values.optString("folderPath"),
                onValueChange = { if (it.length <= 512) edit("values", JSONObject(values.toString()).put("folderPath", it)) },
                label = { Text("Portal folder path") }, singleLine = true, enabled = enabled,
                modifier = Modifier.fillMaxWidth())
            Text("Remote sources use the connection already configured on the Portal. Configure it in Photos first.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        "wait" -> SceneNumberValue("Wait in milliseconds (0–5000)", step.opt("ms"), enabled) {
            edit("ms", it)
        }
        else -> Text("This action is preserved. Choose a supported action type to replace it.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SceneSettingValue(
    key: String, value: Any?, enabled: Boolean, numeric: Boolean, onEdit: (Any) -> Unit,
) {
    if (numeric) {
        SceneNumberValue(key, value, enabled, onEdit)
        return
    }
    when (value) {
        is Boolean -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (key == "enabled") "Enabled" else key, modifier = Modifier.weight(1f))
            Switch(checked = value, onCheckedChange = onEdit, enabled = enabled)
        }
        is Number -> SceneNumberValue(key, value, enabled, onEdit)
        is String -> OutlinedTextField(value = value, onValueChange = {
            if (it.length <= 512) onEdit(it)
        }, label = { Text(key) }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
        else -> Text("$key: configured on Portal", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SceneNumberValue(title: String, value: Any?, enabled: Boolean, onEdit: (Any) -> Unit) {
    var text by remember(value) { mutableStateOf(value?.toString().orEmpty()) }
    OutlinedTextField(value = text, onValueChange = {
        if (it.length <= 12) {
            text = it
            onEdit(it.toLongOrNull() ?: it)
        }
    }, label = { Text(title) }, enabled = enabled, singleLine = true,
        isError = text.toLongOrNull() == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth())
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SceneScheduleEditor(schedule: JSONObject?, enabled: Boolean, onEdit: (JSONObject) -> Unit) {
    val context = LocalContext.current
    val active = schedule?.optBoolean("enabled") == true
    val hour = schedule?.optInt("hour", 18) ?: 18
    val minute = schedule?.optInt("minute", 0) ?: 0
    val dayArray = schedule?.optJSONArray("days")
    val days = if (dayArray == null) (1..7).toSet()
        else (0 until dayArray.length()).map { dayArray.optInt(it) }.toSet()
    fun edit(change: (JSONObject) -> Unit) {
        val copy = schedule?.let { JSONObject(it.toString()) } ?: JSONObject()
            .put("enabled", false).put("hour", hour).put("minute", minute).put("days", JSONArray(days.sorted()))
        change(copy)
        onEdit(copy)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Run on a schedule", fontWeight = FontWeight.Medium)
            Text("Uses the Portal's local time.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = active, onCheckedChange = { checked -> edit {
            it.put("enabled", checked)
            if (!checked && days.isEmpty()) it.put("days", JSONArray((1..7).toList()))
        } },
            enabled = enabled)
    }
    if (active) {
        OutlinedButton(onClick = {
            TimePickerDialog(context, { _, selectedHour, selectedMinute ->
                edit { it.put("hour", selectedHour).put("minute", selectedMinute) }
            }, hour.coerceIn(0, 23), minute.coerceIn(0, 59), true).show()
        }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(String.format(Locale.ROOT, "%02d:%02d", hour, minute))
        }
        val labels = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            labels.forEachIndexed { index, label ->
                val day = index + 1
                FilterChip(selected = day in days, onClick = {
                    val next = days.toMutableSet()
                    if (day in next) next.remove(day) else next.add(day)
                    edit { it.put("days", JSONArray(next.sorted())) }
                }, label = { Text(label.take(3)) }, enabled = enabled,
                    modifier = Modifier.semantics { contentDescription = label })
            }
        }
        if (days.isEmpty()) Text("Choose at least one day.", color = MaterialTheme.colorScheme.error)
        Text("Save to apply. Runs locally on the Portal; Android may delay the scheduled time.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
