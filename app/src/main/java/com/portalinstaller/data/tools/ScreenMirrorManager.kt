package com.portalinstaller.data.tools

import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Rezolutia dispozitivului asa cum o raporteaza `wm size`. */
data class MirrorSize(val width: Int, val height: Int)

/**
 * Deschide si detine stream-ul H.264 `screenrecord` de pe Portal si expune
 * octetii ca Flow. screenrecord are o limita de timp (o setam la 180s), deci
 * la sfarsit de stream redeschidem transparent in aceeasi bucla, ca sursa sa
 * para continua. Toata munca de I/O ruleaza pe Dispatchers.IO.
 */
object ScreenMirrorManager {

    private const val TIME_LIMIT_SECONDS = 180
    private const val CHUNK_SIZE = 64 * 1024
    private val SIZE_REGEX = Regex("""(\d+)x(\d+)""")

    /** Prefera "Override size" (rezolutia afisata), altfel "Physical size". */
    fun parseWmSize(output: String): MirrorSize? {
        val lines = output.lines().map { it.trim() }
        val override = lines.firstOrNull { it.startsWith("Override size:") }
        val physical = lines.firstOrNull { it.startsWith("Physical size:") }
        val chosen = override ?: physical ?: return null
        val m = SIZE_REGEX.find(chosen) ?: return null
        val w = m.groupValues[1].toIntOrNull() ?: return null
        val h = m.groupValues[2].toIntOrNull() ?: return null
        if (w <= 0 || h <= 0 || w > 8192 || h > 8192) return null
        return MirrorSize(w, h)
    }

    suspend fun deviceSize(dadb: Dadb): MirrorSize = withContext(Dispatchers.IO) {
        val resp = dadb.shell("wm size")
        parseWmSize(resp.allOutput)
            ?: throw MirrorUnsupportedException("Nu pot citi rezolutia (wm size)")
    }

    /**
     * Flow rece: la colectare deschide screenrecord si emite bucati de octeti
     * pana la anulare. La sfarsitul normal (limita de timp) redeschide. Inchide
     * mereu AdbStream-ul in finally. Daca screenrecord esueaza imediat, arunca
     * MirrorUnsupportedException.
     */
    fun stream(dadb: Dadb, size: MirrorSize): Flow<ByteArray> = flow {
        val command = "shell:screenrecord --output-format=h264 " +
            "--time-limit=$TIME_LIMIT_SECONDS " +
            "--size ${size.width}x${size.height} -"
        while (currentCoroutineContext().isActive) {
            val adbStream = dadb.open(command)
            coroutineScope {
                // A blocking Dadb read does not observe Job cancellation. A sibling IO
                // coroutine closes this stream as soon as its owner is cancelled.
                val closer = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { runCatching { adbStream.close() } }
                }
                try {
                    val source = adbStream.source
                    val buffer = ByteArray(CHUNK_SIZE)
                    var emittedAny = false
                    while (currentCoroutineContext().isActive) {
                        val read = source.read(buffer)
                        if (read == -1) break
                        if (read > 0) {
                            emittedAny = true
                            emit(buffer.copyOf(read))
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    if (!emittedAny) {
                        throw MirrorUnsupportedException("screenrecord nu a produs cadre")
                    }
                    emit(ByteArray(0)) // explicit recorder boundary for the Annex-B parser
                } finally {
                    closer.cancel()
                    runCatching { adbStream.close() }
                }
            }
        }
    }.flowOn(Dispatchers.IO).buffer(0)
}
