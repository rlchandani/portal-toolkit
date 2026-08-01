package com.portalinstaller.data.screensaver

/** Cheia de deduplicare a unei imagini: numele fisierului plus dimensiunea. */
data class DedupeKey(val name: String, val sizeBytes: Long)

/**
 * Imparte imaginile candidate in „noi” si „duplicate” fata de cele deja
 * incarcate pe Portal, comparand dupa nume + dimensiune. Doua fisiere cu
 * acelasi nume dar dimensiuni diferite sunt considerate distincte.
 */
object DedupeMatcher {
    fun partition(
        candidates: List<Pair<String, Long>>,
        existing: Set<DedupeKey>
    ): Pair<List<Pair<String, Long>>, List<Pair<String, Long>>> {
        val fresh = mutableListOf<Pair<String, Long>>()
        val duplicates = mutableListOf<Pair<String, Long>>()
        candidates.forEach { candidate ->
            val key = DedupeKey(candidate.first, candidate.second)
            if (key in existing) duplicates.add(candidate) else fresh.add(candidate)
        }
        return fresh to duplicates
    }
}
