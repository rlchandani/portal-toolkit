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
    private val pending = ConcurrentLinkedQueue<ByteArray>()
    // Indecsii buffer-elor de intrare libere, oferite de codec prin callback.
    private val freeInputs = ConcurrentLinkedQueue<Int>()

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

    /**
     * Trimite din coada in orice buffer de intrare liber. Apelat de callback-ul
     * codec-ului (thread-ul intern MediaCodec) si de submit() (thread-ul coroutinei).
     * Verifica stopped si codec-ul inainte de queueInputBuffer ca sa evite folosirea
     * unui codec eliberat dupa stop().
     */
    private fun drain() {
        // Verificare timpurie: daca am oprit, renuntam imediat.
        if (stopped) return
        val mc = codec ?: return
        while (true) {
            // Verificam daca avem si index liber si chunk in coada INAINTE de poll().
            val index = freeInputs.peek() ?: break
            val chunk = pending.peek() ?: break
            // Acum extragem atomic: daca cineva a luat index-ul/chunk-ul intre timp,
            // vom detecta si ne vom opri.
            val actualIndex = freeInputs.poll() ?: break
            val actualChunk = pending.poll()
            if (actualChunk == null) {
                // Cineva a luat chunk-ul dupa peek(): punem indexul inapoi si iesim.
                freeInputs.add(actualIndex)
                break
            }
            // Reverifim stopped si codec inainte de a folosi codec-ul: daca stop()
            // a fost apelat intre timp, renuntam (evitam use-after-release).
            if (stopped || codec == null) {
                // Punem chunk-ul si indexul inapoi (nu s-au trimis).
                pending.add(actualChunk)
                freeInputs.add(actualIndex)
                break
            }
            try {
                val input = mc.getInputBuffer(actualIndex) ?: continue
                input.clear()
                input.put(actualChunk)
                // Ultima verificare inainte de queueInputBuffer: codec-ul trebuie sa fie inca activ.
                if (!stopped && codec != null) {
                    mc.queueInputBuffer(actualIndex, 0, actualChunk.size, 0, 0)
                } else {
                    // Oprit intre timp: chunk-ul si indexul sunt deja pierduti (codec-ul se inchide).
                }
            } catch (e: Exception) {
                // Daca nu suntem in shutdown curat (stopped=false), raportam eroarea.
                if (!stopped) {
                    _error.value = e.message ?: "Eroare trimitere cadru"
                }
                break
            }
        }
    }

    @Synchronized
    fun stop() {
        val mc = codec ?: return
        // Marcam stopped=true si punem codec=null SUB LOCK, apoi eliberam codec-ul
        // FARA LOCK ca sa evitam deadlock (callback-ul poate fi blocat asteptand
        // lock-ul in drain(), iar mc.release() asteapta ca callback-urile sa se termine).
        stopped = true
        codec = null
        pending.clear()
        freeInputs.clear()
        // Acum eliberam codec-ul FARA lock: drain() nu va mai folosi mc dupa ce vede
        // stopped=true sau codec=null. Orice callback in executie va vedea stopped=true
        // si va iesi fara sa apeleze queueInputBuffer.
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
}
