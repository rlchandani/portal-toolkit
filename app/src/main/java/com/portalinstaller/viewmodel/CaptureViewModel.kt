package com.portalinstaller.viewmodel

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.portalinstaller.R
import com.portalinstaller.data.AppContainer
import com.portalinstaller.data.tools.ScreenCaptureManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

sealed class CaptureSaveState {
    data object Idle : CaptureSaveState()
    data object Saved : CaptureSaveState()
    data class Failed(val message: String) : CaptureSaveState()
}

/**
 * Captura ecranului Portal: o data (buton) sau in bucla „peek live” (~1.5s).
 * Bucla sare peste un cadru nou daca transferul precedent inca ruleaza, ca sa
 * nu se acumuleze cereri. Dupa 5 minute de peek continuu ridicam un flag pentru
 * a avertiza asupra consumului de baterie. Salvarea foloseste MediaStore in
 * Pictures/PortalToolkit.
 */
class CaptureViewModel(app: Application) : AndroidViewModel(app) {

    private val holder = AppContainer.from(app).connectionHolder

    private val _frame = MutableStateFlow<Bitmap?>(null)
    val frame: StateFlow<Bitmap?> = _frame.asStateFlow()

    private val _isLive = MutableStateFlow(false)
    val isLive: StateFlow<Boolean> = _isLive.asStateFlow()

    private val _saveState = MutableStateFlow<CaptureSaveState>(CaptureSaveState.Idle)
    val saveState: StateFlow<CaptureSaveState> = _saveState.asStateFlow()

    private val _notConnected = MutableStateFlow(false)
    val notConnected: StateFlow<Boolean> = _notConnected.asStateFlow()

    private val _batteryWarning = MutableStateFlow(false)
    val batteryWarning: StateFlow<Boolean> = _batteryWarning.asStateFlow()

    private var liveJob: Job? = null
    private var transferring = false

    fun captureOnce() {
        val d = holder.dadb.value
        if (d == null) {
            _notConnected.value = true
            return
        }
        _notConnected.value = false
        viewModelScope.launch { grabFrame() }
    }

    fun toggleLive() {
        if (_isLive.value) {
            stopLive()
            return
        }
        val d = holder.dadb.value
        if (d == null) {
            _notConnected.value = true
            return
        }
        _notConnected.value = false
        _isLive.value = true
        var elapsedMs = 0L
        liveJob = viewModelScope.launch {
            while (coroutineContext.isActive && _isLive.value) {
                grabFrame()
                delay(LIVE_INTERVAL_MS)
                elapsedMs += LIVE_INTERVAL_MS
                if (elapsedMs >= BATTERY_WARN_AFTER_MS) _batteryWarning.value = true
            }
        }
    }

    private suspend fun grabFrame() {
        if (transferring) return
        val d = holder.dadb.value ?: return
        transferring = true
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val file = ScreenCaptureManager.capture(d, getApplication<Application>().cacheDir)
                BitmapFactory.decodeFile(file.absolutePath)
            }.getOrNull()
        }
        if (bitmap != null) _frame.value = bitmap
        transferring = false
    }

    fun saveCurrent(context: Context) {
        val bitmap = _frame.value ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { writeToMediaStore(context, bitmap) }
            }
            _saveState.value = result.fold(
                onSuccess = { CaptureSaveState.Saved },
                onFailure = { e ->
                    CaptureSaveState.Failed(
                        e.message ?: context.getString(R.string.error_unknown)
                    )
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
        _saveState.value = CaptureSaveState.Idle
    }

    fun clearNotConnected() {
        _notConnected.value = false
    }

    fun onDisconnected() {
        stopLive()
    }

    override fun onCleared() {
        super.onCleared()
        stopLive()
    }

    private fun stopLive() {
        _isLive.value = false
        liveJob?.cancel()
        liveJob = null
        _batteryWarning.value = false
    }

    companion object {
        const val LIVE_INTERVAL_MS = 1_500L
        const val BATTERY_WARN_AFTER_MS = 5 * 60 * 1_000L
    }
}
