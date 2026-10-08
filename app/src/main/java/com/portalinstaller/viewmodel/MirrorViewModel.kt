package com.portalinstaller.viewmodel

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.portalinstaller.R
import com.portalinstaller.data.AppContainer
import com.portalinstaller.data.AdbSession
import com.portalinstaller.data.tools.AnnexBSplitter
import com.portalinstaller.data.tools.H264Decoder
import com.portalinstaller.data.tools.InputInjector
import com.portalinstaller.data.tools.isConfigNal
import com.portalinstaller.data.tools.MirrorUnsupportedException
import com.portalinstaller.data.tools.PortalKey
import com.portalinstaller.data.tools.ScreenCaptureManager
import com.portalinstaller.data.tools.ScreenMirrorManager
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class MirrorState {
    data object Idle : MirrorState()
    data object Starting : MirrorState()
    data object Streaming : MirrorState()
    data class Unsupported(val message: String) : MirrorState()
    data class Error(val message: String) : MirrorState()
    data object NotConnected : MirrorState()
}

sealed class MirrorSaveState {
    data object Idle : MirrorSaveState()
    data object Saved : MirrorSaveState()
    data class Failed(val message: String) : MirrorSaveState()
}

/**
 * Orchestreaza oglindirea ecranului Portal: rezolutie -> decodor -> stream H.264,
 * randat pe Surface-ul din UI. Evenimentele de control (tap/swipe/taste/text)
 * sunt trimise ca apeluri `shell()` scurte. Pastreaza si salvarea unui cadru ca
 * PNG (mostenit din vechiul CaptureViewModel) folosind ScreenCaptureManager.
 */
class MirrorViewModel(app: Application) : AndroidViewModel(app) {

    private val holder = AppContainer.from(app).connectionHolder

    private val _state = MutableStateFlow<MirrorState>(MirrorState.Idle)
    val state: StateFlow<MirrorState> = _state.asStateFlow()

    private val _saveState = MutableStateFlow<MirrorSaveState>(MirrorSaveState.Idle)
    val saveState: StateFlow<MirrorSaveState> = _saveState.asStateFlow()

    private val _notConnected = MutableStateFlow(false)
    val notConnected: StateFlow<Boolean> = _notConnected.asStateFlow()

    private val _batteryWarning = MutableStateFlow(false)
    val batteryWarning: StateFlow<Boolean> = _batteryWarning.asStateFlow()

    private var decoder = H264Decoder()
    private val _firstFrameRendered = MutableStateFlow(false)
    val firstFrameRendered: StateFlow<Boolean> = _firstFrameRendered.asStateFlow()
    private var mirrorSession: AdbSession? = null
    private var saveJob: Job? = null

    private var mirrorJob: Job? = null
    private var currentSurface: Surface? = null
    private var deviceWidth = 0
    private var deviceHeight = 0

    init {
        viewModelScope.launch {
            holder.session.collect { session ->
                if (session != mirrorSession) stopMirror()
                saveJob?.cancel()
                _saveState.value = MirrorSaveState.Idle
                if (session == null) {
                    stopMirror()
                    if (currentSurface != null) {
                        _state.value = MirrorState.NotConnected
                    }
                } else {
                    _notConnected.value = false
                    val surface = currentSurface
                    if (surface != null && mirrorJob == null) {
                        startMirror(surface, session)
                    }
                }
            }
        }
    }

    /** Chemat cand SurfaceView-ul are o suprafata gata: pornim oglindirea. */
    fun onSurfaceAvailable(surface: Surface) {
        currentSurface = surface
        val session = holder.capture()
        if (session == null) {
            _state.value = MirrorState.NotConnected
            return
        }
        if (mirrorJob == null) {
            startMirror(surface, session)
        }
    }

