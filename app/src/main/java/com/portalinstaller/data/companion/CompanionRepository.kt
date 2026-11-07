/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.companion

import android.content.Context
import android.net.Uri
import java.util.UUID
import java.util.ArrayDeque
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Immutable tickets are captured before queueing any input, including ViewModel calls. */
class CompanionRepository(
    private val client: CompanionClient,
    private val store: CompanionSessionStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val discovery: CompanionDiscovery? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val preloadManagement: Boolean = true,
    /** Household key source; null disables provisioning (tests, previews). */
    private val household: HouseholdKeySource? = null,
) {
    private val mutableState = MutableStateFlow(ControlCenterState())
    val state: StateFlow<ControlCenterState> = mutableState.asStateFlow()
    private val lock = Any()
    private val persistenceMutex = Mutex()
    private val writeMutex = Mutex()
    private var sessions = emptyList<CompanionSession>()
    private var current: CompanionSession? = null
    private var explicitTargetSelection = false
    private var requestedTargetIds: Set<String>? = null
    private var selectionSaves = 0
    private val groups = CompanionGroupController(client, scope, io,
        isPaired = { captured -> synchronized(lock) { sessions.any { it.device.id == captured.device.id &&
            it.device.endpoint == captured.device.endpoint && it.token == captured.token } } },
        publish = { operations -> mutableState.update { it.copy(groupOperations = operations) } })
    private var generation = 0L
    private var activeCalls = 0
    private var loadFailure: Throwable? = null
    private val cache = mutableMapOf<String, ControlCenterState>()
    private var discoveryGeneration = 0L
    private var photoUploadJob: Job? = null
    private var photoRequestRevision = 0L
    private var cursorJob: Job? = null
    private val inputQueue = ArrayDeque<InputWork>()
    private val imageSlots = Semaphore(4)
    private data class ImageKey(val deviceId: String, val endpoint: String, val credentials: String, val kind: String, val target: String, val metadata: String)
    private data class CachedImage(val bytes: ByteArray, val savedAt: Long)
    private val imageCache = LinkedHashMap<ImageKey, CachedImage>(64, 0.75f, true)
    private var imageCacheBytes = 0L

    val ready: Job = scope.launch {
        try {
            val saved = withContext(io) { store.load() }
            synchronized(lock) { sessions = saved }
            provisionHousehold(saved)
            mutableState.update { it.copy(devices = saved.map { session -> session.device }) }
            try {
                val selectedIds = withContext(io) { store.loadSelectedIds() }
                explicitTargetSelection = requestedTargetIds != null || selectedIds != null
                mutableState.update { it.copy(selectedTargetIds = (requestedTargetIds ?: selectedIds ?: saved.map { session -> session.device.id }.toSet())
                    .intersect(saved.map { session -> session.device.id }.toSet())) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                explicitTargetSelection = true
                mutableState.update { it.copy(selectedTargetIds = emptySet(), error = CompanionError("selection_unreadable",
                    "Saved target selection is unreadable. Choose targets again in the top Portal menu.")) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            loadFailure = error
            mutableState.update { it.copy(error = errorOf(error)) }
        }
    }

    private data class Ticket(val session: CompanionSession, val generation: Long)
    private class InputWork(
        val ticket: Ticket, val route: String, val body: JSONObject? = null,
        var dx: Double = 0.0, var dy: Double = 0.0, val completion: CompletableDeferred<Unit>? = null,
    )
    private fun owns(ticket: Ticket): Boolean = synchronized(lock) {
        generation == ticket.generation && current?.token == ticket.session.token &&
            current?.device?.endpoint == ticket.session.device.endpoint
    }
    private fun ticket(): Ticket? = synchronized(lock) { current?.let { Ticket(it, generation) } }
    private fun update(ticket: Ticket, change: (ControlCenterState) -> ControlCenterState) {
        synchronized(lock) {
            if (owns(ticket)) mutableState.update(change)
        }
    }
    private fun rememberState() {
        mutableState.value.activeDeviceId?.let { cache[it] = mutableState.value.copy(busy = false, error = null, lastResult = null,
            photoTransfer = mutableState.value.photoTransfer?.copy(running = false)) }
    }
    private fun beginSelection(): Long = synchronized(lock) {
        rememberState()
        generation++
        photoRequestRevision++
        inputQueue.forEach { it.completion?.cancel() }
        inputQueue.clear()
        cursorJob?.cancel()
        cursorJob = null
        activeCalls = 0
        current = null
        generation
    }

    fun pair(endpoint: String, pin: String, name: String? = null): Job {
        val selectedGeneration = beginSelection()
        mutableState.update { ControlCenterState(devices = it.devices, discovered = it.discovered, discovering = it.discovering,
            selectedTargetIds = it.selectedTargetIds, groupOperations = it.groupOperations, targetSelectionSaving = it.targetSelectionSaving,
            sessionGeneration = selectedGeneration, connection = CompanionConnection.CONNECTING, busy = true) }
        return scope.launch {
            ready.join()
            try {
                loadFailure?.let { throw it }
                val normalized = CompanionEndpoint.normalize(endpoint)
                val paired = withContext(io) { client.pair(normalized, pin) }
                if (synchronized(lock) { generation != selectedGeneration }) return@launch
                val previous = synchronized(lock) { sessions.firstOrNull { it.device.endpoint == normalized } }
                val provisional = CompanionDevice(previous?.device?.id ?: "legacy:${UUID.randomUUID()}", name?.takeIf { it.isNotBlank() } ?: paired.optString("name", "Portal"), normalized,
                    photoFolder = previous?.device?.photoFolder ?: CompanionPhotos.DEFAULT_FOLDER,
                    photoBrowseAll = previous?.device?.photoBrowseAll ?: true)
                var session = CompanionSession(provisional, paired.getString("token"))
                val info = withContext(io) { optionalGet(session, "companion") }
                if (info != null) session = CompanionSession(deviceFromInfo(session.device, info, allowIdentityChange = true), session.token)
                if (synchronized(lock) { generation != selectedGeneration }) return@launch
                persistenceMutex.withLock {
                    val old = synchronized(lock) { sessions }
                    val candidate = old.filterNot { it.device.id == session.device.id || it.device.endpoint == normalized } + session
                    if (synchronized(lock) { generation != selectedGeneration }) return@withLock
                    withContext(io) { store.save(candidate) }
                    // A slow persistence seam may finish after disconnect. Preserve the previous roster.
                    if (synchronized(lock) { generation != selectedGeneration }) {
                        withContext(io) { store.save(old) }
                        return@withLock
                    }
                    synchronized(lock) { sessions = candidate; current = session }
                    provisionHousehold(listOf(session))
                    mutableState.update { it.copy(devices = candidate.map { saved -> saved.device }, activeDeviceId = session.device.id,
                        photoFolder = session.device.photoFolder, photoBrowseAll = session.device.photoBrowseAll, busy = false,
                        selectedTargetIds = if (explicitTargetSelection) it.selectedTargetIds else candidate.map { saved -> saved.device.id }.toSet()) }
                }
                ticket()?.takeIf { it.generation == selectedGeneration }?.let { refreshTicket(it) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                synchronized(lock) {
                    if (generation == selectedGeneration) mutableState.update { it.copy(busy = false, connection = failureConnection(error, it.connection), error = errorOf(error)) }
                }
            }
        }
    }

    fun pairFromCode(code: String, pin: String? = null): Job {
        return try {
            val parsed = PairingCode.parse(code)
            pair(parsed.endpoint, pin ?: parsed.pin ?: "", parsed.name)
        } catch (error: Exception) { failedJob(error) }
    }

    fun selectDevice(id: String): Job {
        val selectedGeneration = beginSelection()
        mutableState.update { old ->
            (cache[id] ?: ControlCenterState()).copy(devices = old.devices, discovered = old.discovered, discovering = old.discovering,
                selectedTargetIds = old.selectedTargetIds, groupOperations = old.groupOperations, targetSelectionSaving = old.targetSelectionSaving,
                activeDeviceId = id, sessionGeneration = selectedGeneration, connection = CompanionConnection.CONNECTING, busy = true, error = null, lastResult = null)
        }
        return scope.launch {
            ready.join()
            val session = synchronized(lock) {
                if (generation != selectedGeneration) return@launch
                sessions.firstOrNull { it.device.id == id }?.also { current = it }
            }
            if (session == null) {
                synchronized(lock) {
                    if (generation == selectedGeneration) mutableState.update { it.copy(activeDeviceId = null, busy = false, connection = CompanionConnection.UNPAIRED,
                        error = CompanionError("unknown_device", "Select a saved Portal or pair a new one.")) }
                }
            } else {
                mutableState.update { it.copy(photoFolder = session.device.photoFolder, photoBrowseAll = session.device.photoBrowseAll) }
                runTicket(Ticket(session, selectedGeneration)) { refreshTicket(it) }
            }
        }
    }

    fun disconnect() {
        val nextGeneration = beginSelection()
        mutableState.update { ControlCenterState(devices = it.devices, discovered = it.discovered, discovering = it.discovering,
            selectedTargetIds = it.selectedTargetIds, groupOperations = it.groupOperations, targetSelectionSaving = it.targetSelectionSaving,
            sessionGeneration = nextGeneration) }
    }

    fun forgetDevice(id: String): Job {
        if (state.value.activeDeviceId == id) disconnect()
        return scope.launch {
            ready.join()
            try {
                loadFailure?.let { throw it }
                persistenceMutex.withLock {
                    val candidate = synchronized(lock) { sessions.filterNot { it.device.id == id } }
                    withContext(io) { store.save(candidate) }
                    synchronized(lock) { sessions = candidate; cache.remove(id) }
                    mutableState.update { it.copy(devices = candidate.map { saved -> saved.device },
                        selectedTargetIds = it.selectedTargetIds.intersect(candidate.map { saved -> saved.device.id }.toSet())) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutableState.update { it.copy(error = errorOf(error)) } }
        }
    }

    fun clearError() { mutableState.update { it.copy(error = null, lastResult = null) } }
    /** Music targets only the viewed Portal, never the separate multi-Portal write selection. */
    suspend fun readMusic(deviceKey: String): JSONObject? = musicRequest(deviceKey)
    suspend fun sendMusic(deviceKey: String, body: JSONObject): JSONObject? = musicRequest(deviceKey, body)

    private suspend fun musicRequest(deviceKey: String, body: JSONObject? = null): JSONObject? {
        val selected = ticket() ?: return null
        fun valid() = owns(selected) && state.value.connected &&
            "${selected.session.device.id}:${selected.generation}" == deviceKey
        if (!valid()) return null
        try {
            val result = if (body == null) withContext(io) { client.get(selected.session, "music") }
            else writeMutex.withLock {
                if (!valid()) return@withLock null
                withContext(io) { client.post(selected.session, "music/command", body) }
            }
            return result?.takeIf { valid() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (!valid()) return null
            if (error is CompanionException && error.error.status in setOf(401, 403)) {
                update(selected) { it.copy(connection = CompanionConnection.UNAUTHORIZED, error = error.error) }
            }
            throw error
        }
    }

    /**
     * Feature routes (`/remote/<route>`, e.g. routines) on the viewed Portal only, bound to the
     * session [deviceKey] (`"<deviceId>:<generation>"`) the caller rendered; null once the user has
     * switched Portals. Writes are serialized with the other companion writes.
     */
    suspend fun featureRequest(deviceKey: String, route: String, body: JSONObject? = null): JSONObject? {
        val selected = ticket() ?: return null
        fun valid() = owns(selected) && state.value.connected &&
            "${selected.session.device.id}:${selected.generation}" == deviceKey
        if (!valid()) return null
        try {
            val result = if (body == null) withContext(io) { client.get(selected.session, route) }
            else writeMutex.withLock {
                if (!valid()) return@withLock null
                withContext(io) { client.post(selected.session, route, body) }
            }
            return result?.takeIf { valid() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (!valid()) return null
            if (error is CompanionException && error.error.status in setOf(401, 403)) {
                update(selected) { it.copy(connection = CompanionConnection.UNAUTHORIZED, error = error.error) }
            }
            throw error
        }
    }

    fun refresh(): Job = request(showBusy = false) { refreshTicket(it) }
    fun refreshStatus(): Job = request(showBusy = false) { selected ->
        val info = withContext(io) { optionalGet(selected.session, "companion") }
        if (!owns(selected)) return@request
        val device = info?.let { deviceFromInfo(selected.session.device, it) }
        val playing = withContext(io) { client.get(selected.session, "nowplaying") }
        if (device != null) persistDeviceMetadata(selected, device)
        update(selected) { old ->
            old.copy(info = info?.let { CompanionClient.sanitized(it) }, nowPlaying = playing.optJSONObject("np") ?: playing,
                connection = CompanionConnection.CONNECTED, lastUpdatedAt = clock(),
                error = if (old.connection in setOf(CompanionConnection.OFFLINE, CompanionConnection.UNAUTHORIZED)) null else old.error)
        }
    }
    fun refreshSettings(): Job = request(showBusy = false) { refreshRoute(it, "settings") }
    fun refreshApps(): Job = request(showBusy = false) { refreshRoute(it, "apps") }
    fun refreshSources(): Job = request(showBusy = false) { refreshRoute(it, "sources") }
    fun refreshOperations(): Job = request(showBusy = false) { refreshRoute(it, "operations") }
    fun refreshLayout(): Job = request(showBusy = false) { refreshRoute(it, "layout") }
    fun refreshCatalog(): Job = request(showBusy = false) { refreshRoute(it, "catalog") }
    fun refreshScenes(): Job = request() { refreshRoute(it, "scenes") }
    fun refreshJarvis(): Job = request(showBusy = false) { refreshRoute(it, "jarvis") }

    fun remoteAction(action: String): Job = command("key", JSONObject().put("action", action))
    /** Aggregate each cursor segment; one in-flight input request, without global loading changes. */
    fun moveCursor(dx: Float, dy: Float): Job {
        if (!dx.isFinite() || !dy.isFinite()) return failedJob(IllegalArgumentException("Invalid cursor delta."))
        val selected = ticket() ?: return failedJob(IllegalStateException("Select a paired Portal first."))
        return synchronized(lock) {
            val tail = inputQueue.peekLast()?.takeIf { it.route == "cursor" && it.ticket.generation == selected.generation }
            if (tail != null) { tail.dx += dx; tail.dy += dy }
            else {
                if (inputQueue.size >= 64) return failedJob(IllegalStateException("Remote input is busy. Wait a moment and retry."))
                inputQueue.addLast(InputWork(selected, "cursor", dx = dx.toDouble(), dy = dy.toDouble()))
            }
            startInputWorker()
        }
    }

    /** Actions are barriers in the same bounded FIFO: later movement cannot overtake a tap. */
    private fun inputAction(route: String, body: JSONObject): Job {
        val selected = ticket() ?: return failedJob(IllegalStateException("Select a paired Portal first."))
        return synchronized(lock) {
            if (inputQueue.size >= 64) return failedJob(IllegalStateException("Remote input is busy. Wait a moment and retry."))
            val completion = CompletableDeferred<Unit>()
            inputQueue.addLast(InputWork(selected, route, body, completion = completion))
            startInputWorker()
            completion
        }
    }

    private fun startInputWorker(): Job =
        cursorJob?.takeIf { it.isActive } ?: scope.launch(start = CoroutineStart.LAZY) {
                val worker = currentCoroutineContext()[Job]
                try {
                    while (true) {
                        val cursorNext = synchronized(lock) {
                            val head = inputQueue.peekFirst()
                            if (head == null) {
                                if (cursorJob === worker) cursorJob = null
                                return@launch
                            }
                            head.route == "cursor"
                        }
                        if (cursorNext) delay(40)
                        val work = synchronized(lock) { inputQueue.pollFirst() } ?: continue
                        try {
                            writeMutex.withLock {
                                if (!owns(work.ticket)) return@withLock
                                val body = work.body ?: JSONObject().put("dx", work.dx).put("dy", work.dy)
                                val response = withContext(io) { client.post(work.ticket.session, work.route, body) }
                                if (work.route != "cursor") update(work.ticket) { it.copy(lastResult = CompanionClient.sanitized(response), error = null) }
                            }
                            work.completion?.complete(Unit)
                        } catch (cancelled: CancellationException) {
                            work.completion?.cancel()
                            throw cancelled
                        } catch (error: Exception) {
                                val failure = errorOf(error)
                                update(work.ticket) { old ->
                                    if (old.error?.code == failure.code) old else old.copy(error = failure,
                                        connection = failureConnection(error, old.connection))
                                }
                                work.completion?.complete(Unit)
                        }
                    }
                } finally {
                    synchronized(lock) {
                        if (cursorJob === worker) {
                            cursorJob = null
                            inputQueue.forEach { it.completion?.cancel() }
                            inputQueue.clear()
                        }
                    }
                }
            }.also { cursorJob = it; it.start() }
    fun tap(): Job = inputAction("tap", JSONObject())
    fun swipe(dx: Float, dy: Float): Job = inputAction("swipe", delta(dx, dy))
    fun scroll(direction: String): Job = inputAction("scroll", JSONObject().put("dir", direction))
    fun sendText(text: String, mode: String = "set"): Job = command("text", JSONObject().put("text", text).put("mode", mode))
    suspend fun sendTextConfirmed(text: String): Boolean {
        var confirmed = false
        request { selected ->
            writeMutex.withLock {
                if (!owns(selected)) return@withLock
                val result = withContext(io) {
                    client.post(selected.session, "text", JSONObject().put("text", text).put("mode", "set"))
                }
                if (!owns(selected)) return@withLock
                update(selected) { it.copy(lastResult = CompanionClient.sanitized(result), error = null) }
                confirmed = true
            }
        }.join()
        return confirmed
    }
    fun media(action: String, positionMs: Long? = null): Job = command("media", JSONObject().put("action", action).apply { positionMs?.let { put("positionMs", it) } }, listOf("nowplaying"))
    fun volume(direction: String): Job = command("volume", JSONObject().put("dir", direction), listOf("nowplaying"))
    fun setVolume(level: Int): Job = command("volume", JSONObject().put("dir", "set").put("level", level), listOf("nowplaying"))
    fun launchApp(packageName: String): Job = command("launch", JSONObject().put("packageName", packageName))
    fun previewScreensaver(): Job = remoteAction("screensaver")

    /** Selection is updated synchronously; persistence is serialized with the pairing store. */
    fun selectTargets(ids: Set<String>): Job {
        val selected = if (ready.isCompleted) ids.toSet().intersect(state.value.devices.map { it.id }.toSet()) else ids.toSet()
        synchronized(lock) { explicitTargetSelection = true; requestedTargetIds = selected; selectionSaves++ }
        mutableState.update { it.copy(selectedTargetIds = selected, targetSelectionSaving = true) }
        return scope.launch {
            ready.join()
            try {
                persistenceMutex.withLock { withContext(io) { store.saveSelectedIds(state.value.selectedTargetIds) } }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutableState.update { it.copy(error = CompanionError("selection_not_saved", "Target selection could not be saved. Choose targets again before restarting.")) } }
            finally { synchronized(lock) {
                selectionSaves--
                mutableState.update { it.copy(targetSelectionSaving = selectionSaves > 0) }
            } }
        }
    }
    fun captureSelectedTargets(): CompanionTargets = synchronized(lock) {
        CompanionTargets(sessions.filter { it.device.id in state.value.selectedTargetIds }.map { CompanionSession(it.device.copy(), it.token) })
    }
    fun writeSelectedSettings(domain: String, values: JSONObject): Job {
        val activeRead = ticket()
        val reference = state.value.settings.firstOrNull { it.id == domain }
        val domainTitle = reference?.title?.takeIf { it.isNotBlank() && it != domain }
            ?: CompanionGroupSettings.humanizedTitle(domain)
        val controls = values.keys().asSequence().mapNotNull { key -> reference?.controls?.firstOrNull { it.key == key } }.toList()
        val title = if (controls.size == values.length() && controls.isNotEmpty()) controls.joinToString { control ->
            control.title.takeIf { it.isNotBlank() && it != control.key }
                ?: CompanionGroupSettings.humanizedTitle(control.key)
        } else domainTitle
        return try {
            groups.settings(captureSelectedTargets(), domain, values, title = title, domainTitle = domainTitle) { session ->
                synchronized(lock) { cache.remove(session.device.id) }
                if (activeRead != null && activeRead.session.device.id == session.device.id && owns(activeRead)) refreshRoute(activeRead, "settings")
            }
        } catch (error: Exception) { failedJob(error) }
    }
    fun uploadSelectedUris(context: Context, uris: List<Uri>, targets: CompanionTargets = captureSelectedTargets()): Job =
        uploadSelectedPhotos(CompanionPhotos.uriSources(context, uris), targets)
    fun uploadSelectedPhotos(sources: List<PhotoUploadSource>, targets: CompanionTargets = captureSelectedTargets()): Job {
        val activeRead = ticket()
        return try {
            groups.photos(targets, sources) { session ->
                invalidatePhotoImages(session)
                if (activeRead != null && activeRead.session.device.id == session.device.id && owns(activeRead)) refreshCurrentPhotos(activeRead)
            }
        } catch (error: Exception) { failedJob(error) }
    }
    fun retryGroupOperation(id: Long): Job = groups.retry(id)
    fun cancelGroupOperation(id: Long) = groups.cancel(id)
    fun dismissGroupOperation(id: Long) = groups.dismiss(id)

    fun writeSettings(domain: String, values: JSONObject): Job {
        val copy = JSONObject(values.toString())
        return command("settings", JSONObject().put("domain", domain).put("values", copy), listOf("settings")) { result ->
            val applied = result.optJSONArray("applied") ?: JSONArray()
            val appliedKeys = (0 until applied.length()).map { applied.optString(it) }.toSet()
            val missing = copy.keys().asSequence().filterNot { it in appliedKeys }.toList()
            if (missing.isNotEmpty()) throw CompanionException(CompanionError("validation_failed", "Portal rejected settings: ${missing.joinToString()}.", details = CompanionClient.sanitized(result).put("rejectedKeys", JSONArray(missing))))
        }
    }

    fun writeSources(values: JSONObject): Job {
        val copy = JSONObject(values.toString())
        return command("sources", copy, listOf("sources", "settings")) { result ->
            val applied = result.optJSONArray("applied") ?: JSONArray()
            val appliedGroups = (0 until applied.length()).map { applied.optString(it) }.toSet()
            fun group(key: String): String = when {
                key in setOf("immichAlbumId", "immichAlbumName") -> "immichAlbum"
                key.startsWith("immich") -> "immich"
                key.startsWith("smb") -> "smb"
                key.startsWith("dav") -> "dav"
                key == "calendarUrl" -> "url"
                else -> key
            }
            val missing = copy.keys().asSequence().filterNot { group(it) in appliedGroups }.toList()
            if (missing.isNotEmpty()) throw CompanionException(CompanionError("not_applied", "Portal rejected source fields: ${missing.joinToString()}.",
                details = CompanionClient.sanitized(result, sourceCredentials = true).put("rejectedKeys", JSONArray(missing))))
        }
    }
    fun fetchImmichAlbums(url: String, key: String): Job = command("immich/albums", JSONObject().put("url", url).put("key", key))
    fun updateLayout(layout: JSONObject, expectedRevision: String? = null): Job = command("layout", JSONObject().put("layout", JSONObject(layout.toString())).apply { expectedRevision?.let { put("expectedRevision", it) } }, listOf("layout"))
    fun exportSnapshot(): Job = request { refreshRoute(it, "snapshot") }
    suspend fun exportSnapshotResult(): JSONObject? {
        val selected = ticket() ?: return null
        return try {
            val response = withContext(io) { client.get(selected.session, "snapshot") }
            if (!owns(selected)) return null
            val snapshot = response.optJSONObject("snapshot") ?: response
            update(selected) { it.copy(snapshot = snapshot) }
            JSONObject(snapshot.toString())
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            update(selected) { it.copy(error = errorOf(error), connection = failureConnection(error, it.connection)) }
            null
        }
    }
    fun restoreSnapshot(snapshot: JSONObject, expectedRevision: String? = null): Job = command("snapshot", JSONObject().put("snapshot", JSONObject(snapshot.toString())).apply { expectedRevision?.let { put("expectedRevision", it) } }, refreshAll = true)
    fun startOperation(request: JSONObject): Job = command("operation", JSONObject(request.toString()), listOf("operations"))
    fun cancelOperation(id: String): Job = startOperation(JSONObject().put("type", "cancel").put("id", id))
    fun retryOperation(id: String): Job = startOperation(JSONObject().put("type", "retry").put("id", id))
    fun runScene(id: String): Job = command("scene", JSONObject().put("id", id), listOf("operations"))
    /** Both the list and its optional revision are captured before entering the write queue. */
    fun writeScenes(scenes: JSONArray, expectedRevision: String? = state.value.scenesRevision): Job {
        if (state.value.scenesRevision != null && expectedRevision == null) return failedJob(
            IllegalStateException("Scenes changed while you were editing. Reload scenes before saving."))
        return command("scenes", JSONObject().put("scenes", JSONArray(scenes.toString())).apply {
            expectedRevision?.let { put("expectedRevision", it) }
        }, listOf("scenes"))
    }
    fun saveScenes(scenes: JSONArray, expectedRevision: String? = state.value.scenesRevision): Job = writeScenes(scenes, expectedRevision)
    fun updateJarvis(values: JSONObject): Job = command("jarvis", JSONObject(values.toString()), listOf("jarvis"))
    fun savePresets(presets: JSONArray): Job = command("presets", JSONObject().put("presets", JSONArray(presets.toString())))
    fun runPreset(id: String): Job = command("preset", JSONObject().put("id", id))
    fun refreshPresets(): Job = request { ticket ->
        val result = withContext(io) { client.get(ticket.session, "presets") }
        update(ticket) { it.copy(lastResult = result) }
    }

    fun listPhotos(folder: String? = null): Job {
        val view = state.value
        val all = folder == null && view.photoBrowseAll
        val revision = nextPhotoRevision()
        return request { selected ->
            val safeFolder = CompanionPhotos.exactFolder(folder ?: view.photoFolder)
            if (refreshPhotos(selected, safeFolder, all, revision))
                persistPhotoFolder(selected, safeFolder, all, revision)
        }
    }

    fun listAllPhotos(): Job {
        val view = state.value
        if (!view.supports("photoLibrary")) return failedJob(
            IllegalStateException("Update Immortal on this Portal to browse all photo folders."))
        val revision = nextPhotoRevision()
        return request { selected ->
            if (refreshPhotos(selected, view.photoFolder, true, revision))
                persistPhotoFolder(selected, view.photoFolder, true, revision)
        }
    }

    fun deletePhoto(path: String): Job = request { selected ->
        withContext(io) { client.deletePhoto(selected.session, path) }
        invalidatePhotoImages(selected.session)
        refreshCurrentPhotos(selected)
    }

    fun uploadUris(context: Context, uris: List<Uri>, folder: String = state.value.photoFolder): Job =
        uploadPhotos(CompanionPhotos.uriSources(context, uris), folder)

    fun uploadPhotos(sources: List<PhotoUploadSource>, folder: String = state.value.photoFolder): Job {
        if (photoUploadJob?.isActive == true) return failedJob(IllegalStateException("Wait for the current photo transfer to finish."))
        val selected = ticket() ?: return failedJob(IllegalStateException("Select a paired Portal first."))
        val safeFolder = try { CompanionPhotos.exactFolder(folder) } catch (error: Exception) { return failedJob(error) }
        val imports = sources.toList()
        return scope.launch {
            runTicket(selected) {
                update(selected) { it.copy(photoTransfer = PhotoTransferState(imports.size)) }
                try {
                    for (source in imports) {
                        currentCoroutineContext().ensureActive()
                        if (!owns(selected)) break
                        var upload: CompanionUpload? = null
                        var itemFailed = false
                        try {
                            upload = withContext(io) { source.prepare() }
                            if (!owns(selected)) break
                            val job = currentCoroutineContext()
                            var sent = 0L
                            withContext(io) {
                                client.uploadPhoto(selected.session, safeFolder, upload) { progress ->
                                    job.ensureActive()
                                    val increment = (progress - sent).coerceAtLeast(0)
                                    sent = progress
                                    update(selected) { old -> old.copy(photoTransfer = old.photoTransfer?.let { transfer ->
                                        transfer.copy(bytesSent = transfer.bytesSent + increment)
                                    }) }
                                }
                            }
                            invalidatePhotoImages(selected.session)
                            update(selected) { old -> old.copy(photoTransfer = old.photoTransfer?.let { it.copy(completed = it.completed + 1) }) }
                        } catch (cancelled: CancellationException) {
                            itemFailed = true
                            update(selected) { old -> old.copy(photoTransfer = old.photoTransfer?.let {
                                it.copy(failures = it.failures + PhotoTransferFailure(upload?.name ?: source.name,
                                    CompanionError("interrupted", "Transfer was interrupted. Refresh the folder before retrying.")))
                            }) }
                            throw cancelled
                        } catch (error: Exception) {
                            itemFailed = true
                            update(selected) { old -> old.copy(photoTransfer = old.photoTransfer?.let {
                                it.copy(failures = it.failures + PhotoTransferFailure(upload?.name ?: source.name, errorOf(error)))
                            }) }
                        } finally {
                            val cleanupFailed = withContext(io + NonCancellable) {
                                try { upload?.dispose?.invoke(); false } catch (_: Exception) { true }
                            }
                            if (cleanupFailed) update(selected) { old ->
                                old.copy(photoTransfer = old.photoTransfer?.let { transfer ->
                                    val failures = if (itemFailed && transfer.failures.isNotEmpty()) {
                                        val last = transfer.failures.last()
                                        transfer.failures.dropLast(1) + last.copy(error = last.error.copy(
                                            message = last.error.message + " Local import cleanup also needs attention."))
                                    } else transfer.failures + PhotoTransferFailure(upload?.name ?: source.name,
                                        CompanionError("photo_cleanup_failed",
                                            "Local import cleanup needs attention. The upload result is retained."))
                                    transfer.copy(failures = failures)
                                })
                            }
                        }
                    }
                    if (owns(selected)) {
                        try { refreshCurrentPhotos(selected) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) {
                            if (state.value.photoTransfer?.failures.isNullOrEmpty()) throw error
                            update(selected) { it.copy(connection = failureConnection(error, it.connection)) }
                        }
                        update(selected) { old ->
                            val failures = old.photoTransfer?.failures.orEmpty()
                            val guidance = if (failures.any { it.error.code == "upload_unconfirmed" })
                                "Upload completion is unconfirmed; refresh this Portal's photos before retrying."
                            else "Check individual outcomes and refresh before retrying."
                            old.copy(error = if (failures.isEmpty()) null else CompanionError("photo_transfer_failed",
                                "${failures.size} image(s) need review. $guidance"))
                        }
                    }
                } finally { update(selected) { it.copy(photoTransfer = it.photoTransfer?.copy(running = false)) } }
            }
        }.also { photoUploadJob = it }
    }

    fun cancelPhotoUpload() { photoUploadJob?.cancel() }
    fun loadPhotoThumbnail(path: String, onLoaded: (ByteArray) -> Unit): Job = request(showBusy = false) { selected ->
        val bytes = photoBytes(path) ?: return@request
        if (owns(selected)) onLoaded(bytes)
    }

    suspend fun photoBytes(path: String, thumbnail: Boolean = true): ByteArray? =
        imageBytes("photo", path, thumbnail) { client.photoBytes(it, path, thumbnail) }

    suspend fun appIconBytes(packageName: String): ByteArray? =
        imageBytes("icon", packageName, true) { client.appIconBytes(it, packageName) }

    suspend fun mediaArtBytes(version: String): ByteArray? =
        imageBytes("art", version, true) { client.mediaArtBytes(it, version) }

    private fun credentialFingerprint(session: CompanionSession): String =
        MessageDigest.getInstance("SHA-256").digest(session.token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun invalidatePhotoImages(session: CompanionSession) {
        synchronized(lock) {
            val iterator = imageCache.entries.iterator()
            while (iterator.hasNext()) {
                val item = iterator.next()
                if (item.key.kind == "photo" && item.key.endpoint == session.device.endpoint) {
                    imageCacheBytes -= item.value.bytes.size
                    iterator.remove()
                }
            }
        }
    }

    private suspend fun imageBytes(kind: String, target: String, cacheable: Boolean, load: suspend (CompanionSession) -> ByteArray): ByteArray? {
        val selected = ticket() ?: return null
        val metadata = if (kind == "photo") state.value.photos.firstOrNull { it.path == target }?.let { "${it.size}:${it.modified}" }.orEmpty()
            else if (kind == "icon") state.value.apps.let { apps ->
                (0 until apps.length()).mapNotNull { apps.optJSONObject(it) }.firstOrNull { it.optString("packageName") == target }?.opt("versionCode")?.toString().orEmpty()
            } else ""
        val key = ImageKey(selected.session.device.id, selected.session.device.endpoint, credentialFingerprint(selected.session), kind, target, metadata)
        fun cached(): ByteArray? = synchronized(lock) {
            if (!owns(selected) || !cacheable) null
            else imageCache[key]?.takeIf { clock() - it.savedAt < 5 * 60 * 1000 }?.bytes?.copyOf()
        }
        cached()?.let { return it }
        return try {
            imageSlots.withPermit {
                if (!owns(selected)) return@withPermit null
                cached()?.let { return@withPermit it }
                val bytes = withContext(io) { load(selected.session) }
                if (!owns(selected)) return@withPermit null
                if (cacheable) synchronized(lock) {
                    imageCache.remove(key)?.let { imageCacheBytes -= it.bytes.size }
                    imageCache[key] = CachedImage(bytes.copyOf(), clock())
                    imageCacheBytes += bytes.size
                    while (imageCache.size > 64 || imageCacheBytes > 8L * 1024 * 1024) {
                        val oldest = imageCache.entries.iterator()
                        if (!oldest.hasNext()) break
                        val entry = oldest.next()
                        imageCacheBytes -= entry.value.bytes.size
                        oldest.remove()
                    }
                }
                bytes
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            // A missing image should not replace control errors or invalidate the device session.
            if (error is CompanionException && isAuthenticationFailure(error)) {
                update(selected) { it.copy(connection = CompanionConnection.UNAUTHORIZED, error = error.error) }
            }
            null
        }
    }

    private fun nextPhotoRevision(): Long = synchronized(lock) { ++photoRequestRevision }
    private fun ownsPhoto(selected: Ticket, revision: Long): Boolean =
        synchronized(lock) { owns(selected) && photoRequestRevision == revision }

    private suspend fun refreshCurrentPhotos(selected: Ticket) {
        if (!owns(selected)) return
        val view = state.value
        refreshPhotos(selected, view.photoFolder, view.photoBrowseAll, nextPhotoRevision())
    }

    private suspend fun refreshPhotos(selected: Ticket, folder: String, all: Boolean, revision: Long): Boolean {
        if (!ownsPhoto(selected, revision)) return false
        try {
            val recursive = all && state.value.supports("photoLibrary")
            val response = try {
                withContext(io) { client.photosExact(selected.session, if (recursive) "/sdcard" else folder, recursive = recursive) }
            } catch (error: CompanionException) {
                if (recursive || error.error.status != 404 || error.error.code != "folder_not_found") throw error
                // The first upload creates its destination. A new collection is empty until then.
                JSONObject().put("ok", true).put("path", folder).put("files", JSONArray())
            }
            if (recursive && !response.optBoolean("recursive")) throw IllegalStateException(
                "This Portal did not return a complete photo-library listing. Update Immortal and retry.")
            synchronized(lock) {
                if (!ownsPhoto(selected, revision)) return false
                update(selected) { it.copy(photoFolder = folder, photos = CompanionPhotos.parse(response),
                    photoBrowseAll = all, photoListingLoaded = true, photoListingError = null, photoListingAll = recursive,
                    photoFolders = if (recursive) CompanionPhotos.folders(response) else it.photoFolders,
                    photoListingTruncated = response.optBoolean("truncated"),
                    photoFreeBytes = response.optLong("freeBytes", -1).takeIf { bytes -> bytes >= 0 }) }
            }
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (!ownsPhoto(selected, revision)) return false
            update(selected) { it.copy(photoListingError = errorOf(error).message) }
            throw error
        }
    }

    private suspend fun persistPhotoFolder(selected: Ticket, folder: String, all: Boolean, revision: Long) {
        persistenceMutex.withLock {
            if (!ownsPhoto(selected, revision)) return@withLock
            val old = synchronized(lock) { sessions }
            val candidate = old.map {
                if (it.token == selected.session.token && it.device.endpoint == selected.session.device.endpoint)
                    CompanionSession(it.device.copy(photoFolder = folder, photoBrowseAll = all), it.token) else it
            }
            withContext(io + NonCancellable) { store.save(candidate) }
            if (!ownsPhoto(selected, revision)) {
                withContext(io + NonCancellable) { store.save(old) }
                return@withLock
            }
            synchronized(lock) { sessions = candidate }
            mutableState.update { it.copy(devices = candidate.map { saved -> saved.device }) }
        }
    }

    private fun command(route: String, body: JSONObject, refresh: List<String> = emptyList(), refreshAll: Boolean = false,
        validate: (JSONObject) -> Unit = {}): Job {
        return request { selected ->
        writeMutex.withLock {
            if (!owns(selected)) return@withLock
            var rejection: Exception? = null
            var result: JSONObject? = null
            try {
                result = withContext(io) { client.post(selected.session, route, body) }
                validate(result)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { rejection = error }
            // Re-read even rejected batches: old servers can partially apply a batch.
            try {
                if (rejection !is CompanionException || rejection.error.status !in setOf(401, 403)) {
                    if (refreshAll) refreshTicket(selected) else refresh.forEach { if (owns(selected)) refreshRoute(selected, it) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (rejection == null) rejection = error }
            rejection?.let { throw it }
            update(selected) { it.copy(lastResult = result?.let { response -> CompanionClient.sanitized(response, sourceCredentials = route == "sources") }, error = null) }
        }
        }
    }

    private fun request(showBusy: Boolean = true, block: suspend (Ticket) -> Unit): Job {
        val selected = ticket() ?: return failedJob(IllegalStateException("Select a paired Portal first."))
        return scope.launch { runTicket(selected, showBusy, block) }
    }

    private suspend fun runTicket(selected: Ticket, showBusy: Boolean = true, block: suspend (Ticket) -> Unit) {
        synchronized(lock) {
            if (!owns(selected)) return
            if (showBusy) {
                activeCalls++
                mutableState.update { it.copy(busy = true, error = null, lastResult = null) }
            }
        }
        try { block(selected) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            update(selected) { it.copy(error = errorOf(error), lastResult = null, connection = failureConnection(error, it.connection)) }
        } finally {
            synchronized(lock) {
                if (owns(selected) && showBusy) {
                    activeCalls = (activeCalls - 1).coerceAtLeast(0)
                    mutableState.update { it.copy(busy = activeCalls > 0) }
                }
            }
        }
    }

    private suspend fun refreshTicket(selected: Ticket) {
        if (!owns(selected)) return
        val info = withContext(io) { optionalGet(selected.session, "companion") }
        if (!owns(selected)) return
        val refreshedDevice = if (info != null) deviceFromInfo(selected.session.device, info) else selected.session.device
        val payloads = linkedMapOf<String, JSONObject>()
        if (info != null) payloads["companion"] = info
        for (route in if (preloadManagement) listOf("settings", "apps", "sources", "nowplaying") else listOf("nowplaying")) {
            if (!owns(selected)) return
            payloads[route] = withContext(io) { client.get(selected.session, route) }
        }
        for (route in if (preloadManagement) listOf("layout", "catalog", "operations", "scenes", "jarvis") else emptyList()) {
            if (!owns(selected)) return
            if (info?.optJSONObject("capabilities")?.optBoolean(route) == true) {
                withContext(io) { optionalGet(selected.session, route) }?.let { payloads[route] = it }
            }
        }
        if (info != null) persistDeviceMetadata(selected, refreshedDevice)
        update(selected) { old ->
            var next = old.copy(connection = CompanionConnection.CONNECTED, info = info, error = null, lastUpdatedAt = clock())
            payloads.forEach { (route, result) -> next = merge(next, route, result) }
            next
        }
    }

    private suspend fun persistDeviceMetadata(selected: Ticket, device: CompanionDevice) {
        persistenceMutex.withLock {
            if (!owns(selected)) return@withLock
            val old = synchronized(lock) { sessions }
            val existing = old.firstOrNull { it.token == selected.session.token && it.device.endpoint == selected.session.device.endpoint } ?: return@withLock
            val updated = CompanionSession(device.copy(photoFolder = existing.device.photoFolder,
                photoBrowseAll = existing.device.photoBrowseAll), existing.token)
            if (existing.device == updated.device) return@withLock
            val candidate = old.filterNot { it === existing || it.device.id == updated.device.id } + updated
            withContext(io) { store.save(candidate) }
            if (!owns(selected)) {
                withContext(io) { store.save(old) }
                return@withLock
            }
            synchronized(lock) { sessions = candidate; current = updated }
            update(selected) { oldState -> oldState.copy(activeDeviceId = updated.device.id,
                devices = candidate.map { saved -> saved.device }, selectedTargetIds = oldState.selectedTargetIds.map {
                    if (it == existing.device.id) updated.device.id else it
                }.toSet()) }
            if (existing.device.id != updated.device.id && explicitTargetSelection) {
                requestedTargetIds = state.value.selectedTargetIds
                withContext(io) { store.saveSelectedIds(state.value.selectedTargetIds) }
            }
        }
    }

    private suspend fun refreshRoute(selected: Ticket, route: String) {
        if (!owns(selected)) return
        val response = withContext(io) { client.get(selected.session, route) }
        update(selected) { merge(it, route, response) }
    }

    private fun merge(old: ControlCenterState, route: String, response: JSONObject): ControlCenterState = when (route) {
        "companion" -> old.copy(info = CompanionClient.sanitized(response))
        "settings" -> old.copy(settings = CompanionParser.settings(response), schema = response.optJSONObject("settings") ?: response)
        "sources" -> old.copy(sources = CompanionClient.sanitized(response.optJSONObject("sources") ?: response, sourceCredentials = true))
        "apps" -> old.copy(apps = response.optJSONArray("apps") ?: JSONArray())
        "nowplaying" -> old.copy(nowPlaying = response.optJSONObject("np") ?: response)
        "layout" -> old.copy(layout = response.optJSONObject("layout")?.let { JSONObject(it.toString()).apply {
            if (response.has("revision")) put("revision", response.get("revision"))
            response.optJSONArray("widgetKinds")?.let { put("widgetKinds", it) }
        } } ?: response)
        "snapshot" -> old.copy(snapshot = response.optJSONObject("snapshot") ?: response)
        "catalog" -> old.copy(catalog = response)
        "operations" -> old.copy(operations = response.optJSONArray("operations") ?: JSONArray())
        "scenes" -> old.copy(scenes = response.optJSONArray("scenes") ?: JSONArray(), scenesLoaded = true,
            scenesRevision = (response.opt("revision") as? String)?.takeIf { it.isNotBlank() })
        "jarvis" -> old.copy(jarvis = response.optJSONObject("jarvis") ?: response)
        else -> old
    }

    private suspend fun optionalGet(session: CompanionSession, route: String): JSONObject? = try { client.get(session, route) }
        catch (error: CompanionException) { if (error.error.status == 404) null else throw error }

    private fun deviceFromInfo(device: CompanionDevice, info: JSONObject, allowIdentityChange: Boolean = false): CompanionDevice {
        val id = info.optString("deviceId").ifBlank { device.id }
        if (!allowIdentityChange && !device.id.startsWith("legacy:") && id != device.id) {
            throw CompanionException(CompanionError("identity_mismatch", "This address belongs to a different Portal. Pair it again before controlling it."))
        }
        return device.copy(id = id, name = info.optString("name", device.name), model = info.optString("model").ifBlank { device.model }, apiLevel = info.optInt("apiLevel").takeIf { it > 0 } ?: device.apiLevel)
    }

    private fun failedJob(error: Exception): Job {
        mutableState.update { it.copy(error = errorOf(error), lastResult = null) }
        return CompletableDeferred(Unit)
    }
    private fun errorOf(error: Throwable): CompanionError = when (error) {
        is CompanionException -> error.error
        is IllegalArgumentException, is IllegalStateException -> CompanionError("invalid_request", error.message ?: "Invalid request.")
        else -> CompanionError("unreachable", "Could not reach the Portal or its private session storage. Check the connection and retry.")
    }
    private fun failureConnection(error: Throwable, current: CompanionConnection): CompanionConnection = when {
        isAuthenticationFailure(error) -> CompanionConnection.UNAUTHORIZED
        error is java.io.IOException && error !is CompanionException -> CompanionConnection.OFFLINE
        current == CompanionConnection.CONNECTING -> CompanionConnection.OFFLINE
        else -> current
    }
    private fun isAuthenticationFailure(error: Throwable): Boolean =
        error is CompanionException && (error.error.status == 401 ||
            error.error.status == 403 && error.error.code == "unauthorized")
    private fun delta(dx: Float, dy: Float) = JSONObject().put("dx", dx.toDouble()).put("dy", dy.toDouble())

    fun startDiscovery() {
        val browser = discovery ?: run {
            mutableState.update { it.copy(error = CompanionError("discovery_unavailable", "Discovery is unavailable. Enter the Portal address manually.")) }; return
        }
        val selectedGeneration = synchronized(lock) { ++discoveryGeneration }
        mutableState.update { it.copy(discovering = true) }
        browser.start(onChanged = { discovered ->
            synchronized(lock) { if (discoveryGeneration == selectedGeneration) mutableState.update { it.copy(discovered = discovered) } }
        }, onError = { message ->
            synchronized(lock) { if (discoveryGeneration == selectedGeneration) mutableState.update { it.copy(discovering = false, error = CompanionError("discovery_failed", message)) } }
        })
    }
    fun stopDiscovery() {
        synchronized(lock) { discoveryGeneration++ }
        discovery?.stop()
        mutableState.update { it.copy(discovering = false) }
    }
    fun close() { stopDiscovery(); disconnect(); scope.cancel() }

    /**
     * Best effort: makes each Portal in [targets] hold this phone's household key so Portals can
     * sign messages to each other. Failures (offline, older Portal without the route) are ignored
     * and retried on the next load or pairing.
     */
    private fun provisionHousehold(targets: List<CompanionSession>) {
        val source = household ?: return
        scope.launch(io) {
            val key = try { source.key() } catch (e: Exception) {
                android.util.Log.w(HOUSEHOLD_TAG, "household key unavailable", e); return@launch
            }
            android.util.Log.i(HOUSEHOLD_TAG, "provisioning ${targets.size} portal(s) fp=${CompanionHousehold.fingerprint(key)}")
            targets.forEach { session ->
                try {
                    val held = CompanionHousehold.provision(client, session, key)
                    android.util.Log.i(HOUSEHOLD_TAG, "portal ${session.device.id.take(6)} holds key=$held")
                }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (e: Exception) {
                    android.util.Log.w(HOUSEHOLD_TAG, "provision failed for ${session.device.id.take(6)}: ${e.javaClass.simpleName} ${e.message?.take(120)}")
                }
            }
        }
    }

    companion object {
        private const val HOUSEHOLD_TAG = "PortalHousehold"

        fun create(context: Context): CompanionRepository = CompanionRepository(CompanionClient(), FileCompanionSessionStore.create(context),
            discovery = NsdCompanionDiscovery(context.applicationContext), preloadManagement = false,
            household = PrefsHouseholdKeySource(context))
    }
}
