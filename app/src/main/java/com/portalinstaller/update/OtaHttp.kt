/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.update

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** An update failure with a message that can be shown as is. */
class OtaException(message: String) : IOException(message)

/** The downloaded file's SHA-256 (lowercase hex) and its size in bytes. */
data class Downloaded(val sha256: String, val size: Long)

/**
 * GitHub Releases over HTTPS without auth. Redirects are followed by hand so every hop is checked
 * with [OtaPolicy.nextHop] (github.com → objects/release-assets.githubusercontent.com only).
 */
class OtaHttp(
    private val http: OkHttpClient = defaultClient(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** The body at [url] as text; at most [maxBytes]. */
    suspend fun fetchText(url: String, maxBytes: Long): String = withContext(io) {
        open(url).use { response ->
            val body = response.body ?: throw OtaException(OtaCopy.BAD_MANIFEST)
            val source = body.source()
            if (body.contentLength() > maxBytes || source.request(maxBytes + 1)) throw OtaException(OtaCopy.BAD_MANIFEST)
            source.readUtf8()
        }
    }

    /** Streams [url] into [destination], hashing as it goes; deletes the file on any failure. */
    suspend fun download(url: String, destination: File, expectedSize: Long, onProgress: (Long) -> Unit): Downloaded = withContext(io) {
        try {
            open(url).use { response -> write(response, destination, expectedSize, onProgress) }
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
    }

    private suspend fun write(response: Response, destination: File, expectedSize: Long, onProgress: (Long) -> Unit): Downloaded {
        val body = response.body ?: throw OtaException(OtaCopy.SIZE_MISMATCH)
        if (body.contentLength() > expectedSize) throw OtaException(OtaCopy.TOO_LARGE)
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        var reported = 0L
        body.byteStream().use { input ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    written += count
                    if (written > expectedSize) throw OtaException(OtaCopy.TOO_LARGE)
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                    if (written - reported >= PROGRESS_STEP_BYTES) { reported = written; onProgress(written) }
                }
            }
        }
        onProgress(written)
        return Downloaded(digest.digest().joinToString("") { "%02x".format(it) }, written)
    }

    /** A successful response for [start] after checked redirects; the caller closes it. */
    private fun open(start: String): Response {
        var url = start
        repeat(OtaPolicy.MAX_REDIRECTS + 1) {
            val response = try {
                http.newCall(Request.Builder().url(url).header("Accept", "application/octet-stream, application/json").build()).execute()
            } catch (error: OtaException) {
                throw error
            } catch (_: IOException) {
                throw OtaException(OtaCopy.OFFLINE)
            } catch (_: IllegalArgumentException) {
                throw OtaException(OtaCopy.BAD_SOURCE)
            }
            when {
                response.isSuccessful -> return response
                response.isRedirect -> {
                    val next = OtaPolicy.nextHop(url, response.header("Location"))
                    response.close()
                    url = next ?: throw OtaException(OtaCopy.BAD_SOURCE)
                }
                else -> {
                    response.close()
                    throw OtaException(if (response.code == NOT_FOUND) OtaCopy.NO_RELEASE else OtaCopy.httpError(response.code))
                }
            }
        }
        throw OtaException(OtaCopy.BAD_SOURCE)
    }

    companion object {
        private const val BUFFER_BYTES = 64 * 1024
        private const val PROGRESS_STEP_BYTES = 256L * 1024
        private const val NOT_FOUND = 404
        private const val CONNECT_SECONDS = 15L
        private const val READ_SECONDS = 60L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_SECONDS, TimeUnit.SECONDS).readTimeout(READ_SECONDS, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build()
    }
}
