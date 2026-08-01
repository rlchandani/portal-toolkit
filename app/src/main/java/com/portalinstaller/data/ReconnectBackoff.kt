package com.portalinstaller.data

/**
 * Programul (pur) de reconectare: fiecare încercare eșuată dublează întârzierea
 * pornind de la [baseMs], plafonat la [maxMs]. Fiind pur (fără stare, fără timp
 * real), se poate testa determinist.
 */
class ReconnectBackoff(
    val baseMs: Long = 2_000,
    val maxMs: Long = 30_000
) {
    /**
     * Întârzierea pentru încercarea [attempt] (1 = prima). Valori <= 0 sunt
     * tratate ca prima încercare, ca apelatorii să nu fie nevoiți să valideze.
     */
    fun delayFor(attempt: Int): Long {
        val safeAttempt = if (attempt < 1) 1 else attempt
        // 2^(attempt-1) poate exploda pentru attempt mari; plafonăm devreme
        // ca să evităm overflow-ul lui Long.
        if (safeAttempt >= Long.SIZE_BITS) return maxMs
        val scaled = baseMs shl (safeAttempt - 1)
        return if (scaled >= maxMs || scaled < baseMs) maxMs else scaled
    }
}
