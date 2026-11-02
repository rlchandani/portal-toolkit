/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.companion

import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompanionClientTest {
    @Test fun inactiveSchemaReaderEncodesDomainAndPreservesAuthenticatedJsonChecks() = runTest {
        val calls = mutableListOf<CompanionRequest>()
        val client = CompanionClient(CompanionTransport {
            calls += it
            CompanionResponse(401, """{"ok":false,"error":"unauthorized"}""")
        })
        val session = CompanionSession(CompanionDevice("a", "A", "http://a:8723"), "fixture-token")
        val error = runCatching {
            client.settingsForValidation(session, "screensaver&includeInactive=0")
        }.exceptionOrNull() as CompanionException
        assertEquals(401, error.error.status)
        val request = calls.single()
        val url = request.url.toHttpUrl()
        assertEquals("/remote/settings", url.encodedPath)
        assertEquals("screensaver&includeInactive=0", url.queryParameter("domain"))
        assertEquals(listOf("1"), url.queryParameterValues("includeInactive"))
        assertEquals("GET", request.method)
        assertEquals("fixture-token", request.sessionToken)
        assertNull(request.body)
        assertFalse(request.url.contains("fixture-token"))
    }

    @Test fun inactiveSchemaReaderRejectsBlankDomainBeforeTransport() = runTest {
        val client = CompanionClient(CompanionTransport { throw AssertionError("No request expected") })
        val session = CompanionSession(CompanionDevice("a", "A", "http://a:8723"), "fixture-token")
        assertTrue(runCatching { client.settingsForValidation(session, " ") }
            .exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun preservesServerValidationDetailsAndRedactsCredentials() = runTest {
        val client = CompanionClient(CompanionTransport {
            CompanionResponse(400, """{"ok":false,"error":"validation_failed","errors":{"intervalSec":"range"},"token":"secret","password":"secret","nested":{"apiKey":"secret","code":"bad"}}""")
        })
        val failure = runCatching { client.post(CompanionSession(CompanionDevice("a", "A", "http://a:8723"), "token"), "settings", JSONObject()) }.exceptionOrNull() as CompanionException
        assertEquals(400, failure.error.status)
        assertEquals("range", failure.error.details!!.getJSONObject("errors").getString("intervalSec"))
        assertFalse(failure.error.details!!.toString().contains("secret"))
    }

    @Test fun successfulHttpButUndispatchedInputIsFailure() = runTest {
        val client = CompanionClient(CompanionTransport { CompanionResponse(200, """{"ok":true,"dispatched":false}""") })
        assertTrue(runCatching { client.post(CompanionSession(CompanionDevice("a", "A", "http://a:8723"), "token"), "tap", JSONObject()) }.exceptionOrNull() is CompanionException)
    }

    @Test fun realOkHttpBodyStreamsClosesInputAndCarriesBearerOnlyInHeader() = runTest {
        var closed = false
        var captured: okhttp3.Request? = null
        val progress = mutableListOf<Long>()
        val data = ByteArray(90_000) { (it % 255).toByte() }
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            captured = chain.request()
            val buffer = Buffer()
            chain.request().body!!.writeTo(buffer)
            assertArrayEquals(data, buffer.readByteArray())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"ok":true,"path":"/sdcard/Pictures/Immortal/a.jpg","bytes":90000}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val client = CompanionClient(OkHttpCompanionTransport(http, UnconfinedTestDispatcher(testScheduler)))
        val upload = CompanionUpload("a.jpg", "image/jpeg", data.size.toLong()) {
            object : ByteArrayInputStream(data) { override fun close() { closed = true; super.close() } }
        }
        client.uploadPhoto(CompanionSession(CompanionDevice("a", "A", "http://a:8723"), "private"), CompanionPhotos.DEFAULT_FOLDER, upload) { progress += it }
        assertTrue(closed)
        assertEquals("Bearer private", captured!!.header("Authorization"))
        assertFalse(captured!!.url.toString().contains("private"))
        assertEquals(90_000L, progress.last())
        assertTrue(progress.size > 1)
        assertTrue(captured!!.body!!.isOneShot())
    }

    @Test fun changedUploadLengthClosesStreamAndFailsBeforeSuccess() = runTest {
        var closed = false
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            chain.request().body!!.writeTo(Buffer())
            throw AssertionError("A short stream must fail before receiving a response")
        }.build()
        val client = CompanionClient(OkHttpCompanionTransport(http, UnconfinedTestDispatcher(testScheduler)))
        val upload = CompanionUpload("a.jpg", "image/jpeg", 7) {
            object : ByteArrayInputStream(byteArrayOf(1)) { override fun close() { closed = true; super.close() } }
        }
        assertTrue(runCatching { client.uploadPhoto(CompanionSession(CompanionDevice("a", "A", "http://a:8723"), "private"), CompanionPhotos.DEFAULT_FOLDER, upload) {} }.exceptionOrNull() is IOException)
        assertTrue(closed)
    }
}