    private fun startMirror(surface: Surface, session: AdbSession) {
        if (mirrorJob != null) return
        val runDecoder = H264Decoder()
        decoder = runDecoder
        mirrorSession = session
        _firstFrameRendered.value = false
        _notConnected.value = false
        mirrorJob = holder.launchForSession(viewModelScope, session) {
            _state.value = MirrorState.Starting
            try {
                val size = ScreenMirrorManager.deviceSize(session.dadb)
                holder.ensureCurrent(session)
                deviceWidth = size.width
                deviceHeight = size.height
                runDecoder.start(surface, size.width, size.height)
                runDecoder.error.value?.let { throw IllegalStateException(it) }
                _state.value = MirrorState.Streaming
                val errorObserver = launch {
                    runDecoder.error.collect { message ->
                        if (message != null && mirrorSession == session) {
                            _state.value = MirrorState.Error(message)
                            stopMirror()
                        }
                    }
                }
                val frameObserver = launch {
                    runDecoder.firstFrameRendered.collect {
                        if (mirrorSession == session && holder.isCurrent(session)) _firstFrameRendered.value = it
                    }
                }
                val batteryObserver = launch {
                    delay(BATTERY_WARN_AFTER_MS)
                    if (mirrorSession == session) _batteryWarning.value = true
                }
                try {
                    withContext(Dispatchers.Default) {
                        val splitter = AnnexBSplitter(onDiscontinuity = runDecoder::recoverAtKeyframe)
                        ScreenMirrorManager.stream(session.dadb, size).collect { chunk ->
                            holder.ensureCurrent(session)
                            val nals = if (chunk.isEmpty()) splitter.finish() else splitter.feed(chunk)
                            for (nal in nals) runDecoder.submit(nal, codecConfig = isConfigNal(nal))
                            if (chunk.isEmpty()) runDecoder.recoverAtKeyframe()
                        }
                    }
                } finally {
                    errorObserver.cancel()
                    frameObserver.cancel()
                    batteryObserver.cancel()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: MirrorUnsupportedException) {
                if (holder.isCurrent(session) && mirrorSession == session) {
                    _state.value = MirrorState.Unsupported(e.message ?: "Mirroring unsupported")
                }
            } catch (e: Exception) {
                if (holder.isCurrent(session) && mirrorSession == session) {
                    _state.value = MirrorState.Error(e.message ?: "Mirror failed")
                }
            } finally {
                runDecoder.stop()
                if (mirrorSession == session) {
                    mirrorJob = null
                    _firstFrameRendered.value = false
                    _batteryWarning.value = false
                }
            }
        }
    }

    fun onSurfaceDestroyed() {
        currentSurface = null
        stopMirror()
    }

    fun tap(viewX: Float, viewY: Float, viewW: Int, viewH: Int) {
        if (!firstFrameRendered.value) return
        val (x, y) = InputInjector.mapPoint(viewX, viewY, viewW, viewH, deviceWidth, deviceHeight)
        fireAndForget(InputInjector.tapCommand(x, y))
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, viewW: Int, viewH: Int, durationMs: Int) {
        if (!firstFrameRendered.value) return
        val (dx1, dy1) = InputInjector.mapPoint(x1, y1, viewW, viewH, deviceWidth, deviceHeight)
        val (dx2, dy2) = InputInjector.mapPoint(x2, y2, viewW, viewH, deviceWidth, deviceHeight)
        fireAndForget(InputInjector.swipeCommand(dx1, dy1, dx2, dy2, durationMs))
    }

    fun longPress(viewX: Float, viewY: Float, viewW: Int, viewH: Int) {
        if (!firstFrameRendered.value) return
        val (x, y) = InputInjector.mapPoint(viewX, viewY, viewW, viewH, deviceWidth, deviceHeight)
        fireAndForget(InputInjector.longPressCommand(x, y))
    }

    fun sendKey(key: PortalKey) {
        if (!firstFrameRendered.value) return
        fireAndForget(InputInjector.keyeventCommand(key))
    }

    fun sendText(raw: String) {
        if (raw.isEmpty() || !firstFrameRendered.value) return
        fireAndForget(InputInjector.textCommand(raw))
    }

    /** Trimite o comanda scurta `shell()` fara sa astepte rezultatul. */
    private fun fireAndForget(command: String) {
        val session = mirrorSession ?: return
        val owner = mirrorJob ?: return
        if (!holder.isCurrent(session)) return
        holder.launchForSession(CoroutineScope(viewModelScope.coroutineContext + owner), session) {
            withContext(Dispatchers.IO) {
                holder.ensureCurrent(session)
                runCatching { session.dadb.shell(command) }
            }
        }
    }

    fun saveCurrentFrame(context: Context, outputUri: Uri? = null) {
        val session = mirrorSession ?: holder.capture()
        if (session == null || !holder.isCurrent(session)) {
            _notConnected.value = true
            return
        }
        if (saveJob?.isCompleted == false) return
        saveJob = holder.launchForSession(viewModelScope, session) {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val file = ScreenCaptureManager.capture(session.dadb, context.cacheDir)
                    try {
                        holder.ensureCurrent(session)
                        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                            ?: throw IllegalStateException("Nu pot citi captura")
                        try { writeToMediaStore(context, bitmap, outputUri) } finally { bitmap.recycle() }
                    } finally { file.delete() }
                }
            }
            holder.ensureCurrent(session)
            _saveState.value = result.fold(
                onSuccess = { MirrorSaveState.Saved },
                onFailure = { e ->
                    MirrorSaveState.Failed(e.message ?: context.getString(R.string.error_unknown))
                }
            )
        }
    }

    private suspend fun writeToMediaStore(context: Context, bitmap: Bitmap, outputUri: Uri?) {
        if (outputUri != null) {
            context.contentResolver.openOutputStream(outputUri)?.use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "PNG save failed" }
            } ?: throw IllegalStateException("Document output stream unavailable")
            currentCoroutineContext().ensureActive()
            return
        }
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { "Choose a document to save this screenshot" }
        val fileName = "portal_${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.IS_PENDING, 1)
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/PortalToolkit"
                )
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "PNG save failed" }
            } ?: throw IllegalStateException("MediaStore output stream unavailable")
            currentCoroutineContext().ensureActive()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                check(resolver.update(uri, ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }, null, null) > 0) { "Cannot publish screenshot" }
            }
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    fun clearSaveState() {
        _saveState.value = MirrorSaveState.Idle
    }

    fun clearNotConnected() {
        _notConnected.value = false
    }

    fun onDisconnected() {
        stopMirror()
        saveJob?.cancel()
        _notConnected.value = false
        _saveState.value = MirrorSaveState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        saveJob?.cancel()
        stopMirror()
    }

    private fun stopMirror() {
        mirrorJob?.cancel()
        mirrorJob = null
        mirrorSession = null
        _firstFrameRendered.value = false
        decoder.stop()
        _batteryWarning.value = false
        if (_state.value is MirrorState.Streaming || _state.value is MirrorState.Starting) {
            _state.value = MirrorState.Idle
        }
    }

    companion object {
        const val BATTERY_WARN_AFTER_MS = 5 * 60 * 1_000L
    }
}
