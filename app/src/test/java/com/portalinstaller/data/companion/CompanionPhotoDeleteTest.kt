/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.companion

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompanionPhotoDeleteTest {
    private val path = "/sdcard/Pictures/Immortal/selected image.png"
    private val session = CompanionSession(CompanionDevice("a", "A", "http://a:8723"), "fixture-token")

    private suspend fun rejected(body: String, status: Int = 200): CompanionException {
        val calls = mutableListOf<CompanionRequest>()
        val client = CompanionClient(CompanionTransport {
            calls += it
            CompanionResponse(status, body)
        })
        val failure = runCatching { client.deletePhoto(session, path) }.exceptionOrNull()
        assertTrue("An unconfirmed deletion must not succeed", failure is CompanionException)
        assertEquals("A deletion must never be replayed", 1, calls.size)
        assertEquals("DELETE", calls.single().method)
        assertEquals(path, calls.single().url.toHttpUrl().queryParameter("path"))
        assertEquals("fixture-token", calls.single().sessionToken)
        assertNull(calls.single().body)
        return failure as CompanionException
    }

    @Test fun literalSuccessForSelectedPathIsAccepted() = runTest {
        val client = CompanionClient(CompanionTransport {
            CompanionResponse(200, JSONObject().put("ok", true).put("path", path).toString())
        })
        assertEquals(path, client.deletePhoto(session, path).getString("path"))
    }

    @Test fun anotherPhotoCannotConfirmSelectedDeletion() = runTest {
        assertEquals("delete_unconfirmed", rejected("""{"ok":true,"path":"/sdcard/Pictures/Immortal/other.png"}""").error.code)
    }

    @Test fun missingPathCannotConfirmDeletion() = runTest {
        assertEquals("delete_unconfirmed", rejected("""{"ok":true}""").error.code)
    }

    @Test fun stringSuccessCannotConfirmDeletion() = runTest {
        assertEquals("delete_unconfirmed", rejected(JSONObject().put("ok", "true").put("path", path).toString()).error.code)
    }

    @Test fun uppercaseStringSuccessCannotConfirmDeletion() = runTest {
        assertEquals("delete_unconfirmed", rejected(JSONObject().put("ok", "TRUE").put("path", path).toString()).error.code)
    }

    @Test fun missingSuccessIsUnconfirmedRatherThanDefiniteRejection() = runTest {
        assertEquals("delete_unconfirmed", rejected(JSONObject().put("path", path).toString()).error.code)
    }

    @Test fun unreadablePositiveReplyIsUnconfirmed() = runTest {
        for (body in listOf("", "{", "false", "0", "\"\""))
            assertEquals("delete_unconfirmed", rejected(body).error.code)
    }

    @Test fun explicitRejectionAndUnauthorizedRemainServerErrors() = runTest {
        assertEquals("not_found", rejected("""{"ok":false,"error":"not_found"}""", 404).error.code)
        val denied = rejected("""{"ok":false,"error":"unauthorized"}""", 401)
        assertEquals("unauthorized", denied.error.code)
        assertEquals(401, denied.error.status)
        val malformed = rejected("{", 401)
        assertEquals("invalid_response", malformed.error.code)
        assertEquals(401, malformed.error.status)
    }

    @Test fun unconfirmedDeletionKeepsCurrentPhotosAndSessionWithoutRetry() = runTest {
        val calls = mutableListOf<CompanionRequest>()
        val transport = CompanionTransport { request ->
            calls += request
            val url = request.url.toHttpUrl()
            when (url.encodedPath) {
                "/remote/companion" -> CompanionResponse(200, """{"ok":true,"deviceId":"a","capabilities":{}}""")
                "/remote/nowplaying" -> CompanionResponse(200, """{"ok":true,"np":{}}""")
                "/remote/photos" -> CompanionResponse(200, JSONObject().put("ok", true).put("files",
                    org.json.JSONArray().put(JSONObject().put("name", "selected image.png").put("path", path)
                        .put("size", 77).put("modified", 1))).toString())
                "/remote/photo" -> CompanionResponse(200, """{"ok":true,"path":"/sdcard/Pictures/Immortal/other.png"}""")
                else -> throw AssertionError("Unexpected request")
            }
        }
        val store = object : CompanionSessionStore {
            override suspend fun load() = listOf(session)
            override suspend fun save(sessions: List<CompanionSession>) = Unit
        }
        val repository = CompanionRepository(CompanionClient(transport), store,
            backgroundScope, UnconfinedTestDispatcher(testScheduler), preloadManagement = false)
        repository.ready.join()
        repository.selectDevice("a").join()
        repository.listPhotos(CompanionPhotos.DEFAULT_FOLDER).join()
        calls.clear()
        repository.deletePhoto(path).join()
        assertEquals("delete_unconfirmed", repository.state.value.error?.code)
        assertEquals(listOf(path), repository.state.value.photos.map { it.path })
        assertEquals(CompanionConnection.CONNECTED, repository.state.value.connection)
        assertFalse(repository.state.value.busy)
        assertEquals(listOf("DELETE"), calls.map { it.method })
        repository.close()
    }
}
