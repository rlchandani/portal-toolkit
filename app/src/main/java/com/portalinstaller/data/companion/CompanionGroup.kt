/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.companion

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Only ids and outcome metadata reach Compose. Sessions and import providers stay in memory. */
class CompanionTargets internal constructor(internal val sessions: List<CompanionSession>) {
    override fun toString() = "CompanionTargets(count=${sessions.size}, credentials=redacted)"
}
enum class GroupWorkStatus { QUEUED, CHECKING, SENDING, SUCCEEDED, UNCONFIRMED, FAILED, CANCELLED }
enum class GroupWorkKind { SETTINGS, PHOTOS }
data class GroupWorkItem(
    val targetId: String,
    val targetName: String,
    val name: String,
    val folder: String? = null,
    val status: GroupWorkStatus = GroupWorkStatus.QUEUED,
    val detail: String? = null,
    val bytesSent: Long = 0,
)
data class GroupOperation(
    val id: Long,
    val title: String,
    val kind: GroupWorkKind,
    val items: List<GroupWorkItem>,
    val cancelRequested: Boolean = false,
) {
    val running: Boolean get() = items.any { it.status in setOf(GroupWorkStatus.QUEUED, GroupWorkStatus.CHECKING, GroupWorkStatus.SENDING) }
    val retryable: Boolean get() = !running && items.any { it.status == GroupWorkStatus.FAILED }
}

/** Guard the same active-only policy at the UI and transport boundary. */
object CompanionGroupSettings {
    fun humanizedTitle(value: String): String = value
        .replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ")
        .replace('_', ' ').replace('-', ' ').trim().ifBlank { "Settings" }
        .replaceFirstChar { it.uppercaseChar() }

    fun activeOnly(domain: String, key: String, control: JSONObject? = null): Boolean {
        val lower = key.lowercase()
        return domain == "mqtt" && control?.optString("type") == "string" || lower in setOf("name", "devicename", "portalname", "roomname", "hostname") ||
            listOf("password", "passwd", "credential", "token", "apikey", "secret", "endpoint", "url", "folderpath",
                "immich", "smb", "dav", "source", "broker", "connection").any { lower.contains(it) } ||
            control?.optBoolean("secret") == true || control?.optString("entry") in setOf("url", "password")
    }
    fun validate(domain: String, patch: JSONObject, schema: JSONObject) {
        val controls = CompanionParser.settings(schema).firstOrNull { it.id == domain }?.controls.orEmpty().associateBy { it.key }
        for (key in patch.keys()) {
            val c = controls[key] ?: throw IllegalArgumentException("Unsupported setting: $domain/$key. Update this Portal and retry.")
            require(!activeOnly(domain, key, c.raw) && !c.readOnly) { "$key is an active-Portal control." }
            val value = patch.get(key)
            val valid = when (c.type) {
                "bool" -> value is Boolean
                "int" -> value is Number && value.toDouble() == value.toInt().toDouble() &&
                    value.toInt() in c.raw.optInt("min", Int.MIN_VALUE)..c.raw.optInt("max", Int.MAX_VALUE)
                "enum" -> value is String && c.raw.optJSONArray("options")?.let { options ->
                    (0 until options.length()).any { options.optJSONObject(it)?.optString("value") == value }
                } == true
                "string" -> value is String
                else -> false
            }
            require(valid) { "Unsupported value for $domain/$key on this Portal." }
        }
    }
}

