package com.portalinstaller.data.photos

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher

/**
 * JVM tests for [PhotosPickerClient] using a scripted [PickerHttp] fake — no real network.
 * Verifies URL construction, Bearer token propagation, pagination and video filtering.
 */
class PhotosPickerClientTest {

    /** Records every call and returns responses chosen by a per-call handler. */
    private class FakeHttp(
        val handler: (method: String, url: String) -> HttpResult,
    ) : PickerHttp {
        data class Call(val method: String, val url: String, val token: String, val body: String?)

        val calls = mutableListOf<Call>()

        override fun get(url: String, token: String): HttpResult {
            calls += Call("GET", url, token, null)
            return handler("GET", url)
        }

        override fun post(url: String, token: String, jsonBody: String): HttpResult {
            calls += Call("POST", url, token, jsonBody)
            return handler("POST", url)
        }

        override fun delete(url: String, token: String): HttpResult {
            calls += Call("DELETE", url, token, null)
            return handler("DELETE", url)
        }

        override fun downloadTo(url: String, token: String, dest: File) {
            calls += Call("DOWNLOAD", url, token, null)
        }
    }

    @Test
    fun `createSession posts to sessions with bearer token and parses body`() = runTest {
        val fake = FakeHttp { _, _ ->
            HttpResult(200, """{ "id": "s1", "pickerUri": "https://pick/s1", "mediaItemsSet": false }""")
        }
        val client = PhotosPickerClient(fake)

        val session = client.createSession("TOKEN")

        assertEquals("s1", session.id)
        assertEquals(1, fake.calls.size)
        val call = fake.calls.single()
        assertEquals("POST", call.method)
        assertEquals("${PickerApi.BASE_URL}/sessions", call.url)
        assertEquals("TOKEN", call.token)
        assertEquals("{}", call.body)
    }

    @Test
    fun `getSession hits session endpoint and reports mediaItemsSet`() = runTest {
        val fake = FakeHttp { _, _ -> HttpResult(200, """{ "id": "s1", "mediaItemsSet": true }""") }
        val client = PhotosPickerClient(fake)

        val session = client.getSession("TOKEN", "s1")

        assertTrue(session.mediaItemsSet)
        assertEquals("${PickerApi.BASE_URL}/sessions/s1", fake.calls.single().url)
    }

    @Test
    fun `listAllPhotos follows pageToken across pages and drops videos`() = runTest {
        val page1 = """
            {
              "mediaItems": [
                { "id": "p1", "type": "PHOTO", "mediaFile": { "baseUrl": "https://b/p1", "filename": "a.jpg" } },
                { "id": "v1", "type": "VIDEO", "mediaFile": { "baseUrl": "https://b/v1", "filename": "clip.mp4" } }
              ],
              "nextPageToken": "PAGE2"
            }
        """.trimIndent()
        val page2 = """
            {
              "mediaItems": [
                { "id": "p2", "type": "PHOTO", "mediaFile": { "baseUrl": "https://b/p2", "filename": "b.jpg" } }
              ]
            }
        """.trimIndent()

        val fake = FakeHttp { _, url ->
            if (url.contains("pageToken=PAGE2")) HttpResult(200, page2) else HttpResult(200, page1)
        }
        val client = PhotosPickerClient(fake)

        val photos = client.listAllPhotos("TOKEN", "s1")

        assertEquals(listOf("p1", "p2"), photos.map { it.id })
        assertEquals(2, fake.calls.size)
        assertTrue(fake.calls[0].url.contains("sessionId=s1"))
        assertTrue(fake.calls[1].url.contains("pageToken=PAGE2"))
    }

    @Test
    fun `non-2xx response raises PickerException carrying the http code`() = runTest {
        val fake = FakeHttp { _, _ -> HttpResult(403, "forbidden") }
        val client = PhotosPickerClient(fake)

        try {
            client.getSession("TOKEN", "s1")
            fail("expected PickerException")
        } catch (e: PickerException) {
            assertEquals(403, e.code)
        }
    }

    @Test
    fun `deleteSession swallows failures so the flow is never blocked`() = runTest {
        val fake = FakeHttp { _, _ -> throw PickerException(0, "network down") }
        val client = PhotosPickerClient(fake)

        // Should not throw despite the underlying failure.
        client.deleteSession("TOKEN", "s1")

        assertEquals("DELETE", fake.calls.single().method)
    }

    @Test
    fun `downloadPhoto requests the full-resolution url`() = runTest {
        val fake = FakeHttp { _, _ -> HttpResult(200, "") }
        val client = PhotosPickerClient(fake)
        val item = PickedMediaItem("p1", "PHOTO", "https://b/p1", "image/jpeg", "a.jpg")

        client.downloadPhoto("TOKEN", item, File.createTempFile("picker", ".jpg").also { it.deleteOnExit() })

        val call = fake.calls.single()
        assertEquals("DOWNLOAD", call.method)
        assertEquals("https://b/p1=d", call.url)
    }

