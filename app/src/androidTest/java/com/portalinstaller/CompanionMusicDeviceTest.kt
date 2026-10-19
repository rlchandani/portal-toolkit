/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.portalinstaller.data.companion.CompanionClient
import com.portalinstaller.data.companion.CompanionException
import com.portalinstaller.data.companion.CompanionMusicAction
import com.portalinstaller.data.companion.commandBody
import com.portalinstaller.data.companion.CompanionRepository
import com.portalinstaller.data.companion.CompanionSession
import com.portalinstaller.data.companion.CompanionSessionStore
import com.portalinstaller.data.companion.FileCompanionSessionStore
import com.portalinstaller.viewmodel.CompanionMusicController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Saved credentials stay on the phone. Real writes require a separate explicit opt-in. */
@RunWith(AndroidJUnit4::class)
class CompanionMusicDeviceTest {
    @Test fun familyMusicUsesSavedPairingAndRejectsStaleVolumeCommand(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveMusic") == "1")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sessions = FileCompanionSessionStore.create(context).load()
        val family = sessions.singleOrNull { it.device.name == "Family Area" }
        assertNotNull("The existing Family Area pairing is required.", family)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val store = object : CompanionSessionStore {
            override suspend fun load() = sessions
            override suspend fun save(sessions: List<CompanionSession>) {}
            // Neither saved pairings nor multi-Portal targets are changed by this probe.
            override suspend fun saveSelectedIds(ids: Set<String>) {}
        }
        val repo = CompanionRepository(CompanionClient(), store, scope, preloadManagement = false)
        val controller = CompanionMusicController(scope, repo::readMusic, repo::sendMusic)
        try {
            repo.ready.join()
            repo.selectDevice(family!!.device.id).join()
            assertTrue("Family Area must be reachable using its existing pairing.", repo.state.value.connected)
            val key = "${repo.state.value.activeDeviceId}:${repo.state.value.sessionGeneration}"
            controller.updateSession(key, true)
            controller.refresh().join()
            val before = controller.state.value.music
            assertNotNull("The shipped phone parser must read the actual Portal response.", before)
            assertTrue(controller.state.value.fresh)
            assertEquals("ready", before!!.connection)
            assertNull(before.pending)
            val localRoom = before.rooms.single { it.id == before.sourceId }
            assertTrue(localRoom.canSetVolume)
            val oldVolume = localRoom.volume!!
            val stale = before.commandBody(CompanionMusicAction.Volume(localRoom.id, oldVolume))!!
                .put("sourceRevision", before.sourceRevision + 1)
            try {
                repo.sendMusic(key, stale)
                fail("A stale observed revision must never be admitted.")
            } catch (error: CompanionException) {
                assertEquals(409, error.error.status)
                assertEquals("stale", error.error.code)
            }
            controller.refresh().join()
            val after = controller.state.value.music!!
            assertEquals(before.sourceId, after.sourceId)
            assertEquals(before.queueId, after.queueId)
            assertEquals(before.track?.id, after.track?.id)
            assertNull(after.pending)
            assertEquals(before.rooms.associate { it.id to it.volume },
                after.rooms.associate { it.id to it.volume })
            assertEquals(before.rooms.associate { it.id to it.inCurrentGroup },
                after.rooms.associate { it.id to it.inCurrentGroup })
        } finally {
            controller.stopObserving()
            repo.close()
            scope.cancel()
        }
    }

    @Test fun familyVolumeReceivesCorrelatedConfirmationAndRestoresOnlyThatRoom(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveMusicWrite") == "1")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sessions = FileCompanionSessionStore.create(context).load()
        val family = requireNotNull(sessions.singleOrNull { it.device.name == "Family Area" })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val store = object : CompanionSessionStore {
            override suspend fun load() = sessions
            override suspend fun save(sessions: List<CompanionSession>) {}
            override suspend fun saveSelectedIds(ids: Set<String>) {}
        }
        val repo = CompanionRepository(CompanionClient(), store, scope, preloadManagement = false)
        val controller = CompanionMusicController(scope, repo::readMusic, repo::sendMusic)
        var key = ""
        var baseline: com.portalinstaller.data.companion.CompanionMusic? = null
        try {
            repo.ready.join()
            repo.selectDevice(family.device.id).join()
            assertTrue(repo.state.value.connected)
            key = "${repo.state.value.activeDeviceId}:${repo.state.value.sessionGeneration}"
            controller.updateSession(key, true)
            controller.refresh().join()
            baseline = requireNotNull(controller.state.value.music)
            assertTrue(controller.state.value.fresh)
            assertEquals("ready", baseline.connection)
            assertNull(baseline.pending)
            val local = baseline.rooms.single { it.id == baseline.sourceId }
            assertTrue(local.canSetVolume)
            val original = requireNotNull(local.volume)
            val changed = if (original == 0) 1 else original - 1
            commandAndConfirmVolume(controller, key, baseline, local.id, changed)
            val changedState = requireNotNull(controller.state.value.music)
            assertEquals(baseline.rooms.filter { it.id != local.id }.associate { it.id to it.volume },
                changedState.rooms.filter { it.id != local.id }.associate { it.id to it.volume })
            assertEquals(baseline.rooms.associate { it.id to it.inCurrentGroup },
                changedState.rooms.associate { it.id to it.inCurrentGroup })
        } finally {
            withContext(NonCancellable) {
                try {
                    val originalState = baseline
                    if (originalState != null) {
                        // Cleanup is an explicit restoration, never an automatic POST replay.
                        controller.stopObserving()
                        controller.refresh().join()
                        val latest = requireNotNull(controller.state.value.music)
                        assertEquals("Do not restore onto a replacement source.",
                            originalState.sourceId, latest.sourceId)
                        val roomId = originalState.sourceId
                        val original = requireNotNull(originalState.rooms.single { it.id == roomId }.volume)
                        if (latest.rooms.single { it.id == roomId }.volume != original) {
                            commandAndConfirmVolume(controller, key, latest, roomId, original)
                        }
                        val restored = requireNotNull(controller.state.value.music)
                        assertEquals(originalState.rooms.associate { it.id to it.volume },
                            restored.rooms.associate { it.id to it.volume })
                        assertEquals(originalState.rooms.associate { it.id to it.inCurrentGroup },
                            restored.rooms.associate { it.id to it.inCurrentGroup })
                        assertEquals(originalState.queueId, restored.queueId)
                        assertEquals(originalState.track?.id, restored.track?.id)
                        assertNull(restored.pending)
                    }
                } finally {
                    controller.stopObserving()
                    repo.close()
                    scope.cancel()
                }
            }
        }
    }

    private suspend fun commandAndConfirmVolume(
        controller: CompanionMusicController,
        key: String,
        observed: com.portalinstaller.data.companion.CompanionMusic,
        roomId: String,
        percent: Int,
    ) {
        val previous = observed.lastCommand?.requestId
        controller.command(key, observed, CompanionMusicAction.Volume(roomId, percent)).join()
        val result = withTimeout(15_000) {
            controller.state.first { state ->
                val current = state.music
                state.fresh && !state.sending && current != null && current.pending == null &&
                    current.sourceId == observed.sourceId &&
                    current.sourceRevision == observed.sourceRevision &&
                    current.lastCommand?.requestId != previous &&
                    current.lastCommand?.command == "set_volume" &&
                    current.lastCommand?.outcome == "confirmed" &&
                    current.rooms.singleOrNull { it.id == roomId }?.volume == percent
            }
        }
        assertEquals("Change confirmed by the player.", result.feedback)
    }
}
