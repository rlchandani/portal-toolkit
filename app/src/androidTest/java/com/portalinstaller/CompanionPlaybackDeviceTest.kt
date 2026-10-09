/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.portalinstaller.data.companion.CompanionClient
import com.portalinstaller.data.companion.CompanionSession
import com.portalinstaller.data.companion.FileCompanionSessionStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Explicit opt-in: operate only on our silent VLC fixture, never on an owner's music session. */
@RunWith(AndroidJUnit4::class)
class CompanionPlaybackDeviceTest {
    private val client = CompanionClient()
    private lateinit var session: CompanionSession

    @Before fun fixtureOnly() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("playbackFixture") == "1")
        val sessions = FileCompanionSessionStore.create(instrumentation.targetContext).load()
        assumeTrue(sessions.size == 1)
        session = sessions.single()
        assertEquals(FIXTURE_TITLE, nowPlaying().getString("title"))
    }

    @Test fun activeVlcPublishesSeekCapabilityAndDecodableArtwork() = runBlocking {
        val np = nowPlaying()
        assertTrue(np.getBoolean("active"))
        assertTrue(np.getBoolean("canSeek"))
        assertTrue(np.getLong("durationMs") > 60_000)
        assertTrue(np.getBoolean("hasArt"))
        val bytes = client.mediaArtBytes(session, np.get("artVersion").toString())
        assertTrue(bytes.size in 1..512 * 1024)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertNotNull("Portal cover relay must be a decodable image", bitmap)
        bitmap!!.recycle()
    }

    @Test fun seekPauseResumeAndAbsoluteVolumeRoundTripAndRestore() = runBlocking {
        val original = nowPlaying()
        val originalVolume = original.getJSONObject("volume").getInt("level")
        val originallyMuted = original.getJSONObject("volume").getBoolean("muted")
        val originallyPlaying = original.getBoolean("playing")
        val originalPosition = original.getLong("positionMs")
        val max = original.getJSONObject("volume").getInt("max")
        assertTrue(original.getJSONObject("volume").getBoolean("canSet"))
        try {
            if (originallyPlaying) media("playpause")
            waitUntil { !it.getBoolean("playing") }
            val destination = original.getLong("durationMs") / 2
            media("seek", destination)
            waitUntil { abs(it.getLong("positionMs") - destination) < 2_000 }
            val targetVolume = if (originalVolume == max) (max - 1).coerceAtLeast(0) else originalVolume + 1
            client.post(session, "volume", JSONObject().put("dir", "set").put("level", targetVolume))
            assertEquals(targetVolume, nowPlaying().getJSONObject("volume").getInt("level"))
            media("playpause")
            waitUntil { it.getBoolean("playing") }
        } finally {
            val current = nowPlaying()
            if (current.getBoolean("playing")) media("playpause")
            media("seek", originalPosition)
            client.post(session, "volume", JSONObject().put("dir", "set").put("level", originalVolume))
            if (nowPlaying().getJSONObject("volume").getBoolean("muted") != originallyMuted)
                client.post(session, "volume", JSONObject().put("dir", "mute"))
            if (originallyPlaying) media("playpause")
            assertEquals(originalVolume, nowPlaying().getJSONObject("volume").getInt("level"))
            assertEquals(originallyMuted, nowPlaying().getJSONObject("volume").getBoolean("muted"))
        }
    }

    private suspend fun nowPlaying() = client.get(session, "nowplaying").getJSONObject("np")
    private suspend fun media(action: String, position: Long? = null) {
        val body = JSONObject().put("action", action)
        if (position != null) body.put("positionMs", position)
        assertTrue(client.post(session, "media", body).getBoolean("ok"))
    }
    private suspend fun waitUntil(predicate: (JSONObject) -> Boolean) {
        repeat(40) {
            if (predicate(nowPlaying())) return
            delay(100)
        }
        fail("Fixture playback did not acknowledge its transport command")
    }
    private companion object { const val FIXTURE_TITLE = "Companion playback fixture" }
}
