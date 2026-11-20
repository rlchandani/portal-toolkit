/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.calls

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The sealed-chunk format of a video message, in transit and at rest. A message is split into
 * 64 KiB plaintext chunks; chunk *n* is AES-256-GCM with nonce `0000 ‖ n (8 bytes, big-endian)`
 * and AAD `msgId|n|final(0/1)`. The key is per message: in transit
 * `HMAC-SHA256(householdKey, "hearthpane-vmsg|" + msgId)`, at rest
 * `HMAC-SHA256(dataKey, "hearthpane-vmsg-rest|" + msgId)` (the data key is wrapped by Android
 * Keystore). Reordering, dropping, truncating at a chunk boundary or flipping a bit all fail the
 * tag. Pure JVM; streaming, so a 25 MB message never sits in memory. Byte-for-byte the Portal's
 * `CallMessageCrypto` (Hearthpane), so either side can open what the other sealed.
 */
object PhoneMessageCrypto {
    const val TAG_BYTES = 16
    private const val TAG_BITS = TAG_BYTES * 8
    private const val NONCE_BYTES = 12
    const val SEALED_CHUNK = PhoneMessageLimits.CHUNK + TAG_BYTES

    class Tampered(message: String) : java.io.IOException(message)

    fun transitKey(householdKey: ByteArray, msgId: String): ByteArray = hmac(householdKey, "hearthpane-vmsg|$msgId")

    fun restKey(dataKey: ByteArray, msgId: String): ByteArray = hmac(dataKey, "hearthpane-vmsg-rest|$msgId")

    fun chunkCount(plainBytes: Long): Int = maxOf(1L, (plainBytes + PhoneMessageLimits.CHUNK - 1) / PhoneMessageLimits.CHUNK).toInt()

    fun sealedLength(plainBytes: Long): Long = plainBytes + chunkCount(plainBytes).toLong() * TAG_BYTES

    /** Plaintext length of a sealed stream of [sealedBytes], or -1 if no plaintext could have that size. */
    fun plainLength(sealedBytes: Long): Long {
        if (sealedBytes <= TAG_BYTES) return -1
        val chunks = (sealedBytes + SEALED_CHUNK - 1) / SEALED_CHUNK
        val plain = sealedBytes - chunks * TAG_BYTES
        return if (plain > 0 && sealedLength(plain) == sealedBytes) plain else -1
    }

    fun sealChunk(key: ByteArray, msgId: String, index: Int, final: Boolean, plain: ByteArray, len: Int = plain.size): ByteArray =
            cipher(Cipher.ENCRYPT_MODE, key, msgId, index, final).doFinal(plain, 0, len)

    fun openChunk(key: ByteArray, msgId: String, index: Int, final: Boolean, sealed: ByteArray, len: Int = sealed.size): ByteArray =
            try { cipher(Cipher.DECRYPT_MODE, key, msgId, index, final).doFinal(sealed, 0, len) }
            catch (_: AEADBadTagException) { throw Tampered("chunk $index failed authentication") }

    /** Seals [plainBytes] of [input] chunk by chunk into [out]. Returns the sealed byte count. */
    fun seal(key: ByteArray, msgId: String, input: InputStream, plainBytes: Long, out: OutputStream): Long {
        require(plainBytes > 0)
        val count = chunkCount(plainBytes)
        val buf = ByteArray(PhoneMessageLimits.CHUNK)
        var left = plainBytes
        var written = 0L
        for (i in 0 until count) {
            val want = minOf(left, PhoneMessageLimits.CHUNK.toLong()).toInt()
            readFully(input, buf, want)
            val sealed = sealChunk(key, msgId, i, i == count - 1, buf, want)
            out.write(sealed)
            written += sealed.size
            left -= want
        }
        return written
    }

    /**
   * An OutputStream that accepts a sealed stream of exactly [sealedBytes] (as an HTTP body
   * arrives), opens each chunk under [key] and hands the verified plaintext chunk to [onChunk]
   * (index, final, bytes, length). [finish] fails unless every byte arrived.
   */
    class Opener(private val key: ByteArray, private val msgId: String, private val sealedBytes: Long,
                              private val onChunk: (Int, Boolean, ByteArray, Int) -> Unit) : OutputStream() {
        private val count: Int
        private val buf = ByteArray(SEALED_CHUNK)
        private var fill = 0
        private var index = 0
        private var total = 0L

        init {
            val plain = plainLength(sealedBytes)
            require(plain > 0) { "impossible sealed length" }
            count = chunkCount(plain)
        }

        private fun expected(): Int = if (index == count - 1) (sealedBytes - (count - 1).toLong() * SEALED_CHUNK).toInt() else SEALED_CHUNK

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off
            var l = len
            if (total + l > sealedBytes) throw Tampered("more bytes than announced")
            total += l
            while (l > 0) {
                if (index >= count) throw Tampered("data after the final chunk")
                val take = minOf(l, expected() - fill)
                System.arraycopy(b, o, buf, fill, take)
                fill += take; o += take; l -= take
                if (fill == expected()) {
                    val plain = openChunk(key, msgId, index, index == count - 1, buf, fill)
                    onChunk(index, index == count - 1, plain, plain.size)
                    index++; fill = 0
                }
            }
        }

        fun finish() { if (index != count || total != sealedBytes) throw Tampered("message truncated") }
    }

    /** Random-access plaintext reads from a sealed file (playback seeks); caches one chunk. */
    class Reader(file: File, private val key: ByteArray, private val msgId: String) : AutoCloseable {
        private val raf = RandomAccessFile(file, "r")
        private val sealedBytes = raf.length()
        val plainBytes: Long = plainLength(sealedBytes).also { if (it <= 0) { raf.close(); throw Tampered("bad sealed file length") } }
        private val count = chunkCount(plainBytes)
        private var cachedIndex = -1
        private var cached = ByteArray(0)
        private val sealed = ByteArray(SEALED_CHUNK)

        /** Reads up to [len] plaintext bytes at [position]; -1 at the end. */
        fun read(position: Long, dst: ByteArray, off: Int, len: Int): Int {
            if (position >= plainBytes) return -1
            val index = (position / PhoneMessageLimits.CHUNK).toInt()
            if (index != cachedIndex) load(index)
            val inChunk = (position - index.toLong() * PhoneMessageLimits.CHUNK).toInt()
            val n = minOf(len, cached.size - inChunk)
            System.arraycopy(cached, inChunk, dst, off, n)
            return n
        }

        private fun load(index: Int) {
            val start = index.toLong() * SEALED_CHUNK
            val size = minOf(SEALED_CHUNK.toLong(), sealedBytes - start).toInt()
            raf.seek(start)
            raf.readFully(sealed, 0, size)
            cached = openChunk(key, msgId, index, index == count - 1, sealed, size)
            cachedIndex = index
        }

        override fun close() = raf.close()
    }

    private fun cipher(mode: Int, key: ByteArray, msgId: String, index: Int, final: Boolean): Cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                val nonce = ByteBuffer.allocate(NONCE_BYTES).putInt(0).putLong(index.toLong()).array()
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                updateAAD("$msgId|$index|${if (final) 1 else 0}".toByteArray(Charsets.US_ASCII))
            }

    private fun hmac(key: ByteArray, text: String): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(text.toByteArray(Charsets.UTF_8))

    private fun readFully(input: InputStream, buf: ByteArray, want: Int) {
        var read = 0
        while (read < want) {
            val n = input.read(buf, read, want - read)
            if (n < 0) throw java.io.EOFException("message file shorter than expected")
            read += n
        }
    }
}
