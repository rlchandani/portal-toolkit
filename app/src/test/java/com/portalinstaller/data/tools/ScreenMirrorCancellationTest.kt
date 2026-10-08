package com.portalinstaller.data.tools

import dadb.AdbStream
import dadb.Dadb
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ScreenMirrorCancellationTest {
    @Test fun `cancelling blocked mirror reader closes its owned stream`() = runBlocking {
        val reading = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val closed = AtomicBoolean()
        val blockingSource = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                reading.complete(Unit)
                release.await(3, TimeUnit.SECONDS)
                return -1
            }
            override fun timeout() = Timeout.NONE
            override fun close() { release.countDown() }
        }.buffer()
        val stream = object : AdbStream {
            override val source = blockingSource
            override val sink = Buffer()
            override fun close() { closed.set(true); release.countDown() }
        }
        val dadb = Proxy.newProxyInstance(Dadb::class.java.classLoader, arrayOf(Dadb::class.java)) { _, method, _ ->
            check(method.name == "open")
            stream
        } as Dadb
        val job = launch(Dispatchers.Default) {
            ScreenMirrorManager.stream(dadb, MirrorSize(1280, 720)).collect {}
        }
        try {
            withTimeout(2_000) { reading.await() }
            job.cancel()
            withTimeout(1_000) { job.join() }
            assertTrue(closed.get())
        } finally {
            release.countDown()
            job.cancel()
            job.join()
        }
    }
}
