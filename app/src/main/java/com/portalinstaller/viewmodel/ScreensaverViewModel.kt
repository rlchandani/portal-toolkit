package com.portalinstaller.viewmodel

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.portalinstaller.R
import com.portalinstaller.data.AdbErrors
import com.portalinstaller.data.AdbSession
import com.portalinstaller.data.AppContainer
import com.portalinstaller.data.ConnectionHolder
import com.portalinstaller.data.ContentUriHelper
import com.portalinstaller.data.PortalConnectionManager
import com.portalinstaller.data.files.DestinationExistsException
import com.portalinstaller.data.files.PathSafety
import com.portalinstaller.data.files.RemoteFile
import com.portalinstaller.data.photos.AuthOutcome
import com.portalinstaller.data.photos.OkHttpPickerHttp
import com.portalinstaller.data.photos.PhotosAuth
import com.portalinstaller.data.photos.PhotosPickerClient
import com.portalinstaller.data.photos.PickerException
import com.portalinstaller.data.screensaver.DedupeKey
import com.portalinstaller.data.screensaver.ImageFolder
import com.portalinstaller.data.store.HistoryEntry
import com.portalinstaller.data.transfer.TransferProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files

/**
 * Alege planul de incarcare: fisierele noi (dupa nume+dimensiune) plus
 * duplicatele explicit fortate prin [overrides]. Functie pura, testabila.
 */
internal fun planUpload(
    picked: List<Pair<String, Long>>,
    existing: Set<DedupeKey>,
    overrides: Set<String>
): List<Pair<String, Long>> =
    picked.filter { (name, size) ->
        DedupeKey(name, size) !in existing || name in overrides
    }

/** Starea managerului de screensaver: folderul ales, sugestii si continut. */
data class ScreensaverState(
    val folder: String? = null,
    val suggestions: List<ImageFolder> = emptyList(),
    val contents: List<RemoteFile> = emptyList(),
    val selection: Set<String> = emptySet(),
    val folderBytes: Long = 0L,
    val deviceFreeBytes: Long = 0L,
    val isLoading: Boolean = false,
    val listingFailed: Boolean = false,
)

internal data class ScreensaverFolderListing(
    val contents: List<RemoteFile>,
    val folderBytes: Long,
    val deviceFreeBytes: Long,
)