/** FIFO per Portal across edits/retries, with at most two requests in each fan-out lane. */
internal class CompanionGroupController(
    private val client: CompanionClient,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val isPaired: (CompanionSession) -> Boolean,
    private val publish: (List<GroupOperation>) -> Unit,
    private val progressClock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /** Keep chunk accounting local; copy the public ledger only at a UI-sized interval.
     * Each attempt owns its gate. Closing it freezes the exact terminal byte count and
     * prevents a late transport callback from changing a completed or retried item.
     */
    private class ItemProgress(private val clock: () -> Long, private val publish: (Long) -> Unit) {
        private val lock = Any()
        private var latestBytes = 0L
        private var publishedBytes = 0L
        private var publishedAt = clock()
        private var closed = false

        fun update(bytes: Long) = synchronized(lock) {
            if (closed) return@synchronized
            latestBytes = maxOf(latestBytes, bytes)
            val now = clock()
            if (latestBytes != publishedBytes && now - publishedAt >= 100) {
                publishedAt = now
                publishedBytes = latestBytes
                publish(latestBytes)
            }
        }

        fun finish(confirmedBytes: Long? = null): Long = synchronized(lock) {
            closed = true
            confirmedBytes?.let { latestBytes = it }
            latestBytes
        }
    }

    private class Plan(val targets: CompanionTargets, val domain: String?, val patch: String?,
        val sources: List<PhotoUploadSource>, val onFinished: suspend (CompanionSession) -> Unit,
        val displayTitle: String, val domainTitle: String?) {
        val remaining = targets.sessions.associate { it.device.id to (patch ?: "{}") }.toMutableMap()
        override fun toString() = "GroupPlan(credentials=redacted, patch=redacted)"
    }
    private val lock = Any()
    private var sequence = 0L
    private val plans = linkedMapOf<Long, Plan>()
    private val operations = linkedMapOf<Long, GroupOperation>()
    private val tails = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val latestEdits = mutableMapOf<Triple<String, String, String>, Long>()
    private val settingsSlots = Semaphore(2)
    private val uploadSlots = Semaphore(2)

    fun settings(targets: CompanionTargets, domain: String, values: JSONObject,
        title: String = CompanionGroupSettings.humanizedTitle(domain),
        domainTitle: String = CompanionGroupSettings.humanizedTitle(domain),
        onFinished: suspend (CompanionSession) -> Unit): Job {
        require(values.length() > 0) { "Choose a setting to change." }
        require(values.keys().asSequence().none { CompanionGroupSettings.activeOnly(domain, it) }) { "Use the active Portal for this control." }
        return start(Plan(targets, domain, JSONObject(values.toString()).toString(), emptyList(), onFinished, title, domainTitle))
    }
    fun photos(targets: CompanionTargets, sources: List<PhotoUploadSource>, onFinished: suspend (CompanionSession) -> Unit): Job =
        start(Plan(targets, null, null, sources.toList(), onFinished, "Send ${sources.size} photo(s)", null))

    private fun start(plan: Plan): Job = synchronized(lock) {
        require(plan.targets.sessions.isNotEmpty()) { "Select at least one Portal under Send changes to in the top menu." }
        require(plan.domain != null || plan.sources.isNotEmpty()) { "Select photos to send." }
        // Keep failures until explicitly dismissed; never lose the only retry ledger.
        require(operations.size < 32) { "Dismiss a finished group operation before starting more." }
        val id = ++sequence
        val items = plan.targets.sessions.flatMap { session ->
            if (plan.domain != null) listOf(GroupWorkItem(session.device.id, session.device.name,
                plan.domainTitle ?: CompanionGroupSettings.humanizedTitle(plan.domain)))
            else plan.sources.map { GroupWorkItem(session.device.id, session.device.name, it.name,
                CompanionPhotos.exactFolder(session.device.photoFolder)) }
        }
        plans[id] = plan
        if (plan.domain != null) for (session in plan.targets.sessions) for (key in JSONObject(plan.patch!!).keys()) {
            latestEdits[Triple(session.device.id, plan.domain, key)] = id
        }
        operations[id] = GroupOperation(id, plan.displayTitle,
            if (plan.domain != null) GroupWorkKind.SETTINGS else GroupWorkKind.PHOTOS, items)
        emit()
        schedule(id, items.indices.toList())
    }
    fun retry(id: Long): Job = synchronized(lock) {
        val operation = operations[id] ?: return CompletableDeferred(Unit)
        if (!operation.retryable) return CompletableDeferred(Unit)
        retriedIds.add(id)
        val failed = operation.items.indices.filter { operation.items[it].status == GroupWorkStatus.FAILED }
        operations[id] = operation.copy(cancelRequested = false, items = operation.items.mapIndexed { index, item ->
            if (index in failed) item.copy(status = GroupWorkStatus.QUEUED, detail = null, bytesSent = 0) else item
        })
        emit()
        schedule(id, failed)
    }
    fun cancel(id: Long) = synchronized(lock) {
        operations[id]?.let { operation ->
            operations[id] = operation.copy(cancelRequested = true, items = operation.items.map { item ->
                if (item.status == GroupWorkStatus.QUEUED) item.copy(status = GroupWorkStatus.CANCELLED, detail = "Cancelled before sending.") else item
            })
            emit()
        }
    }
    fun dismiss(id: Long) = synchronized(lock) {
        if (operations[id]?.running == false) { operations.remove(id); plans.remove(id); retriedIds.remove(id); emit() }
    }
    private fun emit() { publish(operations.values.toList()) }
    private fun change(id: Long, index: Int, update: (GroupWorkItem) -> GroupWorkItem) = synchronized(lock) {
        operations[id]?.let { op ->
            operations[id] = op.copy(items = op.items.mapIndexed { i, item -> if (i == index) update(item) else item })
            emit()
        }
    }
    private fun cancelled(id: Long): Boolean = synchronized(lock) { operations[id]?.cancelRequested != false }
    private fun schedule(id: Long, indices: List<Int>): Job {
        val plan = plans.getValue(id)
        val jobs = plan.targets.sessions.map { session ->
            val chosen = indices.filter { operations.getValue(id).items[it].targetId == session.device.id }
            if (chosen.isEmpty()) return@map CompletableDeferred(Unit)
            val key = session.device.id
            val previous = tails[key]
            val done = CompletableDeferred<Unit>()
            tails[key] = done
            scope.launch {
                try {
                    previous?.await()
                    for (index in chosen) {
                        if (cancelled(id)) { change(id, index) { it.copy(status = GroupWorkStatus.CANCELLED, detail = "Cancelled before sending.") }; continue }
                        val slots = if (plan.domain != null) settingsSlots else uploadSlots
                        slots.withPermit {
                            if (cancelled(id)) { change(id, index) { it.copy(status = GroupWorkStatus.CANCELLED) }; return@withPermit }
                            execute(id, index, session, plan)
                        }
                    }
                    // Refresh only the captured active read ticket, never another selection.
                    val slots = if (plan.domain != null) settingsSlots else uploadSlots
                    slots.withPermit {
                        try { plan.onFinished(session) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* outcome is retained; reads can be refreshed */ }
                    }
                } finally {
                    done.complete(Unit)
                    synchronized(lock) { if (tails[key] === done) tails.remove(key) }
                }
            }
        }
        return scope.launch { jobs.joinAll() }
    }
    private suspend fun execute(id: Long, index: Int, session: CompanionSession, plan: Plan) {
        var posted = false
        var uploadProgress: ItemProgress? = null
        var confirmedUploadBytes: Long? = null
        var cleanupFailed = false
        try {
            change(id, index) { it.copy(status = GroupWorkStatus.CHECKING) }
            require(isPaired(session)) { "Pairing changed or was removed. Pair this Portal again." }
            val identity = try { withContext(io) { client.get(session, "companion") } }
                catch (error: CompanionException) { if (error.error.status == 404) null else throw error }
            val serverId = identity?.optString("deviceId").orEmpty()
            require(serverId.isBlank() || session.device.id.startsWith("legacy:") || serverId == session.device.id) {
                "This address belongs to a different Portal. Pair it again."
            }
            if (cancelled(id)) { change(id, index) { it.copy(status = GroupWorkStatus.CANCELLED, detail = "Cancelled before sending.") }; return }
            if (plan.domain != null) {
                val patch = JSONObject(synchronized(lock) { plan.remaining.getValue(session.device.id) })
                val superseded = synchronized(lock) { patch.keys().asSequence().any { key ->
                    (latestEdits[Triple(session.device.id, plan.domain, key)] ?: id) > id
                } }
                // Only retries of an old edit are stale; queued original edits retain their FIFO order.
                if (superseded && synchronized(lock) { retriedIds.contains(id) })
                    throw IllegalArgumentException("A newer edit replaces this setting. Apply the desired value again.")
                val schema = withContext(io) { client.settingsForValidation(session, plan.domain) }
                CompanionGroupSettings.validate(plan.domain, patch, schema)
                if (cancelled(id)) { change(id, index) { it.copy(status = GroupWorkStatus.CANCELLED, detail = "Cancelled before sending.") }; return }
                require(isPaired(session)) { "Pairing changed or was removed. Pair this Portal again." }
                change(id, index) { it.copy(status = GroupWorkStatus.SENDING) }
                posted = true
                val response = withContext(io) { client.post(session, "settings", JSONObject().put("domain", plan.domain).put("values", patch)) }
                val applied = response.optJSONArray("applied")
                val missing = patch.keys().asSequence().filter { key -> applied == null || (0 until applied.length()).none { applied.optString(it) == key } }.toList()
                val remaining = JSONObject().apply { missing.forEach { key -> put(key, patch.get(key)) } }
                synchronized(lock) { plan.remaining[session.device.id] = remaining.toString() }
                require(missing.isEmpty()) { "Portal rejected: ${missing.joinToString()}. Some keys may have applied; refresh before retrying." }
            } else {
                val sourceIndex = index % plan.sources.size
                var upload: CompanionUpload? = null
                try {
                    upload = withContext(io) { plan.sources[sourceIndex].prepare() }
                    if (cancelled(id)) { change(id, index) { it.copy(status = GroupWorkStatus.CANCELLED, detail = "Cancelled before sending.") }; return }
                    require(isPaired(session)) { "Pairing changed or was removed. Pair this Portal again." }
                    change(id, index) { it.copy(status = GroupWorkStatus.SENDING) }
                    posted = true
                    val folder = CompanionPhotos.exactFolder(session.device.photoFolder)
                    val itemProgress = ItemProgress(progressClock) { bytes ->
                        change(id, index) { it.copy(bytesSent = bytes) }
                    }
                    uploadProgress = itemProgress
                    withContext(io) { client.uploadPhoto(session, folder, upload, itemProgress::update) }
                    confirmedUploadBytes = upload.length
                } finally {
                    withContext(io + NonCancellable) {
                        try { upload?.dispose?.invoke() }
                        catch (_: Exception) { cleanupFailed = true }
                    }
                }
            }
            val finalBytes = uploadProgress?.finish(confirmedUploadBytes)
            change(id, index) { it.copy(status = GroupWorkStatus.SUCCEEDED,
                detail = "Confirmed by Portal." + if (cleanupFailed) " Local import cleanup needs attention." else "",
                bytesSent = finalBytes ?: it.bytesSent) }
        } catch (cancelled: CancellationException) {
            val finalBytes = uploadProgress?.finish(confirmedUploadBytes)
            change(id, index) { it.copy(status = GroupWorkStatus.CANCELLED, bytesSent = finalBytes ?: it.bytesSent,
                detail = if (posted) "Interrupted after sending began. Already posted work is not undone; refresh this Portal." else "Cancelled before sending.") }
            throw cancelled
        } catch (error: Exception) {
            val finalBytes = uploadProgress?.finish(confirmedUploadBytes)
            val unconfirmed = error is CompanionException &&
                (plan.domain == null && error.error.code == "upload_unconfirmed" ||
                    plan.domain != null && error.error.code == "settings_unconfirmed")
            val detail = when (error) {
                is CompanionException -> when {
                    unconfirmed -> error.error.message
                    error.error.status == 401 || error.error.status == 403 -> "Pairing or permission rejected. Reconnect this Portal."
                    error.error.status == 404 -> "Unsupported on this Portal. Update Immortal and retry."
                    else -> "Portal rejected the request (${error.error.code})."
                }
                is IllegalArgumentException -> error.message ?: "Unsupported request."
                else -> if (posted && plan.domain == null) "Connection lost. Upload completion is unconfirmed; refresh before retrying."
                    else "Offline or unreachable. Check this Portal and retry."
            }
            change(id, index) { it.copy(status = if (unconfirmed) GroupWorkStatus.UNCONFIRMED else GroupWorkStatus.FAILED,
                detail = detail + if (cleanupFailed) " Local import cleanup needs attention." else "",
                bytesSent = finalBytes ?: it.bytesSent) }
        }
    }
    private val retriedIds = mutableSetOf<Long>()
}
