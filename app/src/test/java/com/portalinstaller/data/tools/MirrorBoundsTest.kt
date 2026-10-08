package com.portalinstaller.data.tools

import org.junit.Assert.*
import org.junit.Test

class MirrorBoundsTest {
    @Test fun `decoder cannot accumulate unlimited submitted bytes`() {
        val queue = BoundedNalQueue()
        queue.offer(byteArrayOf(0, 0, 1, 0x65))
        repeat(200) { queue.offer(ByteArray(64 * 1024) { if (it == 2) 1 else if (it == 3) 0x41 else 0 }) }
        assertTrue("Queue retained ${queue.byteCount} bytes", queue.byteCount <= 4 * 1024 * 1024)
    }

    @Test fun `stopping unstarted decoder discards pending work`() {
        val decoder = H264Decoder()
        decoder.submit(byteArrayOf(0, 0, 1, 0x65))
        decoder.stop()
        val pending = H264Decoder::class.java.getDeclaredField("pending").apply { isAccessible = true }.get(decoder)
        val size = if (pending is Collection<*>) pending.size
        else pending.javaClass.getDeclaredMethod("getSize").invoke(pending) as Int
        assertEquals(0, size)
    }

    @Test fun `overflow discards dependent frames and resumes with cached config and IDR`() {
        val queue = BoundedNalQueue(maxBytes = 64, maxItems = 4)
        fun nal(type: Int) = byteArrayOf(0, 0, 1, type.toByte(), 2)
        queue.offer(nal(7))
        queue.offer(nal(8))
        queue.offer(nal(5))
        queue.offer(nal(1))
        queue.offer(nal(1)) // item overflow
        assertEquals(0, queue.size)
        queue.offer(nal(1))
        assertEquals(0, queue.size)
        queue.offer(nal(5))
        assertEquals(listOf(7, 8, 5), generateSequence { queue.poll() }.map(::nalType).toList())
        assertEquals(0L, queue.byteCount)
    }
    @Test fun `oversized incomplete NAL is discarded and parser recovers`() {
        val splitter = AnnexBSplitter()
        splitter.feed(byteArrayOf(0, 0, 1, 0x65))
        repeat(40) { splitter.feed(ByteArray(64 * 1024) { 2 }) }
        val result = splitter.feed(byteArrayOf(0, 0, 1, 0x67, 3, 0, 0, 1, 0x68))
        assertEquals(1, result.size)
        assertArrayEquals(byteArrayOf(0, 0, 1, 0x67, 3), result.single())
    }
}
