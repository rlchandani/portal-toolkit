package com.portalinstaller.data.transfer

import okio.Buffer
import okio.Sink
import okio.Source
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CountingStreamsTest {
    @Test
    fun `counting source reports cumulative bytes and fraction`() {
        val payload = ByteArray(100) { it.toByte() }
        val progress = mutableListOf<TransferProgress>()
        // Buffer este si Source si Sink; tipul static forteaza extensia dorita.
        val raw: Source = Buffer().apply { write(payload) }
        val source = raw.counting(total = 100L) { progress.add(it) }
        val sink = Buffer()
        sink.writeAll(source)
        assertEquals(100L, progress.last().bytesTransferred)
        assertEquals(1.0f, progress.last().fraction, 0.0001f)
        assertTrue(progress.last().isDeterminate)
    }

    @Test
    fun `zero total is not determinate and never divides by zero`() {
        val p = TransferProgress(bytesTransferred = 5L, totalBytes = 0L)
        assertEquals(0f, p.fraction, 0.0001f)
        assertTrue(!p.isDeterminate)
    }

    @Test
    fun `counting sink reports bytes written`() {
        val progress = mutableListOf<TransferProgress>()
        val raw: Sink = Buffer()
        val sink = raw.counting(total = 50L) { progress.add(it) }.buffer()
        sink.write(ByteArray(50))
        sink.flush()
        assertEquals(50L, progress.last().bytesTransferred)
    }
}
