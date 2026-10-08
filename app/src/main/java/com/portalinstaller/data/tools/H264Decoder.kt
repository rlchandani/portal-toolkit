package com.portalinstaller.data.tools

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue

/** Byte and item bounds apply together. Dropped reference frames require a fresh IDR. */
internal class BoundedNalQueue(
    private val maxBytes: Int = 4 * 1024 * 1024,
    private val maxItems: Int = 128,
    private val maxNalBytes: Int = 2 * 1024 * 1024
) {
    private val chunks = ArrayDeque<ByteArray>()
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var waitingForKeyframe = true
    var byteCount: Long = 0
        private set
    val size: Int get() = chunks.size

    fun offer(bytes: ByteArray) {
        val type = nalType(bytes)
        if (bytes.size > maxNalBytes || bytes.size > maxBytes || type < 0) {
            recover()
            return
        }
        if (type == 7 || type == 8) {
            if (bytes.size > 64 * 1024) { recover(); return }
            if (type == 7) sps = bytes else pps = bytes
            if (waitingForKeyframe) return
        }
        if (byteCount + bytes.size > maxBytes || size >= maxItems) recover()
        if (waitingForKeyframe) {
            if (type != 5) return
            val configs = listOfNotNull(sps, pps)
            if (configs.sumOf { it.size } + bytes.size > maxBytes || configs.size + 1 > maxItems) return
            configs.forEach(::add)
            waitingForKeyframe = false
        }
        add(bytes)
    }

    private fun add(bytes: ByteArray) { chunks.addLast(bytes); byteCount += bytes.size }
    fun poll(): ByteArray? = chunks.removeFirstOrNull()?.also { byteCount -= it.size }
    fun recover() { chunks.clear(); byteCount = 0; waitingForKeyframe = true }
    fun clear() { recover(); sps = null; pps = null }
}

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
    private var stopped = true
    // O unitate NAL de trimis, plus flag-urile MediaCodec (ex. CODEC_CONFIG pentru
    // SPS/PPS). Imutabil.
    private val pending = BoundedNalQueue()
    // Indecsii buffer-elor de intrare libere, oferite de codec prin callback.
    private val freeInputs = ConcurrentLinkedQueue<Int>()
    private val drainLock = Any()
    private var callbackThread: HandlerThread? = null

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
            codec = mc
            val thread = HandlerThread("portal-h264").also { it.start() }
            callbackThread = thread
            mc.setCallback(callback, Handler(thread.looper))
            mc.configure(format, surface, null, 0)
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
    fun submit(bytes: ByteArray, @Suppress("UNUSED_PARAMETER") codecConfig: Boolean = false) {
        synchronized(drainLock) {
            if (stopped || codec == null) return
            pending.offer(bytes)
            drain()
        }
    }

    fun recoverAtKeyframe() = synchronized(drainLock) { pending.recover() }

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
                break
            }
            try {
                val input = mc.getInputBuffer(index) ?: throw IllegalStateException("Missing codec input buffer")
                input.clear()
                require(chunk.size <= input.remaining()) { "NAL exceeds codec input capacity" }
                input.put(chunk)
                if (!stopped && codec != null) {
                    mc.queueInputBuffer(index, 0, chunk.size, 0,
                        if (isConfigNal(chunk)) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0)
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
            val c = codec
            stopped = true
            codec = null
            pending.clear()
            freeInputs.clear()
            c
        }
        _firstFrameRendered.value = false
        // Eliberam codec-ul fara drainLock: drain() vede stopped si iese imediat,
        // iar callback-urile in curs nu mai asteapta lock-ul. mc.release() asteapta
        // terminarea callback-urilor fara sa creeze deadlock.
        if (mc != null) {
            runCatching { mc.stop() }
            runCatching { mc.release() }
        }
        callbackThread?.quitSafely()
        callbackThread = null
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
            synchronized(drainLock) {
                if (stopped || codec !== mc) return
                freeInputs.add(index)
                drain()
            }
        }

        override fun onOutputBufferAvailable(
            mc: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            // render=true elibereaza cadrul direct pe Surface.
            synchronized(drainLock) {
                if (stopped || codec !== mc) return
                if (runCatching { mc.releaseOutputBuffer(index, true) }.isSuccess) {
                    _firstFrameRendered.value = true
                }
            }
        }

        override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
            if (!stopped && codec === mc) _error.value = e.message ?: "Eroare decodor"
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
