package com.portalinstaller.data

import kotlinx.coroutines.delay

/**
 * Politica (pura, testabila) de reincercare a conectarii ADB.
 *
 * Motiv: pe Android 10 (Portal), prima conectare afiseaza dialogul „Permiteti
 * depanarea USB?”. In timp ce dialogul e deschis, socket-ul dadb blocheaza pe
 * citirea de auth; cand utilizatorul apasa „Permite”, adbd renunta adesea la
 * acel socket in asteptare, deci prima incercare esueaza chiar dupa acceptare.
 * O reincercare scurta prinde cheia deja autorizata si conecteaza fara sa fie
 * nevoie de repornirea aplicatiei.
 *
 * [delayFn] si [attempt] sunt injectate ca sa putem testa determinist, fara
 * timp real si fara dadb.
 */
class ConnectRetry(
    private val maxAttempts: Int = 3,
    private val retryDelayMs: Long = 1_000
) {
    /**
     * Ruleaza [attempt] pana la [maxAttempts] ori. Reincearca doar cand
     * [isRetryable] intoarce true pentru eroarea primita (ex. timeout/socket
     * picat), nu pentru esecuri definitive (ex. „nu e un Portal”). Intre
     * incercari asteapta [retryDelayMs] prin [delayFn]. Intoarce ultimul
     * rezultat (succes sau esec).
     */
    suspend fun <T> run(
        isRetryable: (Throwable) -> Boolean,
        delayFn: suspend (Long) -> Unit = { delay(it) },
        attempt: suspend () -> Result<T>
    ): Result<T> {
        var last: Result<T> = Result.failure(IllegalStateException("Nicio incercare rulata."))
        repeat(maxAttempts) { index ->
            last = attempt()
            if (last.isSuccess) return last
            val error = last.exceptionOrNull()
            if (error == null || !isRetryable(error)) return last
            // Nu asteptam dupa ultima incercare.
            if (index < maxAttempts - 1) delayFn(retryDelayMs)
        }
        return last
    }
}
