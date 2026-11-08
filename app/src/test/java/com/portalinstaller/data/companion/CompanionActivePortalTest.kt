/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.companion

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Reopening Toolkit returns to the Portal you last used instead of "No Portal selected". */
@OptIn(ExperimentalCoroutinesApi::class)
class CompanionActivePortalTest {
    private class Store(var active: String?) : CompanionSessionStore {
        override suspend fun load() = listOf("a", "b").map { CompanionSession(CompanionDevice(it, it.uppercase(), "http://$it:8723"), "t-$it") }
        override suspend fun save(sessions: List<CompanionSession>) {}
        override suspend fun loadActiveId(): String? = active
        override suspend fun saveActiveId(id: String?) { active = id }
    }

    private val offline = CompanionClient(CompanionTransport { CompanionResponse(503, """{"ok":false,"error":"offline"}""") })

    @Test fun theLastUsedPortalIsReselectedOnLaunch() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repo = CompanionRepository(offline, Store("b"), backgroundScope, dispatcher, preloadManagement = false)
        repo.ready.join(); advanceUntilIdle()
        assertEquals("b", repo.state.value.activeDeviceId)
    }

    @Test fun aSavedPortalThatIsNoLongerPairedIsIgnored() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repo = CompanionRepository(offline, Store("gone"), backgroundScope, dispatcher, preloadManagement = false)
        repo.ready.join(); advanceUntilIdle()
        assertNull(repo.state.value.activeDeviceId)
    }

    @Test fun choosingAndDisconnectingAreRemembered() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val store = Store(null)
        val repo = CompanionRepository(offline, store, backgroundScope, dispatcher, preloadManagement = false)
        repo.ready.join()
        repo.selectDevice("a").join(); advanceUntilIdle()
        assertEquals("a", store.active)
        repo.disconnect(); advanceUntilIdle()
        assertNull(store.active)
    }

    @Test fun closingTheAppKeepsThePortalForNextLaunch() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val store = Store(null)
        val repo = CompanionRepository(offline, store, backgroundScope, dispatcher, preloadManagement = false)
        repo.ready.join()
        repo.selectDevice("b").join(); advanceUntilIdle()
        repo.close()
        assertEquals("b", store.active)
    }
}
