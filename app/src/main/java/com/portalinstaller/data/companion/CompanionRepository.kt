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
) {
    private val mutableState = MutableStateFlow(ControlCenterState())
    val state: StateFlow<ControlCenterState> = mutableState.asStateFlow()
    private val lock = Any()
    private val persistenceMutex = Mutex()
    private val writeMutex = Mutex()
    private var sessions = emptyList<CompanionSession>()
    private var current: CompanionSession? = null
    private var generation = 0L
    private var activeCalls = 0
    private var loadFailure: Throwable? = null
    private val cache = mutableMapOf<String, ControlCenterState>()
    private var discoveryGeneration = 0L
    private var photoUploadJob: Job? = null
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
            mutableState.update { it.copy(devices = saved.map { session -> session.device }) }
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
                    photoFolder = previous?.device?.photoFolder ?: CompanionPhotos.DEFAULT_FOLDER)
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
                    mutableState.update { it.copy(devices = candidate.map { saved -> saved.device }, activeDeviceId = session.device.id, photoFolder = session.device.photoFolder, busy = false) }
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
                mutableState.update { it.copy(photoFolder = session.device.photoFolder) }
                runTicket(Ticket(session, selectedGeneration)) { refreshTicket(it) }
            }
        }
    }

    fun disconnect() {
        val nextGeneration = beginSelection()
        mutableState.update { ControlCenterState(devices = it.devices, discovered = it.discovered, discovering = it.discovering,
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
                    mutableState.update { it.copy(devices = candidate.map { saved -> saved.device }) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutableState.update { it.copy(error = errorOf(error)) } }
        }
    }

    fun clearError() { mutableState.update { it.copy(error = null, lastResult = null) } }
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
    fun refreshScenes(): Job = request(showBusy = false) { refreshRoute(it, "scenes") }
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
    fun media(action: String, positionMs: Long? = null): Job = command("media", JSONObject().put("action", action).apply { positionMs?.let { put("positionMs", it) } }, listOf("nowplaying"))
    fun volume(direction: String): Job = command("volume", JSONObject().put("dir", direction))
    fun launchApp(packageName: String): Job = command("launch", JSONObject().put("packageName", packageName))
    fun previewScreensaver(): Job = remoteAction("screensaver")

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
    fun saveScenes(scenes: JSONArray): Job = command("scenes", JSONObject().put("scenes", JSONArray(scenes.toString())), listOf("scenes"))
    fun updateJarvis(values: JSONObject): Job = command("jarvis", JSONObject(values.toString()), listOf("jarvis"))
    fun savePresets(presets: JSONArray): Job = command("presets", JSONObject().put("presets", JSONArray(presets.toString())))
    fun runPreset(id: String): Job = command("preset", JSONObject().put("id", id))
    fun refreshPresets(): Job = request { ticket ->
        val result = withContext(io) { client.get(ticket.session, "presets") }
        update(ticket) { it.copy(lastResult = result) }
    }

    fun listPhotos(folder: String = state.value.photoFolder): Job = request { selected ->
        val safeFolder = CompanionPhotos.folder(folder)
        refreshPhotos(selected, safeFolder)
        persistPhotoFolder(selected, safeFolder)
    }

    fun deletePhoto(path: String): Job = request { selected ->
        val folder = state.value.photoFolder
        withContext(io) { client.deletePhoto(selected.session, path) }
        invalidatePhotoImages(selected.session)
        refreshPhotos(selected, folder)
    }

    fun uploadUris(context: Context, uris: List<Uri>, folder: String = state.value.photoFolder): Job =
        uploadPhotos(CompanionPhotos.uriSources(context, uris), folder)

    fun uploadPhotos(sources: List<PhotoUploadSource>, folder: String = state.value.photoFolder): Job {
        if (photoUploadJob?.isActive == true) return failedJob(IllegalStateException("Wait for the current photo transfer to finish."))
        val selected = ticket() ?: return failedJob(IllegalStateException("Select a paired Portal first."))
        val safeFolder = try { CompanionPhotos.folder(folder) } catch (error: Exception) { return failedJob(error) }
        val imports = sources.toList()
        return scope.launch {
            runTicket(selected) {
                update(selected) { it.copy(photoTransfer = PhotoTransferState(imports.size)) }
                try {
                    for (source in imports) {
                        currentCoroutineContext().ensureActive()
                        if (!owns(selected)) break
                        var upload: CompanionUpload? = null
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
                            update(selected) { old -> old.copy(photoTransfer = old.photoTransfer?.let {
                                it.copy(failures = it.failures + PhotoTransferFailure(upload?.name ?: source.name,
                                    CompanionError("interrupted", "Transfer was interrupted. Refresh the folder before retrying.")))
                            }) }
                            throw cancelled
                        } catch (error: Exception) {
                            update(selected) { old -> old.copy(photoTransfer = old.photoTransfer?.let {
                                it.copy(failures = it.failures + PhotoTransferFailure(upload?.name ?: source.name, errorOf(error)))
                            }) }
                        } finally { withContext(io + NonCancellable) { upload?.dispose?.invoke() } }
                    }
                    if (owns(selected)) {
                        refreshPhotos(selected, safeFolder)
                        persistPhotoFolder(selected, safeFolder)
                        update(selected) { old ->
                            val failures = old.photoTransfer?.failures.orEmpty()
                            old.copy(error = if (failures.isEmpty()) null else CompanionError("photo_transfer_failed",
                                "${failures.size} image(s) could not be sent. Review the individual failures before retrying."))
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
            else state.value.apps.let { apps ->
                (0 until apps.length()).mapNotNull { apps.optJSONObject(it) }.firstOrNull { it.optString("packageName") == target }?.opt("versionCode")?.toString().orEmpty()
            }
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

    private suspend fun refreshPhotos(selected: Ticket, folder: String) {
        if (!owns(selected)) return
        val response = try {
            withContext(io) { client.photos(selected.session, folder) }
        } catch (error: CompanionException) {
            if (error.error.status != 404 || error.error.code != "folder_not_found") throw error
            // The first upload creates its destination. A new collection is empty until then.
            JSONObject().put("ok", true).put("path", folder).put("files", JSONArray())
        }
        update(selected) { it.copy(photoFolder = folder, photos = CompanionPhotos.parse(response),
            photoFreeBytes = response.optLong("freeBytes", -1).takeIf { bytes -> bytes >= 0 }) }
    }

    private suspend fun persistPhotoFolder(selected: Ticket, folder: String) {
        persistenceMutex.withLock {
            if (!owns(selected)) return@withLock
            val candidate = synchronized(lock) { sessions.map {
                if (it.token == selected.session.token && it.device.endpoint == selected.session.device.endpoint) CompanionSession(it.device.copy(photoFolder = folder), it.token) else it
            } }
            withContext(io) { store.save(candidate) }
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
            val updated = CompanionSession(device.copy(photoFolder = existing.device.photoFolder), existing.token)
            if (existing.device == updated.device) return@withLock
            val candidate = old.filterNot { it === existing || it.device.id == updated.device.id } + updated
            withContext(io) { store.save(candidate) }
            if (!owns(selected)) {
                withContext(io) { store.save(old) }
                return@withLock
            }
            synchronized(lock) { sessions = candidate; current = updated }
            update(selected) { it.copy(activeDeviceId = updated.device.id, devices = candidate.map { saved -> saved.device }) }
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
        "scenes" -> old.copy(scenes = response.optJSONArray("scenes") ?: JSONArray())
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

    companion object {
        fun create(context: Context): CompanionRepository = CompanionRepository(CompanionClient(), FileCompanionSessionStore.create(context),
            discovery = NsdCompanionDiscovery(context.applicationContext), preloadManagement = false)
    }
}
