/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipOutputStream

class PortalBackupPreviewBudgetTest {
    private val manager = PortalBackupManager()
    private val manifest = """
        {"formatVersion":1,"createdAt":1700000000000,
        "device":{"manufacturer":"Étoile","model":"Portal 🏠","sdk":"28"},
        "settings":{"system.screen_brightness":"88","system.screen_off_timeout":"120000"},
        "apps":[{"packageName":"com.example.one","files":["base.apk"]},
        {"packageName":"com.example.two","files":["base.apk"]}]}
    """.trimIndent().toByteArray(Charsets.UTF_8)

    @Test
    fun `known oversized late entry is rejected before reading its body`() {
        val fixture = archive(
            "manifest.json" to manifest,
            "apps/com.example/base.apk" to byteArrayOf(7),
            stored = true
        )
        val headerOffset = fixture.headers.getValue("apps/com.example/base.apk")
        // Keep the real ZIP's local headers, but announce a body above the default
        // 3 GiB budget and omit it. Inspection must reject the size before reading.
        val bytes = fixture.bytes.copyOf(fixture.bodies.getValue("apps/com.example/base.apk"))
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(headerOffset + 18, (3L * 1024 * 1024 * 1024 + 1).toInt())
            putInt(headerOffset + 22, (3L * 1024 * 1024 * 1024 + 1).toInt())
        }
        val input = TrackedInput(bytes)
        val error = assertThrows(IllegalArgumentException::class.java) {
            manager.inspectBackup(input)
        }
        assertTrue(error.message.orEmpty().contains("larger than the supported limit"))
        assertTrue(input.closed)
    }

    @Test
    fun `ordinary archive returns UTF8 summary and closes input`() {
        val input = TrackedInput(archive(
            "apps/com.example.one/base.apk" to byteArrayOf(1, 2),
            "manifest.json" to manifest,
            "apps/com.example.two/base.apk" to byteArrayOf(3),
            "notes.txt" to "extra metadata".toByteArray(Charsets.UTF_8)
        ).bytes)

        assertEquals(
            PortalBackupPreview(1700000000000L, "Étoile", "Portal 🏠", "28", 2, 2),
            manager.inspectBackup(input)
        )
        assertTrue(input.closed)
    }

    @Test
    fun `unknown size ignored entry is accepted exactly at archive budget`() {
        // Default DEFLATED entries use data descriptors, so local headers do not
        // announce their decompressed size. The payload must actually be counted.
        val input = TrackedInput(archive(
            "manifest.json" to manifest,
            "unknown.bin" to ByteArray(32768)
        ).bytes)

        assertEquals(2, manager.inspectBackup(
            input, maxArchiveBytes = manifest.size.toLong() + 32768
        ).appCount)
        assertTrue(input.closed)
    }

    @Test
    fun `unknown size ignored entry one byte above archive budget is rejected`() {
        val input = TrackedInput(archive(
            "manifest.json" to manifest,
            "unknown.bin" to ByteArray(32769)
        ).bytes)

        assertArchiveBudgetRejected(input, manifest.size.toLong() + 32768)
        assertTrue(input.closed)
    }

    @Test
    fun `APK bytes and manifest share one budget regardless of manifest order`() {
        val apks = listOf(
            "apps/com.example.one/base.apk" to ByteArray(5000),
            "apps/com.example.two/base.apk" to ByteArray(7000)
        )
        listOf(listOf("manifest.json" to manifest) + apks, apks + ("manifest.json" to manifest))
            .forEach { entries ->
                val fixture = archive(*entries.toTypedArray())
                assertEquals(2, manager.inspectBackup(
                    ByteArrayInputStream(fixture.bytes),
                    maxArchiveBytes = manifest.size.toLong() + 12000
                ).appCount)
                assertArchiveBudgetRejected(
                    ByteArrayInputStream(fixture.bytes), manifest.size.toLong() + 11999
                )
            }
    }

    @Test
    fun `directory payload contributes to archive budget`() {
        val fixture = archive(
            "manifest.json" to manifest,
            "unexpected/" to ByteArray(8192)
        )

        assertEquals(2, manager.inspectBackup(
            ByteArrayInputStream(fixture.bytes),
            maxArchiveBytes = manifest.size.toLong() + 8192
        ).appCount)
        assertArchiveBudgetRejected(
            ByteArrayInputStream(fixture.bytes), manifest.size.toLong() + 8191
        )
    }

    @Test
    fun `zero budget rejects first nonempty unknown payload`() {
        assertArchiveBudgetRejected(
            ByteArrayInputStream(archive(
                "unknown.bin" to byteArrayOf(1),
                "manifest.json" to manifest
            ).bytes), 0
        )
    }

    @Test
    fun `invalid injected budgets reject without reading and close input`() {
        val bytes = archive("manifest.json" to manifest).bytes
        listOf(-1L, 3L * 1024 * 1024 * 1024 + 1, Long.MIN_VALUE, Long.MAX_VALUE)
            .forEach { budget ->
                val input = TrackedInput(bytes)
                assertThrows(IllegalArgumentException::class.java) {
                    manager.inspectBackup(input, maxArchiveBytes = budget)
                }
                assertEquals(0, input.position)
                assertTrue(input.closed)
            }
    }

    @Test
    fun `cancellation before traversal closes input without reading`() {
        val input = TrackedInput(archive("manifest.json" to manifest).bytes)
        val cancellation = CancellationException("preview cancelled")

        val error = assertThrows(CancellationException::class.java) {
            manager.inspectBackup(input, checkCancelled = { throw cancellation })
        }
        assertSame(cancellation, error)
        assertEquals(0, input.position)
        assertTrue(input.closed)
    }

    @Test
    fun `cancellation while APK payload is read stops promptly and closes input`() {
        val payload = ByteArray(1024 * 1024).also { Random(42).nextBytes(it) }
        val fixture = archive(
            "manifest.json" to manifest,
            "apps/com.example.one/base.apk" to payload
        )
        val input = TrackedInput(fixture.bytes)
        val cancelAfter = fixture.bodies.getValue("apps/com.example.one/base.apk") + 32768
        val cancellation = CancellationException("preview cancelled during payload")

        val error = assertThrows(CancellationException::class.java) {
            manager.inspectBackup(input, checkCancelled = {
                if (input.position >= cancelAfter) throw cancellation
            })
        }
        assertSame(cancellation, error)
        // A buffered source may read ahead by one 8192-byte buffer, but preview
        // must stop here rather than draining the remainder of the 1 MiB APK.
        assertTrue(input.position <= cancelAfter + 8192)
        assertTrue(input.closed)
    }

    @Test
    fun `highly compressed ignored payload checks cancellation during inflation`() {
        val input = TrackedInput(archive(
            "unknown.bin" to ByteArray(256 * 1024),
            "manifest.json" to manifest
        ).bytes)
        val cancellation = CancellationException("cancel compressed payload")
        var checkpoints = 0

        val error = assertThrows(CancellationException::class.java) {
            manager.inspectBackup(input, checkCancelled = {
                checkpoints++
                if (checkpoints == 20) throw cancellation
            })
        }
        assertSame(cancellation, error)
        assertTrue(input.closed)
    }

    @Test
    fun `manifest exactly one MiB is accepted and one extra byte is rejected`() {
        val limit = 1024 * 1024
        val atLimit = manifest + ByteArray(limit - manifest.size) { ' '.code.toByte() }
        assertEquals(2, manager.inspectBackup(
            ByteArrayInputStream(archive("manifest.json" to atLimit).bytes)
        ).appCount)
        val input = TrackedInput(archive(
            "manifest.json" to (atLimit + byteArrayOf(' '.code.toByte()))
        ).bytes)

        val error = assertThrows(IllegalArgumentException::class.java) {
            manager.inspectBackup(input)
        }
        assertTrue(error.message.orEmpty().contains("manifest is too large"))
        assertTrue(input.closed)
    }

    @Test
    fun `late corrupt payload is rejected after valid manifest`() {
        val fixture = archive(
            "manifest.json" to manifest,
            "late.apk" to byteArrayOf(1, 2, 3),
            stored = true
        )
        val bytes = fixture.bytes.copyOf()
        val bodyOffset = fixture.bodies.getValue("late.apk")
        bytes[bodyOffset] = (bytes[bodyOffset].toInt() xor 1).toByte()
        val input = TrackedInput(bytes)

        assertThrows(ZipException::class.java) { manager.inspectBackup(input) }
        assertTrue(input.closed)
    }

    @Test
    fun `late truncated payload is rejected after valid manifest`() {
        val payload = ByteArray(16384).also { Random(9).nextBytes(it) }
        val fixture = archive("manifest.json" to manifest, "late.apk" to payload)
        val input = TrackedInput(fixture.bytes.copyOf(fixture.bodies.getValue("late.apk") + 256))

        assertThrows(IOException::class.java) { manager.inspectBackup(input) }
        assertTrue(input.closed)
    }

    @Test
    fun `duplicate manifest cannot replace earlier summary`() {
        val fixture = archive(
            "manifest.json" to manifest,
            "manifest.jsox" to """{"formatVersion":1,"apps":[]}""".toByteArray(Charsets.UTF_8)
        )
        val input = TrackedInput(renameEntry(fixture.bytes, "manifest.jsox", "manifest.json"))

        val error = assertThrows(IllegalArgumentException::class.java) {
            manager.inspectBackup(input)
        }
        assertTrue(error.message.orEmpty().contains("multiple manifests"))
        assertTrue(input.closed)
    }

    @Test
    fun `duplicate ignored entry is rejected`() {
        val fixture = archive(
            "manifest.json" to manifest,
            "ignored.one" to byteArrayOf(1),
            "ignored.two" to byteArrayOf(2)
        )
        val input = TrackedInput(renameEntry(fixture.bytes, "ignored.two", "ignored.one"))

        assertThrows(IllegalArgumentException::class.java) { manager.inspectBackup(input) }
        assertTrue(input.closed)
    }

    @Test
    fun `five hundred entries are accepted and a later extra entry is rejected`() {
        val entries = listOf("manifest.json" to manifest) +
            (1..499).map { "empty-$it" to byteArrayOf() }
        assertEquals(2, manager.inspectBackup(
            ByteArrayInputStream(archive(*entries.toTypedArray()).bytes)
        ).appCount)
        val input = TrackedInput(archive(
            *(entries + ("one-too-many" to byteArrayOf())).toTypedArray()
        ).bytes)

        val error = assertThrows(IllegalArgumentException::class.java) {
            manager.inspectBackup(input)
        }
        assertTrue(error.message.orEmpty().contains("too many files"))
        assertTrue(input.closed)
    }

    private fun assertArchiveBudgetRejected(input: java.io.InputStream, budget: Long) {
        val error = assertThrows(IllegalArgumentException::class.java) {
            manager.inspectBackup(input, maxArchiveBytes = budget)
        }
        assertTrue(error.message.orEmpty().contains("larger than the supported limit"))
    }

    private data class Archive(
        val bytes: ByteArray,
        val headers: Map<String, Int>,
        val bodies: Map<String, Int>
    )

    private fun archive(vararg entries: Pair<String, ByteArray>, stored: Boolean = false): Archive {
        val output = ByteArrayOutputStream()
        val headers = mutableMapOf<String, Int>()
        val bodies = mutableMapOf<String, Int>()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, payload) ->
                headers[name] = output.size()
                zip.putNextEntry(ZipEntry(name).apply {
                    if (stored) {
                        method = ZipEntry.STORED
                        size = payload.size.toLong()
                        compressedSize = size
                        crc = CRC32().apply { update(payload) }.value
                    }
                })
                bodies[name] = output.size()
                zip.write(payload)
                zip.closeEntry()
            }
        }
        return Archive(output.toByteArray(), headers, bodies)
    }

    private fun renameEntry(bytes: ByteArray, from: String, to: String): ByteArray {
        val oldName = from.toByteArray(Charsets.UTF_8)
        val newName = to.toByteArray(Charsets.UTF_8)
        require(oldName.size == newName.size)
        return bytes.copyOf().also { result ->
            // Update both local and central directory names; payload CRCs remain
            // unchanged, and the result is a ZIP with repeated entry names.
            for (offset in 0..result.size - oldName.size) {
                if (oldName.indices.all { result[offset + it] == oldName[it] }) {
                    newName.copyInto(result, offset)
                }
            }
        }
    }

    private class TrackedInput(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        val position: Int get() = pos
        var closed = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }
}
