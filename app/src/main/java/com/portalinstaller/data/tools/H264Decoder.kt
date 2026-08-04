package com.portalinstaller.data.tools

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Decodor H.264 (video/avc) in mod asincron pe baza de callback. Primeste bucati
 * de octeti prin submit(), le pune intr-o coada si le trimite in buffer-ele de
 * intrare pe masura ce codec-ul le ofera (onInputBufferAvailable). Cadrele
 * decodate sunt eliberate direct pe Surface. Nu stie nimic despre ADB.
 */
class H264Decoder {

    private val _firstFrameRendered = MutableStateFlow(false)
    val firstFrameRendered: StateFlow<Boolean> = _firstFrameRendered.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var codec: MediaCodec? = null
    private val pending = ConcurrentLinkedQueue<ByteArray>()
    // Indecsii buffer-elor de intrare libere, oferite de codec prin callback.
    private val freeInputs = ConcurrentLinkedQueue<Int>()

    @Synchronized
    fun start(surface: Surface, width: Int, height: Int) {
        if (codec != null) return
        _firstFrameRendered.value = false
        _error.value = null
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            val mc = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            mc.setCallback(callback)
            mc.configure(format, surface, null, 0)
            codec = mc
            mc.start()
        } catch (e: Exception) {
            _error.value = e.message ?: "Eroare initializare decodor"
            stop()
        }
    }

    fun submit(bytes: ByteArray) {
        pending.add(bytes)
        drain()
    }

    /** Trimite din coada in orice buffer de intrare liber. */
    private fun drain() {
        val mc = codec ?: return
        while (true) {
            val index = freeInputs.poll() ?: break
            val chunk = pending.poll()
            if (chunk == null) {
                // Nimic de trimis: punem indexul inapoi si iesim.
                freeInputs.add(index)
                break
            }
            try {
                val input = mc.getInputBuffer(index) ?: continue
                input.clear()
                input.put(chunk)
                mc.queueInputBuffer(index, 0, chunk.size, 0, 0)
            } catch (e: Exception) {
                _error.value = e.message ?: "Eroare trimitere cadru"
                break
            }
        }
    }

    @Synchronized
    fun stop() {
        val mc = codec ?: return
        codec = null
        runCatching { mc.stop() }
        runCatching { mc.release() }
        pending.clear()
        freeInputs.clear()
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
            freeInputs.add(index)
            drain()
        }

        override fun onOutputBufferAvailable(
            mc: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            // render=true elibereaza cadrul direct pe Surface.
            runCatching { mc.releaseOutputBuffer(index, true) }
            if (!_firstFrameRendered.value) _firstFrameRendered.value = true
        }

        override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
            _error.value = e.message ?: "Eroare decodor"
        }

        override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) {
            // Nimic: randam pe Surface, nu ne intereseaza formatul de iesire.
        }
    }
}
