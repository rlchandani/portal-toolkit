/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.photos

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** The application interceptor returns fixtures before any socket is opened. */
class OkHttpPickerHttpTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun transport(code: Int = 200, body: ResponseBody): OkHttpPickerHttp =
        OkHttpPickerHttp(OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("Bearer synthetic-token", chain.request().header("Authorization"))
            assertEquals("https://photos.invalid/image=d", chain.request().url.toString())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("fixture").body(body).build()
        }.build())

    private fun destination(): File = temporaryFolder.newFile("photo.jpg")

    @Test fun `successful download writes full body and closes it`() {
        var closed = false
        val bytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1)
        val body = object : ResponseBody() {
            override fun contentType(): MediaType = "image/jpeg".toMediaType()
            override fun contentLength(): Long = bytes.size.toLong()
            override fun source(): BufferedSource = Buffer().write(bytes)
            override fun close() { closed = true }
        }
        val dest = destination()

        transport(body = body).downloadTo("https://photos.invalid/image=d", "synthetic-token", dest)

        assertArrayEquals(bytes, dest.readBytes())
        assertTrue(closed)
    }

    @Test fun `empty image body is rejected`() {
        val dest = destination()
        val error = runCatching {
            transport(body = ByteArray(0).toResponseBody("image/jpeg".toMediaType()))
                .downloadTo("https://photos.invalid/image=d", "synthetic-token", dest)
        }.exceptionOrNull()

        assertTrue("An empty download must not become an uploaded photo", error is PickerException)
    }

    @Test fun `HTML error body with success status is rejected`() {
        val error = runCatching {
            transport(body = "<html>Try again</html>".toResponseBody("text/html".toMediaType()))
                .downloadTo("https://photos.invalid/image=d", "synthetic-token", destination())
        }.exceptionOrNull()

        assertTrue("A provider error page must not become an uploaded photo", error is PickerException)
    }

    @Test fun `truncated image body is rejected`() {
        val body = object : ResponseBody() {
            private val data = Buffer().write(byteArrayOf(0xff.toByte(), 0xd8.toByte()))
            override fun contentType(): MediaType = "image/jpeg".toMediaType()
            override fun contentLength(): Long = 100
            override fun source(): BufferedSource = data
        }
        val error = runCatching {
            transport(body = body).downloadTo("https://photos.invalid/image=d", "synthetic-token", destination())
        }.exceptionOrNull()

        assertTrue("A short response must not become an uploaded photo", error is PickerException)
    }

    @Test fun `expired download preserves HTTP code and does not overwrite destination`() {
        val dest = destination().apply { writeText("previous") }
        val error = runCatching {
            transport(code = 401, body = "expired".toResponseBody())
                .downloadTo("https://photos.invalid/image=d", "synthetic-token", dest)
        }.exceptionOrNull()

        assertTrue(error is PickerException)
        assertEquals(401, (error as PickerException).code)
        assertEquals("previous", dest.readText())
    }

    @Test fun `interrupted response removes staging and preserves previous local photo`() {
        var closed = false
        val data = Buffer().writeUtf8("partial")
        val input = object : Source {
            override fun timeout(): Timeout = Timeout.NONE
            override fun close() { closed = true }
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (data.size == 0L) throw IOException("connection lost")
                return data.read(sink, byteCount)
            }
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType(): MediaType = "image/jpeg".toMediaType()
            override fun contentLength(): Long = 100
            override fun source(): BufferedSource = input
        }
        val dest = destination().apply { writeText("previous") }

        assertTrue(runCatching {
            transport(body = body).downloadTo("https://photos.invalid/image=d", "synthetic-token", dest)
        }.exceptionOrNull() is IOException)

        assertEquals("previous", dest.readText())
        assertEquals(listOf(dest), temporaryFolder.root.listFiles()!!.toList())
        assertTrue(closed)
    }

    @Test fun `unknown length image response remains downloadable`() {
        val input = Buffer().writeUtf8("synthetic image")
        val body = object : ResponseBody() {
            override fun contentType(): MediaType = "image/jpeg".toMediaType()
            override fun contentLength(): Long = -1
            override fun source(): BufferedSource = input
        }
        val dest = destination()

        transport(body = body).downloadTo("https://photos.invalid/image=d", "synthetic-token", dest)

        assertEquals("synthetic image", dest.readText())
    }

    @Test fun `binary image response with MIME parameters remains downloadable`() {
        val dest = destination()

        transport(body = "synthetic image".toResponseBody("application/octet-stream; charset=binary".toMediaType()))
            .downloadTo("https://photos.invalid/image=d", "synthetic-token", dest)

        assertEquals("synthetic image", dest.readText())
    }
}