    @Test
    fun `pagination preserves opaque tokens containing query delimiters`() = runTest {
        val token = "next+/=&?# page"
        val fake = FakeHttp { _, url ->
            val query = url.toHttpUrl()
            assertEquals("session&1", query.queryParameter("sessionId"))
            when (query.queryParameter("pageToken")) {
                null -> HttpResult(200, """{"nextPageToken":"$token"}""")
                token -> HttpResult(200, """{"mediaItems":[{"id":"last","type":"PHOTO","mediaFile":{"baseUrl":"https://b/last","filename":"last.jpg"}}]}""")
                else -> error("Pagination token was changed")
            }
        }

        val photos = PhotosPickerClient(fake).listAllPhotos("synthetic-token", "session&1")

        assertEquals(listOf("last"), photos.map { it.id })
        assertEquals(2, fake.calls.size)
    }

    @Test
    fun `empty and video only pages do not end pagination early`() = runTest {
        val responses = ArrayDeque(listOf(
            HttpResult(200, """{"mediaItems":[],"nextPageToken":"second"}"""),
            HttpResult(200, """{"mediaItems":[{"id":"v","type":"VIDEO"}],"nextPageToken":"third"}"""),
            HttpResult(200, """{"mediaItems":[{"id":"p","type":"PHOTO","mediaFile":{"filename":"last.jpg","baseUrl":"https://b/p"}}],"nextPageToken":""}"""),
        ))
        val fake = FakeHttp { _, _ -> responses.removeFirst() }

        assertEquals(listOf("p"), PhotosPickerClient(fake).listAllPhotos("synthetic-token", "s").map { it.id })
        assertEquals(3, fake.calls.size)
    }

    @Test
    fun `expiry on a later page fails without returning a partial selection`() = runTest {
        for (code in listOf(401, 404, 410)) {
            val responses = ArrayDeque(listOf(
                HttpResult(200, """{"mediaItems":[{"id":"first","type":"PHOTO"}],"nextPageToken":"second"}"""),
                HttpResult(code, """{"error":{"message":"expired"}}"""),
            ))
            val fake = FakeHttp { _, _ -> responses.removeFirst() }

            val error = runCatching { PhotosPickerClient(fake).listAllPhotos("synthetic-token", "s") }.exceptionOrNull()

            assertTrue(error is PickerException)
            assertEquals(code, (error as PickerException).code)
            assertEquals(2, fake.calls.size)
        }
    }

    @Test
    fun `repeated continuation token fails instead of looping forever`() = runTest {
        var repeatedPages = 0
        val fake = FakeHttp { _, _ ->
            // Bound the reproduction even when the production guard is missing.
            if (++repeatedPages > 3) throw AssertionError("Repeated page was requested again")
            HttpResult(200, """{"nextPageToken":"same"}""")
        }

        val error = runCatching { PhotosPickerClient(fake).listAllPhotos("synthetic-token", "s") }.exceptionOrNull()

        assertTrue("Expected a pagination error, got $error", error is PickerException)
        assertEquals(2, fake.calls.size)
    }

    @Test
    fun `deleteSession propagates cancellation`() = runTest {
        val fake = FakeHttp { _, _ -> throw CancellationException("cancelled") }

        val error = runCatching { PhotosPickerClient(fake).deleteSession("synthetic-token", "s") }.exceptionOrNull()

        assertTrue(error is CancellationException)
    }

    @Test
    fun `malformed successful API responses become recoverable picker errors`() = runTest {
        for (body in listOf("not json", """{"mediaItems":[null]}""")) {
            val fake = FakeHttp { _, _ -> HttpResult(200, body) }
            val client = PhotosPickerClient(fake)
            val error = runCatching {
                if (body == "not json") client.createSession("synthetic-token")
                else client.listAllPhotos("synthetic-token", "s")
            }.exceptionOrNull()

            assertTrue("A malformed API response must reach the import error state", error is PickerException)
            assertEquals(0, (error as PickerException).code)
        }
    }

    @Test
    fun `cancellation after a page prevents fetching the next page`() = runTest {
        lateinit var job: Job
        val fake = FakeHttp { _, _ ->
            job.cancel()
            HttpResult(200, """{"nextPageToken":"second"}""")
        }
        val client = PhotosPickerClient(fake, StandardTestDispatcher(testScheduler))
        job = launch(start = CoroutineStart.LAZY) { client.listAllPhotos("synthetic-token", "s") }
        job.start()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(1, fake.calls.size)
    }

    @Test
    fun `invalid download URL fails before invoking the transport`() = runTest {
        for (url in listOf("", "not-a-url", "file:///private/photo.jpg", "http://photos.invalid/image")) {
            val fake = FakeHttp { _, _ -> HttpResult(200, "") }
            val item = PickedMediaItem("p", "PHOTO", url, "image/jpeg", "photo.jpg")
            val dest = File.createTempFile("invalid-picker", ".jpg")
            try {
                val error = runCatching { PhotosPickerClient(fake).downloadPhoto("synthetic-token", item, dest) }.exceptionOrNull()
                assertTrue("An invalid media URL must not reach a transport", error is PickerException)
                assertTrue(fake.calls.isEmpty())
            } finally {
                dest.delete()
            }
        }
    }
}
