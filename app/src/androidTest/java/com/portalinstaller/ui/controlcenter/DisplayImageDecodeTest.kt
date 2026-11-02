/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.controlcenter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Generated encoded images; real Android decoding and allocation, no Portal/owner photos. */
@RunWith(AndroidJUnit4::class)
class DisplayImageDecodeTest {
    @Test fun normalPortalSizedPhotoRetainsPixelsAndMeasuresDecodeCost() {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open("qa-display-512x384.jpg").use { it.readBytes() }
        val baseline = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        val sampled = requireNotNull(decodeCompanionDisplayBitmap(bytes, PHOTO_GRID_IMAGE_EDGE))
        try {
            assertEquals(512, sampled.width)
            assertEquals(384, sampled.height)
            assertTrue("Ordinary thumbnail pixels must remain identical", baseline.sameAs(sampled))
            assertEquals(baseline.allocationByteCount, sampled.allocationByteCount)
        } finally {
            baseline.recycle()
            sampled.recycle()
        }
        val oldSamples = JSONArray()
        val newSamples = JSONArray()
        repeat(5) {
            oldSamples.put(JSONObject(measure(bytes) { BitmapFactory.decodeByteArray(it, 0, it.size) }))
            newSamples.put(JSONObject(measure(bytes) {
                decodeCompanionDisplayBitmap(it, PHOTO_GRID_IMAGE_EDGE)
            }))
        }
        save(JSONObject().put("compressedBytes", bytes.size).put("pixelsIdentical", true)
            .put("baselineSamples", oldSamples).put("displaySamples", newSamples),
            "display-normal-decoding.json")
    }

    @Test fun largePhotoUsesDisplaySizedAllocations() {
        val bytes = largePhoto()
        assertTrue("Fixture fits the existing thumbnail transport limit", bytes.size < 512 * 1024)
        val baseline = measure(bytes) { BitmapFactory.decodeByteArray(it, 0, it.size) }
        val samples = JSONArray()
        for (edge in listOf(512, 1024, 256)) {
            val decoded = measure(bytes) { decodeCompanionDisplayBitmap(it, edge) }
            samples.put(JSONObject(decoded).put("maxEdge", edge))
            save(JSONObject().put("compressedBytes", bytes.size)
                .put("baseline", JSONObject(baseline)).put("displaySamples", samples))
            assertTrue("Display width exceeds $edge", decoded.getValue("width") <= edge)
            assertTrue("Display height exceeds $edge", decoded.getValue("height") <= edge)
            assertTrue("Bitmap allocation exceeds RGBA display budget",
                decoded.getValue("allocationBytes") <= edge.toLong() * edge * 4)
        }
    }

    @Test fun smallTransparentImageKeepsSizeAndAlpha() {
        val bytes = encoded(32, 16) { eraseColor(0x80785438.toInt()) }
        val bitmap = requireNotNull(decodeCompanionDisplayBitmap(bytes, 256))
        try {
            assertEquals(32, bitmap.width)
            assertEquals(16, bitmap.height)
            assertTrue(bitmap.hasAlpha())
            assertEquals(0x80, bitmap.getPixel(1, 1) ushr 24)
        } finally { bitmap.recycle() }
    }

    @Test fun oddDimensionsStayInsideTheDecodedBudget() {
        val bitmap = requireNotNull(decodeCompanionDisplayBitmap(encoded(1025, 513) {}, 512))
        try {
            assertTrue(bitmap.width <= 512)
            assertTrue(bitmap.height <= 512)
            assertTrue(bitmap.allocationByteCount <= 512 * 512 * 4)
        } finally { bitmap.recycle() }
    }

    @Test fun corruptEmptyAndInvalidTargetDoNotCreateAnImage() {
        assertNull(decodeCompanionDisplayBitmap(byteArrayOf(), 512))
        assertNull(decodeCompanionDisplayBitmap(byteArrayOf(1, 2, 3), 512))
        assertNull(decodeCompanionDisplayBitmap(largePhoto(), 0))
    }

    private fun largePhoto() = InstrumentationRegistry.getInstrumentation().context.assets
        .open("qa-display-4096x3072.jpg").use { it.readBytes() }

    private fun encoded(width: Int, height: Int, draw: Bitmap.() -> Unit): ByteArray =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).let { bitmap ->
            try {
                bitmap.draw()
                ByteArrayOutputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    it.toByteArray()
                }
            } finally { bitmap.recycle() }
        }

    private fun measure(bytes: ByteArray, decode: (ByteArray) -> Bitmap?): Map<String, Long> {
        val started = SystemClock.elapsedRealtimeNanos()
        val bitmap = requireNotNull(decode(bytes))
        return try {
            mapOf("width" to bitmap.width.toLong(), "height" to bitmap.height.toLong(),
                "allocationBytes" to bitmap.allocationByteCount.toLong(),
                "decodeNanos" to SystemClock.elapsedRealtimeNanos() - started)
        } finally { bitmap.recycle() }
    }

    private fun save(result: JSONObject, name: String = "display-image-decoding.json") {
        val directory = requireNotNull(InstrumentationRegistry.getInstrumentation()
            .targetContext.getExternalFilesDir("qa"))
        check(directory.isDirectory || directory.mkdirs())
        File(directory, name).writeText(result.toString(2))
    }
}
