package com.portalinstaller.viewmodel

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.portalinstaller.R
import com.portalinstaller.data.AppContainer
import com.portalinstaller.data.tools.H264Decoder
import com.portalinstaller.data.tools.InputInjector
import com.portalinstaller.data.tools.MirrorUnsupportedException
import com.portalinstaller.data.tools.PortalKey
import com.portalinstaller.data.tools.ScreenCaptureManager
import com.portalinstaller.data.tools.ScreenMirrorManager
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    private val decoder = H264Decoder()
    val firstFrameRendered: StateFlow<Boolean> = decoder.firstFrameRendered

    private var mirrorJob: Job? = null
    private var currentSurface: Surface? = null
    private var deviceWidth = 0
    private var deviceHeight = 0

    init {
        viewModelScope.launch {
            holder.dadb.collect { d ->
                if (d == null) {
                    stopMirror()
                    if (currentSurface != null) {
                        _state.value = MirrorState.NotConnected
                    }
                } else {
                    _notConnected.value = false
                    val surface = currentSurface
                    if (surface != null && mirrorJob == null) {
                        startMirror(surface, d)
                    }
                }
            }
        }
    }

    /** Chemat cand SurfaceView-ul are o suprafata gata: pornim oglindirea. */
    fun onSurfaceAvailable(surface: Surface) {
        currentSurface = surface
        val d = holder.dadb.value
        if (d == null) {
            _state.value = MirrorState.NotConnected
            return
        }
        if (mirrorJob == null) {
            startMirror(surface, d)
        }
    }

    private fun startMirror(surface: Surface, d: Dadb) {
        if (mirrorJob != null) return
        _notConnected.value = false
        mirrorJob = viewModelScope.launch {
            _state.value = MirrorState.Starting
            try {
                val size = ScreenMirrorManager.deviceSize(d)
                deviceWidth = size.width
                deviceHeight = size.height
                decoder.start(surface, size.width, size.height)
                _state.value = MirrorState.Streaming
                launch { observeDecoderError() }
                launch { observeBattery() }
                ScreenMirrorManager.stream(d, size).collect { chunk ->
                    decoder.submit(chunk)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: MirrorUnsupportedException) {
                _state.value = MirrorState.Unsupported(
                    e.message ?: getApplication<Application>().getString(R.string.mirror_unsupported)
                )
                decoder.stop()
                mirrorJob = null
            } catch (e: Exception) {
                _state.value = MirrorState.Error(
                    e.message ?: getApplication<Application>().getString(R.string.error_unknown)
                )
                decoder.stop()
                mirrorJob = null
            }
        }
    }

    private suspend fun observeDecoderError() {
        decoder.error.collect { message ->
            if (message != null) {
                _state.value = MirrorState.Error(message)
                stopMirror()
            }
        }
    }

    private suspend fun observeBattery() {
        // Avertisment de baterie dupa 5 minute de oglindire continua.
        delay(BATTERY_WARN_AFTER_MS)
        _batteryWarning.value = true
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
        val d = holder.dadb.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { d.shell(command) }
        }
    }

    fun saveCurrentFrame(context: Context) {
        val d = holder.dadb.value
        if (d == null) {
            _notConnected.value = true
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val file = ScreenCaptureManager.capture(d, context.cacheDir)
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                        ?: throw IllegalStateException("Nu pot citi captura")
                    writeToMediaStore(context, bitmap)
                }
            }
            _saveState.value = result.fold(
                onSuccess = { MirrorSaveState.Saved },
                onFailure = { e ->
                    MirrorSaveState.Failed(e.message ?: context.getString(R.string.error_unknown))
                }
            )
        }
    }

    private fun writeToMediaStore(context: Context, bitmap: Bitmap) {
        val fileName = "portal_${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/PortalToolkit"
                )
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore insert failed")
        resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        } ?: throw IllegalStateException("MediaStore output stream unavailable")
    }

    fun clearSaveState() {
        _saveState.value = MirrorSaveState.Idle
    }

    fun clearNotConnected() {
        _notConnected.value = false
    }

    fun onDisconnected() {
        stopMirror()
        _notConnected.value = false
        _saveState.value = MirrorSaveState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        stopMirror()
    }

    private fun stopMirror() {
        mirrorJob?.cancel()
        mirrorJob = null
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
