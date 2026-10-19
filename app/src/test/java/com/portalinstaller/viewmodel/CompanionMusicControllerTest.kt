/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.viewmodel

import com.portalinstaller.data.companion.CompanionMusicAction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompanionMusicControllerTest {
    @Test fun repeatedRefreshesShareOneReadAndOldDeviceCannotPublish() = runTest {
        val old = CompletableDeferred<JSONObject?>()
        val reads = mutableListOf<String>()
        val controller = CompanionMusicController(backgroundScope,
            read = { key -> reads += key; if (key == "a:1") old.await() else music() },
            send = { _, _ -> error("No command expected") })
        controller.updateSession("a:1", true)
        repeat(30) { controller.refresh() }
        runCurrent()
        assertEquals(listOf("a:1"), reads)
        controller.updateSession("b:2", true)
        controller.refresh()
        runCurrent()
        old.complete(music("Old title"))
        runCurrent()
        assertEquals("b:2", controller.state.value.deviceKey)
        assertEquals("Song", controller.state.value.music?.track?.title)
        assertTrue(controller.state.value.fresh)
    }

    @Test fun admissionDoesNotBecomeConfirmationUntilMatchingReadback() = runTest {
        var latest = music()
        val sent = mutableListOf<JSONObject>()
        val controller = CompanionMusicController(backgroundScope, { latest }, { _, body ->
            sent += body
            latest = music(pending = """{"requestId":7,"command":"pause","startedAtElapsedMs":1}""")
            JSONObject("""{"ok":true,"admitted":true,"status":"admitted","music":${latest.getJSONObject("music")}}""")
        })
        controller.updateSession("a:1", true)
        controller.refresh().join()
        val observed = controller.state.value.music!!
        controller.command("a:1", observed, CompanionMusicAction.Transport("pause"))
        // Admission is synchronous, so a second tap cannot queue another write.
        controller.command("a:1", observed, CompanionMusicAction.Transport("pause"))
        runCurrent()
        assertEquals(1, sent.size)
        assertFalse(controller.state.value.feedback.orEmpty().contains("confirmed", ignoreCase = true))
        latest = music(playback = "paused",
            result = """{"requestId":7,"command":"pause","outcome":"confirmed"}""")
        controller.refresh().join()
        assertEquals("Change confirmed by the player.", controller.state.value.feedback)
        assertEquals("paused", controller.state.value.music?.playback)
    }

    @Test fun unrelatedTerminalResultCannotConfirmOurCommand() = runTest {
        var latest = music()
        val controller = CompanionMusicController(backgroundScope, { latest }, { _, _ ->
            latest = music(pending = """{"requestId":7,"command":"pause","startedAtElapsedMs":1}""")
            JSONObject("""{"ok":true,"admitted":true,"status":"admitted","music":${latest.getJSONObject("music")}}""")
        })
        controller.updateSession("a:1", true)
        controller.refresh().join()
        controller.command("a:1", controller.state.value.music!!, CompanionMusicAction.Transport("pause"))
        runCurrent()
        latest = music(playback = "paused",
            result = """{"requestId":8,"command":"pause","outcome":"confirmed"}""")
        controller.refresh().join()
        assertEquals("Latest state loaded; the previous change was not confirmed.", controller.state.value.feedback)
    }

    @Test fun oldRowAndOldPortalCannotDispatchToReplacementQueueOrDevice() = runTest {
        var latest = music()
        val sent = mutableListOf<JSONObject>()
        val controller = CompanionMusicController(backgroundScope, { latest }, { _, body ->
            sent += body
            JSONObject("""{"ok":true,"admitted":false,"status":"already_applied"}""")
        })
        controller.updateSession("a:1", true)
        controller.refresh().join()
        val old = controller.state.value.music!!
        latest = music(queueId = "other")
        controller.refresh().join()
        controller.command("a:1", old, CompanionMusicAction.QueueItem("item"))
        runCurrent()
        assertTrue(sent.isEmpty())
        controller.updateSession("b:2", true)
        controller.refresh().join()
        controller.command("a:1", controller.state.value.music!!, CompanionMusicAction.Transport("pause"))
        runCurrent()
        assertTrue(sent.isEmpty())
    }

    @Test fun hidingOrDisconnectingCancelsReadsAndNeverSendsPlay() = runTest {
        val waiting = CompletableDeferred<JSONObject?>()
        var sends = 0
        val controller = CompanionMusicController(backgroundScope, { waiting.await() }, { _, _ ->
            sends++; error("Must never resume on hide/reconnect")
        })
        controller.updateSession("a:1", true)
        controller.refresh()
        runCurrent()
        controller.stopObserving()
        assertFalse(controller.state.value.fresh)
        assertFalse(controller.state.value.reading)
        controller.updateSession("a:1", false)
        waiting.complete(music())
        runCurrent()
        assertNull(controller.state.value.music)
        assertEquals(0, sends)
    }

    @Test fun transportFailureRequiresFreshStateAndIsNeverReplayed() = runTest {
        var sends = 0
        val controller = CompanionMusicController(backgroundScope, { music() }, { _, _ ->
            sends++; throw java.io.IOException("Synthetic transport failure")
        })
        controller.updateSession("a:1", true)
        controller.refresh().join()
        controller.command("a:1", controller.state.value.music!!, CompanionMusicAction.Transport("pause"))
        runCurrent()
        assertEquals(1, sends)
        assertEquals("Could not confirm the change. Review the latest state before trying again.",
            controller.state.value.feedback)
        controller.refresh().join()
        assertEquals(1, sends)
    }

    @Test fun pendingReadbackHasADeadlineAndNeverReplaysTheWrite() = runTest {
        var latest = music()
        var reads = 0
        var writes = 0
        val controller = CompanionMusicController(backgroundScope, { reads++; latest }, { _, _ ->
            writes++
            latest = music(pending = """{"requestId":7,"command":"pause","startedAtElapsedMs":1}""")
            JSONObject("""{"ok":true,"admitted":true,"status":"admitted","music":${latest.getJSONObject("music")}}""")
        })
        controller.updateSession("a:1", true)
        controller.refresh().join()
        controller.command("a:1", controller.state.value.music!!, CompanionMusicAction.Transport("pause"))
        runCurrent()
        advanceTimeBy(12_001)
        runCurrent()
        assertEquals(1, writes)
        assertTrue(reads in 2..50)
        assertFalse(controller.state.value.fresh)
        assertEquals("The player did not confirm this change in time.", controller.state.value.feedback)
        val stoppedAt = reads
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(stoppedAt, reads)
    }

    @Test fun interruptionRechecksCapturedIntentAndAllowsOnlyExplicitPause() = runTest {
        var latest = music()
        val sent = mutableListOf<JSONObject>()
        val controller = CompanionMusicController(backgroundScope, { latest }, { _, body ->
            sent += body
            JSONObject("""{"ok":true,"admitted":false,"status":"already_applied"}""")
        })
        controller.updateSession("a:1", true)
        controller.refresh().join()
        val beforeInterruption = controller.state.value.music!!
        latest = music().apply { getJSONObject("music").put("audioOwnedByOther", true) }
        controller.refresh().join()
        controller.command("a:1", beforeInterruption, CompanionMusicAction.Transport("play"))
        runCurrent()
        assertTrue(sent.isEmpty())
        controller.refresh().join()
        val interrupted = controller.state.value.music!!
        controller.command("a:1", interrupted, CompanionMusicAction.Transport("pause"))
        runCurrent()
        assertEquals(1, sent.size)
        assertEquals("pause", sent.single().getString("command"))
        assertEquals(3L, sent.single().getLong("sourceRevision"))
        assertEquals("ma:family", sent.single().getString("sourceId"))
        assertEquals("item", sent.single().getString("trackId"))
    }

    @Test fun interruptedPauseCannotUseAReplacedSourceRevision() = runTest {
        var latest = music().apply { getJSONObject("music").put("audioOwnedByOther", true) }
        val sent = mutableListOf<JSONObject>()
        val controller = CompanionMusicController(backgroundScope, { latest }, { _, body ->
            sent += body
            JSONObject("""{"ok":true,"admitted":false,"status":"already_applied"}""")
        })
        controller.updateSession("a:1", true)
        controller.refresh().join()
        val captured = controller.state.value.music!!
        latest = music().apply {
            getJSONObject("music").put("audioOwnedByOther", true).put("sourceRevision", 4L)
        }
        controller.refresh().join()
        controller.command("a:1", captured, CompanionMusicAction.Transport("pause"))
        runCurrent()
        assertTrue(sent.isEmpty())
    }

    private fun music(
        title: String = "Song",
        queueId: String = "queue",
        playback: String = "playing",
        pending: String = "null",
        result: String = "null",
    ): JSONObject = JSONObject("""
        {"ok":true,"music":{"schemaVersion":1,"sourceRevision":3,"sourceId":"ma:family",
        "connection":"ready","playback":"$playback","pending":$pending,"lastCommand":$result,
        "audioOwnedByOther":false,"controlPlayerId":"ma:family","queueId":"$queueId",
        "track":{"id":"item","title":"$title","artist":"Artist","album":"","durationMs":30000,"isLive":false},
        "positionMs":5000,"capabilities":{"play":true,"pause":true,"next":true,"previous":true,
        "seek":true,"queue":true,"lyrics":false,"rooms":false,"volume":true,"selectPlayer":true},
        "queue":[{"id":"item","title":"Song","artist":"Artist","durationMs":30000,"available":true,"canSelect":true}],
        "rooms":[{"id":"ma:family","name":"Family Area","available":true,"volume":25,"muted":false,
        "groupId":null,"inCurrentGroup":true,"canSelect":true,"canJoin":false,"canRemove":false,"canSetVolume":true}],
        "lyrics":[]}}
    """.trimIndent())
}
