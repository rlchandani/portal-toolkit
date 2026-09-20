package com.portalinstaller.data.photos

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

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
}
