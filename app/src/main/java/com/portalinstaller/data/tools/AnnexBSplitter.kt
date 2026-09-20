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
class AnnexBSplitter {

    private var buffer = ByteArray(0)

    /**
     * Adauga octeti noi si returneaza NAL-urile complete descoperite. Fiecare
     * NAL returnat este o copie proprie (imutabil pentru apelant).
     */
    fun feed(chunk: ByteArray): List<ByteArray> {
        if (chunk.isEmpty()) return emptyList()
        buffer += chunk

        val result = mutableListOf<ByteArray>()
        // Indexul primului cod de start din buffer (inceputul NAL-ului curent).
        var firstStart = indexOfStartCode(buffer, 0)
        if (firstStart < 0) {
            // Inca nu am vazut niciun cod de start: pastram doar coada care ar
            // putea fi inceputul unuia (cel mult 2 octeti 00 00).
            buffer = keepPossiblePrefix(buffer)
            return result
        }

        var cursor = firstStart
        while (true) {
            val next = indexOfStartCode(buffer, cursor + 3)
            if (next < 0) break
            result.add(normalize(buffer.copyOfRange(cursor, next)))
            cursor = next
        }
        // Pastram in buffer NAL-ul incomplet curent (de la cursor pana la capat).
        buffer = buffer.copyOfRange(cursor, buffer.size)
        return result
    }

    /**
     * Cauta un cod de start (00 00 01 sau 00 00 00 01) incepand de la [from] si
     * returneaza indexul primului octet 00 al secventei 00 00 01, sau -1.
     */
    private fun indexOfStartCode(data: ByteArray, from: Int): Int {
        var i = maxOf(from, 0)
        while (i + 2 < data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                return i
            }
            i++
        }
        return -1
    }

    /**
     * Cand nu exista inca niciun cod de start, pastram doar ultimii cel mult 2
     * octeti daca ar putea fi prefixul (00, sau 00 00) al unui cod care vine.
     */
    private fun keepPossiblePrefix(data: ByteArray): ByteArray {
        val n = data.size
        val tail = when {
            n >= 2 && data[n - 2].toInt() == 0 && data[n - 1].toInt() == 0 -> 2
            n >= 1 && data[n - 1].toInt() == 0 -> 1
            else -> 0
        }
        return if (tail == 0) ByteArray(0) else data.copyOfRange(n - tail, n)
    }

    /** Normalizeaza un NAL ca sa inceapa cu codul de start pe 3 octeti. */
    private fun normalize(nal: ByteArray): ByteArray {
        // Prefix pe 4 octeti 00 00 00 01 -> aruncam primul 00.
        if (nal.size >= 4 &&
            nal[0].toInt() == 0 && nal[1].toInt() == 0 &&
            nal[2].toInt() == 0 && nal[3].toInt() == 1
        ) {
            return nal.copyOfRange(1, nal.size)
        }
        return nal
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
