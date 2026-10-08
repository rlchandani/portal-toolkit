package com.portalinstaller.data.photos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for Google Photos Picker response parsing (uses org.json test dep). */
class PickerModelsTest {

    @Test
    fun `session parses fields and polling durations`() {
        val json = """
            {
              "id": "sess-123",
              "pickerUri": "https://photos.google.com/picker/sess-123",
              "mediaItemsSet": false,
              "pollingConfig": { "pollInterval": "5s", "timeoutIn": "600s" }
            }
        """.trimIndent()

        val s = PickerSession.parse(json)

        assertEquals("sess-123", s.id)
        assertEquals("https://photos.google.com/picker/sess-123", s.pickerUri)
        assertFalse(s.mediaItemsSet)
        assertEquals(5_000L, s.pollIntervalMillis)
        assertEquals(600_000L, s.timeoutMillis)
    }

    @Test
    fun `session without polling config falls back to defaults`() {
        val s = PickerSession.parse("""{ "id": "x", "mediaItemsSet": true }""")
        assertTrue(s.mediaItemsSet)
        assertEquals(PickerApi.DEFAULT_POLL_MILLIS, s.pollIntervalMillis)
        assertEquals(PickerApi.DEFAULT_TIMEOUT_MILLIS, s.timeoutMillis)
    }

    @Test
    fun `media items page parses items and next page token`() {
        val json = """
            {
              "mediaItems": [
                {
                  "id": "a",
                  "type": "PHOTO",
                  "mediaFile": { "baseUrl": "https://b/a", "mimeType": "image/jpeg", "filename": "IMG_1.jpg" }
                },
                {
                  "id": "b",
                  "type": "VIDEO",
                  "mediaFile": { "baseUrl": "https://b/b", "mimeType": "video/mp4", "filename": "VID_2.mp4" }
                }
              ],
              "nextPageToken": "TOK2"
            }
        """.trimIndent()

        val page = MediaItemsPage.parse(json)

        assertEquals(2, page.items.size)
        assertEquals("TOK2", page.nextPageToken)
        assertEquals("IMG_1.jpg", page.items[0].filename)
        assertTrue(page.items[0].isPhoto)
        assertFalse(page.items[1].isPhoto)
    }

    @Test
    fun `media item without filename falls back to id-based name`() {
        val json = """
            { "mediaItems": [ { "id": "z9", "type": "PHOTO", "mediaFile": { "baseUrl": "https://b/z" } } ] }
        """.trimIndent()
        val page = MediaItemsPage.parse(json)
        assertEquals("photo_z9.jpg", page.items[0].filename)
        assertNull(page.nextPageToken)
    }

    @Test
    fun `download url appends full-resolution suffix`() {
        assertEquals("https://b/a=d", PickerApi.downloadUrl("https://b/a"))
    }

    @Test
    fun `duration parsing handles fractional, blank and garbage`() {
        assertEquals(100L, parseDurationMillis("0.100s", 999L))
        assertEquals(3_500L, parseDurationMillis("3.5s", 999L))
        assertEquals(999L, parseDurationMillis(null, 999L))
        assertEquals(999L, parseDurationMillis("", 999L))
        assertEquals(999L, parseDurationMillis("abc", 999L))
    }

    @Test
    fun `invalid and overflowing polling durations use a bounded fallback`() {
        for (raw in listOf("0s", "-1s", "NaNs", "Infinitys", "1e300s", "5")) {
            assertEquals("duration=$raw", 999L, parseDurationMillis(raw, 999L))
        }
    }
}
