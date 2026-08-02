package com.portalinstaller.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectRetryTest {

    private fun retry() = ConnectRetry(maxAttempts = 3, retryDelayMs = 1_000)

    @Test
    fun `returns immediately on first success without delaying`() = runTest {
        var attempts = 0
        val delays = mutableListOf<Long>()
        val result = retry().run(
            isRetryable = { true },
            delayFn = { delays += it },
            attempt = { attempts++; Result.success("ok") }
        )
        assertEquals("ok", result.getOrNull())
        assertEquals(1, attempts)
        assertTrue(delays.isEmpty())
    }

    @Test
    fun `retries a retryable failure then succeeds`() = runTest {
        var attempts = 0
        val delays = mutableListOf<Long>()
        val result = retry().run(
            isRetryable = { true },
            delayFn = { delays += it },
            attempt = {
                attempts++
                // Esueaza de doua ori (socket picat la accept), reuseste a treia.
                if (attempts < 3) Result.failure(IOException("dropped")) else Result.success("ok")
            }
        )
        assertEquals("ok", result.getOrNull())
        assertEquals(3, attempts)
        assertEquals(listOf(1_000L, 1_000L), delays)
    }

    @Test
    fun `does not retry a non-retryable failure`() = runTest {
        var attempts = 0
        val delays = mutableListOf<Long>()
        val marker = IllegalStateException("not a portal")
        val result = retry().run<String>(
            isRetryable = { false },
            delayFn = { delays += it },
            attempt = { attempts++; Result.failure(marker) }
        )
        assertEquals(marker, result.exceptionOrNull())
        assertEquals(1, attempts)
        assertTrue(delays.isEmpty())
    }

    @Test
    fun `stops after maxAttempts and returns the last failure`() = runTest {
        var attempts = 0
        val delays = mutableListOf<Long>()
        val result = retry().run<String>(
            isRetryable = { true },
            delayFn = { delays += it },
            attempt = { attempts++; Result.failure(IOException("attempt $attempts")) }
        )
        assertEquals("attempt 3", result.exceptionOrNull()?.message)
        assertEquals(3, attempts)
        // Doua pauze intre cele trei incercari; niciuna dupa ultima.
        assertEquals(listOf(1_000L, 1_000L), delays)
    }
}
