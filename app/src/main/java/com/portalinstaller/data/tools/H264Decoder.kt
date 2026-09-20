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

    @Volatile
    private var codec: MediaCodec? = null
    @Volatile
    private var stopped = false
    // O unitate NAL de trimis, plus flag-urile MediaCodec (ex. CODEC_CONFIG pentru
    // SPS/PPS). Imutabil.
    private class InputChunk(val bytes: ByteArray, val flags: Int)
    private val pending = ConcurrentLinkedQueue<InputChunk>()
    // Indecsii buffer-elor de intrare libere, oferite de codec prin callback.
    private val freeInputs = ConcurrentLinkedQueue<Int>()
    private val drainLock = Any()

    @Synchronized
    fun start(surface: Surface, width: Int, height: Int) {
        if (codec != null) return
        stopped = false
        _firstFrameRendered.value = false
        _error.value = null
        pending.clear()
        freeInputs.clear()
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            // Un NAL de tip keyframe la 2160x1440 poate depasi cu mult 64 KB; cerem
            // un buffer de intrare suficient de mare ca sa incapa un cadru intreg.
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
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

    /**
     * Trimite o unitate NAL completa. [codecConfig] trebuie sa fie true pentru
     * SPS/PPS, ca decodorul sa le trateze drept configuratie.
     */
    fun submit(bytes: ByteArray, codecConfig: Boolean = false) {
        val flags = if (codecConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
        pending.add(InputChunk(bytes, flags))
        drain()
    }

    /**
     * Trimite din coada in orice buffer de intrare liber. Apelat de callback-ul
     * codec-ului (thread-ul intern MediaCodec) si de submit() (thread-ul coroutinei).
     * Serializat sub drainLock pentru a mentine ordinea trimiterii chunk-urilor.
     */
    private fun drain() = synchronized(drainLock) {
        if (stopped) return
        val mc = codec ?: return
        while (true) {
            val index = freeInputs.poll() ?: break
            val chunk = pending.poll()
            if (chunk == null) {
                freeInputs.add(index)
                break
            }
            if (stopped || codec == null) {
                pending.add(chunk)
                freeInputs.add(index)
                break
            }
            try {
                val input = mc.getInputBuffer(index) ?: continue
                input.clear()
                input.put(chunk.bytes)
                if (!stopped && codec != null) {
                    mc.queueInputBuffer(index, 0, chunk.bytes.size, 0, chunk.flags)
                }
            } catch (e: Exception) {
                if (!stopped) {
                    _error.value = e.message ?: "Eroare trimitere cadru"
                }
                break
            }
        }
    }

    @Synchronized
    fun stop() {
        val mc = synchronized(drainLock) {
            val c = codec ?: return
            stopped = true
            codec = null
            pending.clear()
            freeInputs.clear()
            c
        }
        // Eliberam codec-ul fara drainLock: drain() vede stopped si iese imediat,
        // iar callback-urile in curs nu mai asteapta lock-ul. mc.release() asteapta
        // terminarea callback-urilor fara sa creeze deadlock.
        runCatching { mc.stop() }
        runCatching { mc.release() }
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

    companion object {
        // 2 MB: acopera un keyframe la 2160x1440 cu marja confortabila.
        private const val MAX_INPUT_SIZE = 2 * 1024 * 1024
    }
}
