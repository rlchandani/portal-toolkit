/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.update

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtaHttpTest {
    private val feed = OtaPolicy.FEED_URL
    private val asset = "https://github.com/rlchandani/portal-toolkit/releases/download/toolkit-v1.9/portal-toolkit.apk"
    private val cdn = "https://release-assets.githubusercontent.com/github-production-release-asset/1?sig=x"

    private fun reply(chain: Interceptor.Chain, code: Int, body: ByteArray = ByteArray(0), location: String? = null): Response =
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x")
            .apply { if (location != null) header("Location", location) }
            .body(body.toResponseBody("application/octet-stream".toMediaType())).build()

    private fun http(seen: MutableList<String> = mutableListOf(), route: (Interceptor.Chain, String) -> Response) =
        OtaHttp(OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .addInterceptor { chain -> val url = chain.request().url.toString(); seen += url; route(chain, url) }.build())

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun tempFile() = File.createTempFile("ota", ".apk").also { it.deleteOnExit() }

    @Test fun followsGithubRedirectsToTheManifest() = runTest {
        val seen = mutableListOf<String>()
        val text = http(seen) { chain, url -> when (url) {
            feed -> reply(chain, 302, location = "/rlchandani/portal-toolkit/releases/download/toolkit-v1.9/portal-toolkit-latest.json")
            "https://github.com/rlchandani/portal-toolkit/releases/download/toolkit-v1.9/portal-toolkit-latest.json" -> reply(chain, 302, location = cdn)
            cdn -> reply(chain, 200, """{"schema":1}""".toByteArray())
            else -> error(url)
        } }.fetchText(feed, OtaPolicy.MAX_MANIFEST_BYTES)
        assertEquals("""{"schema":1}""", text)
        assertEquals(3, seen.size)
    }

    @Test fun refusesRedirectsOffGithub() = runTest {
        val error = runCatching {
            http { chain, _ -> reply(chain, 302, location = "https://evil.example/x") }.fetchText(feed, 100)
        }.exceptionOrNull()
        assertEquals(OtaCopy.BAD_SOURCE, (error as OtaException).message)
    }

    @Test fun stopsAfterTooManyRedirects() = runTest {
        val error = runCatching {
            http { chain, url -> reply(chain, 302, location = url) }.fetchText(feed, 100)
        }.exceptionOrNull()
        assertTrue(error is OtaException)
    }

    @Test fun missingReleaseSaysSo() = runTest {
        val error = runCatching { http { chain, _ -> reply(chain, 404) }.fetchText(feed, 100) }.exceptionOrNull()
        assertEquals(OtaCopy.NO_RELEASE, error?.message)
    }

    @Test fun serverErrorsAreReported() = runTest {
        val error = runCatching { http { chain, _ -> reply(chain, 503) }.fetchText(feed, 100) }.exceptionOrNull()
        assertEquals(OtaCopy.httpError(503), error?.message)
    }

    @Test fun oversizedManifestIsRefused() = runTest {
        val error = runCatching { http { chain, _ -> reply(chain, 200, ByteArray(200)) }.fetchText(feed, 100) }.exceptionOrNull()
        assertEquals(OtaCopy.BAD_MANIFEST, error?.message)
    }

    @Test fun downloadHashesWhileWritingAndReportsProgress() = runTest {
        val bytes = ByteArray(300_000) { (it % 251).toByte() }
        val out = tempFile()
        val progress = mutableListOf<Long>()
        val result = http { chain, url -> if (url == asset) reply(chain, 302, location = cdn) else reply(chain, 200, bytes) }
            .download(asset, out, bytes.size.toLong()) { progress += it }
        assertEquals(Downloaded(sha(bytes), bytes.size.toLong()), result)
        assertEquals(bytes.size.toLong(), out.length())
        assertEquals(bytes.size.toLong(), progress.last())
    }

    @Test fun downloadLargerThanAnnouncedIsStoppedAndDeleted() = runTest {
        val out = tempFile()
        val error = runCatching {
            http { chain, _ -> reply(chain, 200, ByteArray(1000)) }.download(asset, out, 10) {}
        }.exceptionOrNull()
        assertEquals(OtaCopy.TOO_LARGE, error?.message)
        assertFalse(out.exists())
    }

    @Test fun networkFailureIsFriendly() = runTest {
        val error = runCatching {
            http { _, _ -> throw java.net.UnknownHostException("github.com") }.fetchText(feed, 100)
        }.exceptionOrNull()
        assertEquals(OtaCopy.OFFLINE, error?.message)
    }
}
