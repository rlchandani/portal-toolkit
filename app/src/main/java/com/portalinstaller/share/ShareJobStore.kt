/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.share

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

/** Jobs as JSON. Room ids and names only; tokens never leave the companion session store. */
object ShareJobCodec {
    private val ID = Regex("[A-Za-z0-9_-]{1,64}")

    fun encode(jobs: List<ShareJob>): String = JSONObject().put("version", 1).put("jobs", JSONArray(jobs.map(::job))).toString()

    private fun job(j: ShareJob): JSONObject = JSONObject().put("id", j.id).put("kind", j.kind.name).put("from", j.from)
        .put("createdAt", j.createdAt).put("cancelled", j.cancelled).put("notified", j.notified)
        .put("text", j.text ?: JSONObject.NULL)
        .put("link", j.link?.let { JSONObject().put("url", it.url).put("title", it.title ?: JSONObject.NULL) } ?: JSONObject.NULL)
        .put("rooms", JSONArray(j.rooms.map { JSONObject().put("id", it.id).put("name", it.name) }))
        .put("items", JSONArray(j.items.map { JSONObject().put("id", it.id).put("name", it.name).put("bytes", it.bytes) }))
        .put("states", JSONObject().apply { j.states.forEach { (k, s) -> put(k, state(s)) } })

    private fun state(s: ItemState) = JSONObject().put("status", s.status.name).put("sent", s.sentBytes)
        .put("reason", s.reason ?: JSONObject.NULL).put("path", s.portalPath ?: JSONObject.NULL)
        .put("remote", s.remoteState ?: JSONObject.NULL)

    fun decode(text: String): List<ShareJob> {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        if (root.optInt("version") != 1) return emptyList()
        val list = root.optJSONArray("jobs") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i -> list.optJSONObject(i)?.let { runCatching { job(it) }.getOrNull() } }
    }

    private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).takeIf { has(key) }

    private fun job(o: JSONObject): ShareJob {
        val id = o.getString("id"); require(id.matches(ID))
        val rooms = o.getJSONArray("rooms").let { a -> (0 until a.length()).map { a.getJSONObject(it).let { r -> ShareRoom(r.getString("id"), r.getString("name")) } } }
        val items = o.getJSONArray("items").let { a -> (0 until a.length()).map { a.getJSONObject(it).let { r ->
            ShareItem(r.getString("id").also { v -> require(v.matches(ID)) }, r.getString("name"), r.getLong("bytes")) } } }
        val states = o.optJSONObject("states") ?: JSONObject()
        val decoded = states.keys().asSequence().associateWith { key ->
            val s = states.getJSONObject(key)
            ItemState(ItemStatus.valueOf(s.getString("status")), s.optLong("sent"), s.str("reason"), s.str("path"), s.str("remote"))
        }
        val link = o.optJSONObject("link")?.let { ShareLink(it.getString("url"), it.str("title")) }
        return ShareJob(id, ShareKind.valueOf(o.getString("kind")), SenderName.clean(o.getString("from")), o.getLong("createdAt"),
            rooms, items, o.str("text"), link, decoded, o.optBoolean("cancelled"), o.optBoolean("notified"))
    }
}

/** App-private, no-backup storage for queued jobs and the staged copies of their files. */
class ShareJobStore(private val directory: File) {
    private val file get() = File(directory, "share-jobs.json")
    private val files get() = File(directory, "files")

    @Synchronized fun load(): List<ShareJob> = if (file.exists()) ShareJobCodec.decode(file.readText()) else emptyList()

    @Synchronized fun save(jobs: List<ShareJob>) {
        check(directory.isDirectory || directory.mkdirs()) { "Could not create private share storage." }
        val temp = File.createTempFile("share-jobs-", ".tmp", directory)
        try {
            temp.writeText(ShareJobCodec.encode(jobs))
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
        // Staged bytes of jobs that are gone are never needed again.
        val keep = jobs.map { it.id }.toSet()
        files.listFiles()?.filter { it.name !in keep }?.forEach { it.deleteRecursively() }
    }

    fun itemFile(jobId: String, itemId: String): File = File(File(files, jobId), itemId)

    @Synchronized fun forget(jobId: String) { File(files, jobId).deleteRecursively() }
}
