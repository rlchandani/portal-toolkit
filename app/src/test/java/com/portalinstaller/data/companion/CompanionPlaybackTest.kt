/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.companion

import java.util.Locale
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompanionPlaybackTest {
    @Test fun missingStatusHasNoPlaybackControls() {
        for (status in listOf(null, JSONObject())) {
            val playback = CompanionPlayback.fromJson(status)
            assertFalse(playback.active)
            assertFalse(playback.playing)
            assertEquals("Nothing playing", playback.title)
            assertEquals(0L, playback.durationMs)
            assertEquals(0L, playback.positionMs)
            assertFalse(playback.canSeek)
            assertNull(playback.artVersion)
            assertNull(playback.volume)
        }
    }

    @Test fun activeStatusPreservesTrackMetadataAndAdvertisedControls() {
        val playback = CompanionPlayback.fromJson(JSONObject("""{
            "active":true,"playing":true,"title":"Night Train","artist":"Quartet","album":"Live",
            "durationMs":180000,"positionMs":42500,"canSeek":true,"hasArt":true,"artVersion":"cover-2",
            "volume":{"level":4,"max":15,"muted":true,"canSet":true}
        }"""))
        assertTrue(playback.active)
        assertTrue(playback.playing)
        assertEquals("Night Train", playback.title)
        assertEquals("Quartet", playback.artist)
        assertEquals("Live", playback.album)
        assertEquals(180_000L, playback.durationMs)
        assertEquals(42_500L, playback.positionMs)
        assertTrue(playback.canSeek)
        assertEquals("cover-2", playback.artVersion)
        assertEquals(PlaybackVolume(4, 15, muted = true, canSet = true), playback.volume)
    }

    @Test fun inactiveStatusDoesNotExposeStaleTrackOrSeekAndArtworkControls() {
        val playback = CompanionPlayback.fromJson(JSONObject("""{
            "active":false,"playing":true,"title":"Previous track","artist":"Previous artist",
            "album":"Previous album","durationMs":90000,"canSeek":true,"hasArt":true,"artVersion":12
        }"""))
        assertFalse(playback.active)
        assertFalse(playback.playing)
        assertEquals("Nothing playing", playback.title)
        assertEquals("", playback.artist)
        assertEquals("", playback.album)
        assertFalse(playback.canSeek)
        assertNull(playback.artVersion)
    }

    @Test fun olderStatusWithBlankTitleKeepsPlaybackWithoutInventingCapabilities() {
        val playback = CompanionPlayback.fromJson(JSONObject("""{"active":true,"playing":true,"title":"  "}"""))
        assertTrue(playback.active)
        assertTrue(playback.playing)
        assertEquals("Audio on Portal", playback.title)
        assertFalse(playback.canSeek)
        assertNull(playback.artVersion)
        assertNull(playback.volume)
    }

    @Test fun timelineBoundsPreventNegativeOrOutOfTrackSeekPositions() {
        data class Case(val duration: Long, val position: Long, val expectedDuration: Long, val expectedPosition: Long, val seek: Boolean)
        val cases = listOf(
            Case(-1, 50, 0, 0, false),
            Case(0, 50, 0, 0, false),
            Case(90_000, -1, 90_000, 0, true),
            Case(90_000, 120_000, 90_000, 90_000, true),
            Case(Long.MAX_VALUE, Long.MAX_VALUE, 604_800_000, 604_800_000, true),
        )
        for (case in cases) {
            val playback = CompanionPlayback.fromJson(JSONObject().put("active", true).put("canSeek", true)
                .put("durationMs", case.duration).put("positionMs", case.position))
            assertEquals(case.toString(), case.expectedDuration, playback.durationMs)
            assertEquals(case.toString(), case.expectedPosition, playback.positionMs)
            assertEquals(case.toString(), case.seek, playback.canSeek)
        }
        assertFalse(CompanionPlayback.fromJson(JSONObject("""{"active":true,"durationMs":90000}""")).canSeek)
    }

    @Test fun artworkRequiresAnAdvertisedNonNullVersionAndAcceptsNumericVersions() {
        val cases = listOf(
            """{"active":true,"hasArt":true,"artVersion":17}""" to "17",
            """{"active":true,"hasArt":true,"artVersion":"cover/v2"}""" to "cover/v2",
            """{"active":true,"hasArt":false,"artVersion":17}""" to null,
            """{"active":true,"artVersion":17}""" to null,
            """{"active":true,"hasArt":true}""" to null,
            """{"active":true,"hasArt":true,"artVersion":null}""" to null,
        )
        for ((status, expected) in cases) {
            assertEquals(status, expected, CompanionPlayback.fromJson(JSONObject(status)).artVersion)
        }
    }

    @Test fun invalidVolumeFallsBackWhileReadOnlyVolumeKeepsItsLevel() {
        for (volume in listOf(
            """{"level":4}""", """{"max":15}""", """{"level":0,"max":0}""",
            """{"level":-1,"max":15}""", """{"level":16,"max":15}""", """{"level":1,"max":1001}""",
            """{"level":4294967297,"max":15}""", """{"level":1,"max":4294967311}""",
        )) {
            assertNull(volume, CompanionPlayback.fromJson(JSONObject().put("active", true)
                .put("volume", JSONObject(volume))).volume)
        }
        val readOnly = CompanionPlayback.fromJson(JSONObject("""{
            "active":true,"volume":{"level":0,"max":15,"muted":true}
        }"""))
        assertEquals(PlaybackVolume(0, 15, muted = true, canSet = false), readOnly.volume)
    }

    @Test fun malformedVolumeIndicesOmitAbsoluteVolumeRatherThanCoercingValues() {
        for (field in listOf("level", "max")) {
            for (value in listOf("4", true, 4.5, 4.0, JSONObject.NULL)) {
                val volume = JSONObject().put("level", 1).put("max", 15).put("canSet", true)
                    .put(field, value)
                assertNull("$field=$value", CompanionPlayback.fromJson(JSONObject().put("active", true)
                    .put("volume", volume)).volume)
            }
        }
    }

    @Test fun integerAndLongVolumeIndicesPreserveAbsoluteVolumeCapability() {
        for ((level, max) in listOf(4 to 15, 4L to 15L)) {
            val volume = JSONObject().put("level", level).put("max", max).put("canSet", true)
            assertEquals(PlaybackVolume(4, 15, muted = false, canSet = true),
                CompanionPlayback.fromJson(JSONObject().put("active", true).put("volume", volume)).volume)
        }
    }

    @Test fun aZeroMaximumExplicitlyReportedByThePortalDisablesVolumeControls() {
        val playback = CompanionPlayback.fromJson(JSONObject("""{
            "active":false,"volume":{"level":0,"max":0,"muted":true,"canSet":false}
        }"""))
        assertEquals(PlaybackVolume(0, 0, muted = true, canSet = false), playback.volume)
    }

    @Test fun trackIdentityIgnoresProgressButChangesWithTrackSourceAndArtwork() {
        val status = JSONObject("""{
            "active":true,"playing":true,"source":"player-a","title":"Track","artist":"Artist",
            "album":"Album","durationMs":90000,"positionMs":1000,"hasArt":true,"artVersion":1
        }""")
        val original = CompanionPlayback.fromJson(status).trackKey
        assertEquals(original, CompanionPlayback.fromJson(JSONObject(status.toString())
            .put("positionMs", 2000).put("playing", false)).trackKey)
        for ((field, value) in listOf("title" to "Next track", "source" to "player-b", "artVersion" to "2")) {
            assertNotEquals(field, original, CompanionPlayback.fromJson(JSONObject(status.toString()).put(field, value)).trackKey)
        }
    }

    @Test fun timeFormattingUsesWholeSecondsAndDoesNotWrapHoursOrUseLocaleDigits() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar"))
            for ((ms, expected) in listOf(
                -1000L to "0:00",
                999L to "0:00",
                59_999L to "0:59",
                60_000L to "1:00",
                3_599_999L to "59:59",
                3_600_000L to "1:00:00",
                3_665_000L to "1:01:05",
                90_061_000L to "25:01:01",
            )) {
                assertEquals(ms.toString(), expected, playbackTime(ms))
            }
        } finally {
            Locale.setDefault(previous)
        }
    }
}
