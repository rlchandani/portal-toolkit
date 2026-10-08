package com.portalinstaller.data.tools

/**
 * Imparte un flux Annex-B (H.264 de la screenrecord) in unitati NAL complete.
 * MediaCodec cere ca fiecare buffer de intrare sa contina un NAL complet,
 * aliniat la codul de start; altfel decodorul produce macroblocuri verzi si
 * rupturi. Fluxul soseste in bucati arbitrare de octeti (64 KB), deci acumulam
 * intr-un buffer si emitem un NAL de fiecare data cand gasim inceputul celui
 * urmator. Ultimul NAL ramane retinut pana soseste urmatorul cod de start.
 *
 * Fiecare NAL emis incepe cu codul de start pe 3 octeti (00 00 01); un prefix
 * pe 4 octeti (00 00 00 01) este normalizat la 3 (octetul 00 in plus e inofensiv
 * pentru decodor, dar il eliminam ca iesirea sa fie previzibila).
 */
class AnnexBSplitter(
    private val maxNalBytes: Int = 2 * 1024 * 1024,
    private val onDiscontinuity: () -> Unit = {}
) {
    init { require(maxNalBytes >= 4) }
    private var buffer = ByteArray(minOf(4096, maxNalBytes))
    private var size = 0
    private var zeros = 0
    private var inNal = false
    private var discarding = false
    val bufferedBytes: Int get() = size

    /**
     * Adauga octeti noi si returneaza NAL-urile complete descoperite. Fiecare
     * NAL returnat este o copie proprie (imutabil pentru apelant).
     */
    fun feed(chunk: ByteArray): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        for (byte in chunk) {
            if (byte.toInt() == 1 && zeros >= 2) {
                if (inNal && !discarding && size >= 4) {
                    result += buffer.copyOf(size)
                }
                size = 3
                buffer[0] = 0
                buffer[1] = 0
                buffer[2] = 1
                inNal = true
                discarding = false
                zeros = 0
                continue
            }
            // Keep possible delimiter bytes separately: a complete NAL can occupy
            // the entire limit without its following start code counting as payload.
            if (byte.toInt() == 0) {
                zeros = minOf(zeros + 1, maxNalBytes)
                continue
            }
            if (inNal && !discarding) {
                val required = size + zeros + 1
                if (required > maxNalBytes) {
                    size = 0
                    discarding = true
                    onDiscontinuity()
                } else {
                    while (required > buffer.size) {
                        buffer = buffer.copyOf(minOf(buffer.size * 2, maxNalBytes))
                    }
                    buffer.fill(0, size, size + zeros)
                    size += zeros
                    buffer[size++] = byte
                }
            }
            zeros = 0
        }
        return result
    }

    /** Flush only at a recorder boundary; never join NAL fragments across recorder processes. */
    fun finish(): List<ByteArray> {
        val result = if (inNal && !discarding && size >= 4) listOf(buffer.copyOf(size)) else emptyList()
        size = 0
        zeros = 0
        inNal = false
        discarding = false
        return result
    }
}

/** Tipul NAL: octetul de header (dupa 00 00 01) mascat cu 0x1F. */
fun nalType(nal: ByteArray): Int {
    // Header-ul este primul octet dupa codul de start pe 3 octeti.
    if (nal.size < 4) return -1
    return nal[3].toInt() and 0x1F
}

/** SPS (7) sau PPS (8): trebuie trimise cu BUFFER_FLAG_CODEC_CONFIG. */
fun isConfigNal(nal: ByteArray): Boolean = when (nalType(nal)) {
    7, 8 -> true
    else -> false
}