/** Only the latest listing for the captured connection and chosen folder may update the UI. */
internal class ScreensaverListings(
    private val holder: ConnectionHolder,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<ScreensaverState>,
    private val defaultFolder: String,
    private val discover: suspend (AdbSession) -> List<ImageFolder>,
    private val list: suspend (AdbSession, String) -> ScreensaverFolderListing,
) {
    private var revision = 0L
    private var job: Job? = null

    fun loadFolders(origin: AdbSession) = start(origin, discoverFolders = true)
    fun refresh(origin: AdbSession) = start(origin, discoverFolders = false)

    fun chooseFolder(folder: String) {
        cancel()
        state.value = state.value.copy(
            folder = folder, contents = emptyList(), selection = emptySet(),
            folderBytes = 0L, deviceFreeBytes = 0L, isLoading = false, listingFailed = false,
        )
    }

    fun cancel() {
        revision++
        job?.cancel()
        job = null
    }

    private fun start(origin: AdbSession, discoverFolders: Boolean) {
        cancel()
        if (!holder.isCurrent(origin)) return
        val requestRevision = revision
        var requestedFolder = state.value.folder
        state.value = state.value.copy(isLoading = true, listingFailed = false)
        fun isCurrent() = revision == requestRevision && holder.isCurrent(origin) &&
            state.value.folder == requestedFolder
        job = holder.launchForSession(scope, origin) {
            try {
                if (discoverFolders) {
                    val suggestions = try {
                        discover(origin)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                    currentCoroutineContext().ensureActive()
                    if (!isCurrent()) return@launchForSession
                    requestedFolder = requestedFolder ?: suggestions.firstOrNull()?.path ?: defaultFolder
                    state.value = state.value.copy(suggestions = suggestions, folder = requestedFolder)
                }
                val listing = list(origin, requestedFolder ?: defaultFolder)
                currentCoroutineContext().ensureActive()
                if (!isCurrent()) return@launchForSession
                state.value = state.value.copy(
                    contents = listing.contents, folderBytes = listing.folderBytes,
                    deviceFreeBytes = listing.deviceFreeBytes, isLoading = false, listingFailed = false,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (isCurrent()) {
                    state.value = state.value.copy(
                        isLoading = false, listingFailed = true, selection = emptySet(),
                    )
                }
            }
        }
    }
}

/** Rezumatul unei sesiuni de incarcare pe Portal. */
sealed class UploadSummaryState {
    data object Idle : UploadSummaryState()
    data class Uploading(val current: Int, val total: Int, val progress: TransferProgress) : UploadSummaryState()
    data class Done(val uploaded: Int, val skipped: Int, val failed: Int) : UploadSummaryState()
    data class Failed(val message: String) : UploadSummaryState()
}

/** Imports a picker selection with private staging, collision preservation, and session cleanup. */
internal suspend fun importPickerPhotos(
    client: PhotosPickerClient,
    token: String,
    sessionId: String,
    cacheDir: File,
    existingNames: Set<String>,
    waitMillis: Long,
    upload: suspend (File) -> Unit,
    onProgress: (UploadSummaryState.Uploading) -> Unit = {},
): UploadSummaryState.Done {
    try {
        onProgress(UploadSummaryState.Uploading(0, 0, TransferProgress(0L, 0L)))
        var session = client.getSession(token, sessionId)
        val timeout = minOf(waitMillis.coerceAtLeast(0), session.timeoutMillis)
        var waited = 0L
        while (!session.mediaItemsSet && waited < timeout) {
            val interval = minOf(session.pollIntervalMillis, timeout - waited)
            delay(interval)
            waited += interval
            session = client.getSession(token, sessionId)
        }
        if (!session.mediaItemsSet) return UploadSummaryState.Done(0, 0, 0)

        val photos = client.listAllPhotos(token, sessionId)
        val toDownload = photos.filter { it.filename !in existingNames }
        var skipped = photos.size - toDownload.size
        val preservedNames = existingNames.toMutableSet()
        var uploaded = 0
        var failed = 0
        var tokenExpired = false
        toDownload.forEachIndexed { index, item ->
            currentCoroutineContext().ensureActive()
            if (item.filename in preservedNames) {
                skipped++
                return@forEachIndexed
            }
            if (tokenExpired) {
                failed++
                return@forEachIndexed
            }
            onProgress(UploadSummaryState.Uploading(index + 1, toDownload.size, TransferProgress(0L, 0L)))
            var staging: File? = null
            try {
                PathSafety.requireName(item.filename)
                require(item.filename.none { it == '\\' || it.isISOControl() }) { "Invalid photo filename" }
                // Keep the remote filename without sharing a path with unrelated phone cache data.
                withContext(Dispatchers.IO) {
                    staging = Files.createTempDirectory(cacheDir.toPath(), "picker-import-").toFile()
                }
                val local = File(staging, item.filename)
                try {
                    client.downloadPhoto(token, item, local)
                } catch (e: PickerException) {
                    if (e.code == 401) tokenExpired = true
                    throw e
                }
                currentCoroutineContext().ensureActive()
                withContext(Dispatchers.IO) {
                    require(local.isFile && local.length() > 0) { "Empty photo download" }
                }
                upload(local)
                uploaded++
                preservedNames += item.filename
            } catch (e: CancellationException) {
                throw e
            } catch (_: DestinationExistsException) {
                skipped++
                preservedNames += item.filename
            } catch (_: Exception) {
                failed++
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    staging?.deleteRecursively()
                }
            }
        }
        return UploadSummaryState.Done(uploaded, skipped, failed)
    } finally {
        withContext(NonCancellable) {
            client.deleteSession(token, sessionId)
        }
    }
}

/**
 * Evenimente one-shot pentru importul din Google Photos: cer UI-ului sa lanseze un
 * ecran de sistem (consimtamant OAuth sau UI-ul de picker gazduit de Google), lucru
 * pe care un ViewModel nu il poate face singur.
 */
sealed class PickerEvent {
    data class NeedsAuthResolution(val pendingIntent: PendingIntent) : PickerEvent()
    data class OpenPicker(val uri: String) : PickerEvent()
}

internal enum class ScreensaverPickerPhase { AUTHORIZING, AUTH_RESOLUTION, CREATING, PICKER, IMPORTING }

/** One operation target survives the authorization UI and picker UI round trips. */
internal class ScreensaverPickerRequest(
    val origin: AdbSession,
    val folder: String,
) {
    var phase = ScreensaverPickerPhase.AUTHORIZING
    val authorization = CompletableDeferred<AuthOutcome>()
    val returned = CompletableDeferred<ScreensaverPickerImport>()
    var picker: Pair<String, String>? = null
    var job: Job? = null
}

internal class ScreensaverPickerImport(
    val cacheDir: File,
    val upload: suspend (AdbSession, String, File) -> Unit,
    val onProgress: (UploadSummaryState.Uploading) -> Unit,
    val onComplete: suspend (UploadSummaryState.Done, AdbSession, String) -> Unit,
)

/** Main-thread ownership of untagged ActivityResult and ON_RESUME callbacks. */
internal class ScreensaverPickerOwnership(private val holder: ConnectionHolder) {
    var active: ScreensaverPickerRequest? = null
        private set
    private var authorizationUi: ScreensaverPickerRequest? = null
    private var pickerUi: ScreensaverPickerRequest? = null

    fun begin(origin: AdbSession, folder: String): ScreensaverPickerRequest? {
        if (active != null || authorizationUi != null || pickerUi != null || !holder.isCurrent(origin)) return null
        return ScreensaverPickerRequest(origin, folder).also { active = it }
    }

    fun isCurrent(request: ScreensaverPickerRequest): Boolean =
        active === request && holder.isCurrent(request.origin)

    fun awaitAuthorization(request: ScreensaverPickerRequest): Boolean =
        transition(request, ScreensaverPickerPhase.AUTHORIZING, ScreensaverPickerPhase.AUTH_RESOLUTION)

    fun creatingSession(request: ScreensaverPickerRequest): Boolean {
        if (!isCurrent(request) || request.phase !in setOf(
                ScreensaverPickerPhase.AUTHORIZING, ScreensaverPickerPhase.CREATING
            )) return false
        request.phase = ScreensaverPickerPhase.CREATING
        return true
    }

    fun pickerReady(request: ScreensaverPickerRequest): Boolean =
        transition(request, ScreensaverPickerPhase.CREATING, ScreensaverPickerPhase.PICKER)

    fun authorizationShown(request: ScreensaverPickerRequest): Boolean {
        if (!isCurrent(request) || request.phase != ScreensaverPickerPhase.AUTH_RESOLUTION || authorizationUi != null) return false
        authorizationUi = request
        return true
    }

    fun pickerShown(request: ScreensaverPickerRequest): Boolean {
        if (!isCurrent(request) || request.phase != ScreensaverPickerPhase.PICKER || pickerUi != null) return false
        pickerUi = request
        return true
    }

    fun takeAuthorization(): ScreensaverPickerRequest? {
        val request = authorizationUi ?: return null
        authorizationUi = null
        return request.takeIf { transition(it, ScreensaverPickerPhase.AUTH_RESOLUTION, ScreensaverPickerPhase.CREATING) }
    }

    fun takePicker(): ScreensaverPickerRequest? {
        val request = pickerUi ?: return null
        pickerUi = null
        return request.takeIf { transition(it, ScreensaverPickerPhase.PICKER, ScreensaverPickerPhase.IMPORTING) }
    }

    fun finish(request: ScreensaverPickerRequest): Boolean {
        if (active !== request) return false
        active = null
        // Delivered UI still owes an untagged callback. Keep its owner until that callback is drained.
        return true
    }

    private fun transition(
        request: ScreensaverPickerRequest, from: ScreensaverPickerPhase, to: ScreensaverPickerPhase,
    ): Boolean {
        if (!isCurrent(request) || request.phase != from) return false
        request.phase = to
        return true
    }
}

/** One connection-owned coroutine spans authorization, picker presentation, and import. */
internal class ScreensaverPickerFlow(
    private val holder: ConnectionHolder,
    private val scope: CoroutineScope,
    private val client: PhotosPickerClient,
    private val listNames: suspend (AdbSession, String) -> Set<String>,
) {
    private val ownership = ScreensaverPickerOwnership(holder)
    private val queuedEvents = Channel<Pair<ScreensaverPickerRequest, PickerEvent>>(capacity = 4)
    val isBusy: Boolean get() = ownership.active != null
    val events: Flow<PickerEvent> = queuedEvents.receiveAsFlow().mapNotNull { (request, event) ->
        val deliver = when (event) {
            is PickerEvent.NeedsAuthResolution -> ownership.authorizationShown(request)
            is PickerEvent.OpenPicker -> ownership.pickerShown(request)
        }
        event.takeIf { deliver }
    }

    fun start(
        origin: AdbSession,
        folder: String,
        authorize: suspend () -> AuthOutcome,
        onFailure: (String) -> Unit = {},
        onCancelled: () -> Unit = {},
    ): Boolean {
        ownership.active?.takeIf { !ownership.isCurrent(it) }?.let { cancel() }
        val request = ownership.begin(origin, folder) ?: return false
        request.job = holder.launchForSession(scope, origin) {
            try {
                ensureCurrent(request)
                // UI contents can belong to a previous folder while its refresh is pending.
                // A failed listing must stop the import rather than imply an empty folder.
                val existingNames = listNames(request.origin, request.folder).toSet()
                ensureCurrent(request)
                var outcome = authorize()
                ensureCurrent(request)
                if (outcome is AuthOutcome.NeedsResolution) {
                    if (!ownership.awaitAuthorization(request)) throw CancellationException("Photo request changed")
                    queuedEvents.send(request to PickerEvent.NeedsAuthResolution(outcome.pendingIntent))
                    outcome = request.authorization.await()
                    ensureCurrent(request)
                }
                val token = when (outcome) {
                    is AuthOutcome.Token -> outcome.accessToken
                    is AuthOutcome.Failed -> throw PickerException(0, outcome.message)
                    is AuthOutcome.NeedsResolution -> throw PickerException(0, "Google sign-in did not return an access token")
                }
                if (!ownership.creatingSession(request)) throw CancellationException("Photo request changed")
                // A blocking create may mint a session after cancellation. Retain its ID for cleanup.
                val session = withContext(NonCancellable) {
                    client.createSession(token).also { request.picker = token to it.id }
                }
                ensureCurrent(request)
                if (!ownership.pickerReady(request)) throw CancellationException("Photo request changed")
                queuedEvents.send(request to PickerEvent.OpenPicker(session.pickerUri))
                val adapter = request.returned.await()
                ensureCurrent(request)
                request.picker = null // importPickerPhotos now owns deletion, including cancellation.
                val result = importPickerPhotos(
                    client, token, session.id, adapter.cacheDir, existingNames,
                    waitMillis = 90_000,
                    upload = {
                        ensureCurrent(request)
                        adapter.upload(request.origin, request.folder, it)
                        ensureCurrent(request)
                    },
                    onProgress = {
                        ensureCurrent(request)
                        adapter.onProgress(it)
                    },
                )
                ensureCurrent(request)
                adapter.onComplete(result, request.origin, request.folder)
            } catch (e: CancellationException) {
                if (ownership.finish(request)) onCancelled()
                throw e
            } catch (e: Exception) {
                if (ownership.isCurrent(request)) onFailure(e.message ?: "Google Photos import failed")
            } finally {
                ownership.finish(request)
                request.picker?.let { (token, id) ->
                    request.picker = null
                    withContext(NonCancellable) { client.deleteSession(token, id) }
                }
            }
        }.also { job ->
            // launchForSession may reject a queued request before its body starts.
            job.invokeOnCompletion { ownership.finish(request) }
        }
        return true
    }

    fun onAuthResolved(readOutcome: () -> AuthOutcome): Boolean {
        val request = ownership.takeAuthorization() ?: return false
        request.authorization.complete(readOutcome())
        return true
    }

    fun onPickerReturned(
        cacheDir: File,
        upload: suspend (AdbSession, String, File) -> Unit,
        onProgress: (UploadSummaryState.Uploading) -> Unit = {},
        onComplete: suspend (UploadSummaryState.Done, AdbSession, String) -> Unit = { _, _, _ -> },
    ): Boolean {
        val request = ownership.takePicker() ?: return false
        request.returned.complete(ScreensaverPickerImport(cacheDir, upload, onProgress, onComplete))
        return true
    }

    fun cancel() {
        val request = ownership.active ?: return
        ownership.finish(request)
        request.job?.cancel()
    }

    private fun ensureCurrent(request: ScreensaverPickerRequest) {
        request.job?.ensureActive()
        if (!ownership.isCurrent(request)) throw CancellationException("Photo request changed")
    }
}

/**
 * Gestioneaza folderul de screensaver al Portalului: descopera folderele cu
 * imagini, masoara stocarea, incarca poze cu deduplicare si opereaza pe
 * continut (sterge/muta/trage). Inlocuieste vechiul PhotosViewModel.
 */
class ScreensaverViewModel(app: Application) : AndroidViewModel(app) {

    private val container = AppContainer.from(app)
    private val holder = container.connectionHolder
    private val screensaverManager = container.screensaverManager
    private val fileManager = container.fileManager
    private val historyStore = container.historyStore

    private val _state = MutableStateFlow(ScreensaverState())
    val state: StateFlow<ScreensaverState> = _state.asStateFlow()
    private val listings = ScreensaverListings(
        holder, viewModelScope, _state, PortalConnectionManager.PORTAL_PHOTOS_DIR,
        discover = { origin -> screensaverManager.findImageFolders(origin.dadb) },
        list = { origin, folder ->
            val contents = fileManager.list(origin.dadb, folder).filter { !it.isDirectory }
            holder.ensureCurrent(origin)
            val folderBytes = try {
                screensaverManager.folderBytes(origin.dadb, folder)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                0L
            }
            holder.ensureCurrent(origin)
            val freeBytes = try {
                screensaverManager.deviceFreeBytes(origin.dadb)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                0L
            }
            ScreensaverFolderListing(contents, folderBytes, freeBytes)
        },
    )

    private val _uploadState = MutableStateFlow<UploadSummaryState>(UploadSummaryState.Idle)
    val uploadState: StateFlow<UploadSummaryState> = _uploadState.asStateFlow()

    private val _notConnected = MutableStateFlow(false)
    val notConnected: StateFlow<Boolean> = _notConnected.asStateFlow()

    private var uploadJob: Job? = null

    // ── Google Photos import (Picker API) ─────────────────────────────────────────
    private val photosAuth = PhotosAuth(app)
    private val pickerClient = PhotosPickerClient(OkHttpPickerHttp())
    private val pickerFlow = ScreensaverPickerFlow(holder, viewModelScope, pickerClient) { origin, folder ->
        fileManager.list(origin.dadb, folder).map { it.name }.toSet()
    }
    val pickerEvents: Flow<PickerEvent> = pickerFlow.events

    private fun defaultFolder(): String = PortalConnectionManager.PORTAL_PHOTOS_DIR

    fun loadFolders() {
        val origin = holder.capture() ?: run { _notConnected.value = true; return }
        _notConnected.value = false
        listings.loadFolders(origin)
    }

    fun chooseFolder(context: Context, folder: String) {
        listings.chooseFolder(folder)
        refreshContents()
    }

    fun refreshContents() {
        val origin = holder.capture() ?: run { _notConnected.value = true; return }
        listings.refresh(origin)
    }

    fun toggleSelect(path: String) {
        if (_state.value.listingFailed) return
        val selection = _state.value.selection
        _state.value = _state.value.copy(
            selection = if (path in selection) selection - path else selection + path
        )
    }

    fun clearSelection() {
        _state.value = _state.value.copy(selection = emptySet())
    }

    fun deleteSelected(context: Context) {
        val d = holder.dadb.value ?: run { _notConnected.value = true; return }
        val targets = _state.value.contents.filter { it.path in _state.value.selection }
        if (targets.isEmpty()) return
        viewModelScope.launch {
            val result = runCatching { targets.forEach { fileManager.delete(d, it, advancedMode = false) } }
            reportBatch(context, result)
            clearSelection()
            refreshContents()
        }
    }

    fun moveSelected(context: Context, destDir: String) {
        val d = holder.dadb.value ?: run { _notConnected.value = true; return }
        val targets = _state.value.contents.filter { it.path in _state.value.selection }
        if (targets.isEmpty()) return
        viewModelScope.launch {
            val result = runCatching { targets.forEach { fileManager.move(d, it, destDir, advancedMode = false) } }
            reportBatch(context, result)
            clearSelection()
            refreshContents()
        }
    }

    fun pullSelected(context: Context) {
        val d = holder.dadb.value ?: run { _notConnected.value = true; return }
        val targets = _state.value.contents.filter { it.path in _state.value.selection }
        if (targets.isEmpty()) return
        viewModelScope.launch {
            val result = runCatching {
                targets.forEach { fileManager.pull(d, it, getApplication<Application>().cacheDir, advancedMode = false) }
            }
            reportBatch(context, result)
            clearSelection()
        }
    }

    /**
     * Incarca pozele selectate, sarind peste duplicate cu exceptia celor
     * fortate prin [overrideDuplicates] (dupa numele fisierului).
     */
    fun uploadPhotos(context: Context, uris: List<Uri>, overrideDuplicates: Set<String> = emptySet()) {
        val d = holder.dadb.value ?: run { _notConnected.value = true; return }
        if (uris.isEmpty()) return
        // Garda de reintrare: nu pornim o a doua incarcare peste una in curs,
        // altfel referinta uploadJob veche se pierde si nu mai poate fi anulata.
        if (uploadJob?.isActive == true || pickerFlow.isBusy) return
        val folder = _state.value.folder ?: defaultFolder()

        uploadJob = viewModelScope.launch {
            // Construieste harta nume->uri si perechile (nume, dimensiune) pentru dedupe.
            val picked = uris.map { uri ->
                val name = ContentUriHelper.getDisplayName(context, uri) ?: "photo_${uri.hashCode()}.jpg"
                val size = ContentUriHelper.getSize(context, uri)
                Triple(name, size, uri)
            }
            val existing = _state.value.contents
                .map { DedupeKey(it.name, it.sizeBytes) }
                .toSet()
            val plan = planUpload(
                picked = picked.map { it.first to it.second },
                existing = existing,
                overrides = overrideDuplicates
            )
            // Filtram dupa cheia completa (nume + dimensiune), nu doar nume:
            // altfel doua poze cu acelasi nume dar dimensiuni diferite s-ar
            // amesteca, iar un duplicat deja incarcat ar reintra in plan.
            val planKeys = plan.map { DedupeKey(it.first, it.second) }.toSet()
            val toUpload = picked.filter { DedupeKey(it.first, it.second) in planKeys }
            val skipped = picked.size - toUpload.size

            var uploaded = 0
            var failed = 0
            var lastError: String? = null

            toUpload.forEachIndexed { index, (name, size, uri) ->
                _uploadState.value = UploadSummaryState.Uploading(
                    current = index + 1,
                    total = toUpload.size,
                    progress = TransferProgress(0L, size)
                )
                val local = withContext(Dispatchers.IO) {
                    runCatching {
                        val input = context.contentResolver.openInputStream(uri) ?: return@runCatching null
                        val tmp = File(context.cacheDir, name)
                        tmp.outputStream().use { out -> input.copyTo(out) }
                        input.close()
                        tmp
                    }.getOrNull()
                }
                if (local == null) {
                    failed++
                    lastError = context.getString(R.string.error_read_photo_failed)
                    return@forEachIndexed
                }
                val pushResult = runCatching { fileManager.push(d, local, folder, advancedMode = false) }
                pushResult
                    .onSuccess {
                        uploaded++
                        _uploadState.value = UploadSummaryState.Uploading(
                            current = index + 1,
                            total = toUpload.size,
                            progress = TransferProgress(size, size)
                        )
                    }
                    .onFailure {
                        failed++
                        lastError = reason(context, it)
                    }
                local.delete()
            }

            _uploadState.value = UploadSummaryState.Done(uploaded = uploaded, skipped = skipped, failed = failed)
            historyStore.add(
                HistoryEntry(
                    timestamp = System.currentTimeMillis(),
                    description = context.getString(R.string.history_photo_upload, uploaded, toUpload.size),
                    success = failed == 0
                )
            )
            clearSelection()
            refreshContents()
        }
    }

    /**
     * Aduce local (in cache-ul de miniaturi) un fisier de pe Portal si intoarce
     * fisierul cache. Reutilizeaza fisierul deja descarcat daca exista deja o
     * miniatura valida pentru aceeasi (cale, data, dimensiune).
     */
    suspend fun thumbnailFile(file: RemoteFile): File? {
        val d = holder.dadb.value ?: return null
        return withContext(Dispatchers.IO) {
            val cacheDir = getApplication<Application>().cacheDir
            val key = com.portalinstaller.data.screensaver.ThumbnailCache.keyFor(
                file.path, file.modifiedAt, file.sizeBytes
            )
            val cached = com.portalinstaller.data.screensaver.ThumbnailCache.fileFor(cacheDir, key)
            if (cached.exists() && cached.length() > 0L) return@withContext cached
            val result = runCatching {
                d.pull(cached, file.path)
                com.portalinstaller.data.screensaver.ThumbnailCache.trim(cacheDir)
                cached
            }
            result.getOrNull()
        }
    }

    fun cancelUpload() {
        uploadJob?.cancel()
        uploadJob = null
        pickerFlow.cancel()
        _uploadState.value = UploadSummaryState.Idle
    }

    fun clearUploadState() {
        _uploadState.value = UploadSummaryState.Idle
    }

    // ── Google Photos import (Picker API) ─────────────────────────────────────────

    /**
     * Porneste importul din Google Photos: cere autorizarea, iar apoi fie deschide
     * direct sesiunea de picker (daca avem deja token), fie cere UI-ului sa lanseze
     * ecranul de consimtamant.
     */
    fun startGooglePhotosImport(context: Context) {
        val origin = holder.capture() ?: run { _notConnected.value = true; return }
        if (uploadJob?.isActive == true) return
        val appContext = context.applicationContext
        pickerFlow.start(
            origin = origin,
            folder = _state.value.folder ?: defaultFolder(),
            authorize = { photosAuth.authorize() },
            onFailure = {
                _uploadState.value = UploadSummaryState.Failed(appContext.getString(R.string.google_photos_error, it))
            },
            onCancelled = { _uploadState.value = UploadSummaryState.Idle },
        )
    }

    /** Continua dupa ecranul de consimtamant OAuth lansat de UI. */
    fun onAuthResolved(context: Context, data: Intent?) {
        pickerFlow.onAuthResolved { photosAuth.tokenFromResult(data) }
    }

    /**
     * Apelat cand utilizatorul se intoarce in aplicatie din picker. Interogheaza
     * sesiunea pana cand alegerea e gata, apoi descarca pozele alese si le impinge
     * pe Portal prin pipeline-ul existent (deduplicare dupa nume, progres, istoric).
     */
    fun onPickerReturned(context: Context) {
        val appContext = context.applicationContext
        pickerFlow.onPickerReturned(
            cacheDir = appContext.cacheDir,
            upload = { origin, folder, local ->
                holder.ensureCurrent(origin)
                fileManager.push(origin.dadb, local, folder, advancedMode = false, replaceExisting = false)
                Unit
            },
            onProgress = { _uploadState.value = it },
            onComplete = { result, origin, folder ->
                holder.ensureCurrent(origin)
                _uploadState.value = result
                if (result != UploadSummaryState.Done(0, 0, 0)) {
                    historyStore.add(
                        HistoryEntry(
                            timestamp = System.currentTimeMillis(),
                            description = appContext.getString(
                                R.string.history_photo_upload, result.uploaded, result.uploaded + result.failed
                            ),
                            success = result.failed == 0,
                        )
                    )
                    holder.ensureCurrent(origin)
                    if (_state.value.folder == null || _state.value.folder == folder) refreshContents()
                }
            },
        )
    }

    fun clearNotConnected() {
        _notConnected.value = false
    }

    fun onDisconnected() {
        listings.cancel()
        cancelUpload()
        _notConnected.value = false
        _state.value = ScreensaverState()
    }

    private fun reportBatch(context: Context, result: Result<Unit>) {
        _uploadState.value = result.fold(
            onSuccess = { UploadSummaryState.Idle },
            onFailure = { UploadSummaryState.Failed(reason(context, it)) }
        )
    }

    private fun reason(context: Context, t: Throwable): String =
        AdbErrors.formatReason(context, t.message, context.getString(R.string.error_unknown))

}
