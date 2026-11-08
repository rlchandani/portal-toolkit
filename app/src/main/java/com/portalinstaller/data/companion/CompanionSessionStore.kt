/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.companion

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

interface CompanionSessionStore {
    suspend fun load(): List<CompanionSession>
    suspend fun save(sessions: List<CompanionSession>)
    suspend fun loadSelectedIds(): Set<String>? = null
    suspend fun saveSelectedIds(ids: Set<String>) {}
    /** The Portal last opened in Control Center, restored on launch; null when none. */
    suspend fun loadActiveId(): String? = null
    suspend fun saveActiveId(id: String?) {}
}

/** Android production factory always uses private noBackupFilesDir, separate from ADB records. */
class FileCompanionSessionStore(private val directory: File) : CompanionSessionStore {
    private val file get() = File(directory, "companion-sessions.json")

    override suspend fun load(): List<CompanionSession> {
        if (!file.exists()) return emptyList()
        try {
            val root = JSONObject(file.readText())
            require(root.getInt("version") == 1)
            val list = root.getJSONArray("sessions")
            return (0 until list.length()).map { index ->
                val item = list.getJSONObject(index)
                val device = CompanionDevice(item.getString("id"), item.getString("name"), CompanionEndpoint.normalize(item.getString("endpoint")),
                    item.optString("model").ifBlank { null }, item.optInt("apiLevel").takeIf { it > 0 },
                    CompanionPhotos.exactFolder(item.optString("photoFolder", CompanionPhotos.DEFAULT_FOLDER)),
                    item.optBoolean("photoBrowseAll", true))
                val token = item.getString("token")
                require(token.isNotBlank())
                CompanionSession(device, token)
            }.also { require(it.map { session -> session.device.id }.distinct().size == it.size) }
        } catch (_: Exception) {
            throw IOException("Saved pairing data is unreadable. Pairing data has been preserved.")
        }
    }

    override suspend fun save(sessions: List<CompanionSession>) {
        check(directory.isDirectory || directory.mkdirs()) { "Could not create private session storage." }
        val array = JSONArray()
        sessions.forEach { session ->
            array.put(JSONObject().put("id", session.device.id).put("name", session.device.name)
                .put("endpoint", session.device.endpoint).put("model", session.device.model)
                .put("apiLevel", session.device.apiLevel).put("photoFolder", session.device.photoFolder)
                .put("photoBrowseAll", session.device.photoBrowseAll).put("token", session.token))
        }
        val temp = File.createTempFile("companion-sessions-", ".tmp", directory)
        try {
            temp.setReadable(false, false)
            temp.setWritable(false, false)
            check(temp.setReadable(true, true) && temp.setWritable(true, true)) { "Could not protect session storage." }
            FileOutputStream(temp).use { output ->
                output.write(JSONObject().put("version", 1).put("sessions", array).toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }

    /** Same no-backup private directory as credentials, without tokens or endpoint metadata. */
    override suspend fun loadSelectedIds(): Set<String>? {
        val selection = File(directory, "companion-targets.json")
        if (!selection.exists()) return null
        try {
            val root = JSONObject(selection.readText())
            require(root.getInt("version") == 1)
            val ids = root.getJSONArray("ids")
            return (0 until ids.length()).map { ids.getString(it) }.toSet()
        } catch (_: Exception) {
            throw IOException("Saved target selection is unreadable. Choose targets again in the top Portal menu.")
        }
    }

    /** Unreadable or missing means "none": the user simply picks a Portal again. */
    override suspend fun loadActiveId(): String? {
        val active = File(directory, "companion-active.json")
        if (!active.exists()) return null
        return try {
            JSONObject(active.readText()).takeIf { it.optInt("version") == 1 }?.optString("id")?.ifBlank { null }
        } catch (_: Exception) { null }
    }

    override suspend fun saveActiveId(id: String?) {
        val active = File(directory, "companion-active.json")
        if (id == null) { active.delete(); return }
        check(directory.isDirectory || directory.mkdirs())
        val temp = File.createTempFile("companion-active-", ".tmp", directory)
        try {
            temp.writeText(JSONObject().put("version", 1).put("id", id).toString())
            Files.move(temp.toPath(), active.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }

    override suspend fun saveSelectedIds(ids: Set<String>) {
        check(directory.isDirectory || directory.mkdirs())
        val temp = File.createTempFile("companion-targets-", ".tmp", directory)
        try {
            temp.setReadable(false, false)
            temp.setWritable(false, false)
            check(temp.setReadable(true, true) && temp.setWritable(true, true))
            FileOutputStream(temp).use { output ->
                output.write(JSONObject().put("version", 1).put("ids", JSONArray(ids.sorted())).toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(temp.toPath(), File(directory, "companion-targets.json").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }

    companion object {
        fun create(context: Context): FileCompanionSessionStore = FileCompanionSessionStore(File(context.applicationContext.noBackupFilesDir, "companion"))
    }
}
