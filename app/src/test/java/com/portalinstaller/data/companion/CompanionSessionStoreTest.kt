/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.companion

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CompanionSessionStoreTest {
    @Test fun privateFileRoundTripKeepsIndependentDevicesAndForget() = runTest {
        val directory = Files.createTempDirectory("companion-session-test").toFile()
        try {
            val store = FileCompanionSessionStore(directory)
            val a = CompanionSession(CompanionDevice("a", "Kitchen", "http://a:8723"), "secret-a")
            val b = CompanionSession(CompanionDevice("b", "Living room", "https://b/vpn"), "secret-b")
            store.save(listOf(a, b))
            assertEquals(listOf("a", "b"), store.load().map { it.device.id })
            assertEquals("secret-a", store.load().first().token)
            store.save(listOf(b))
            assertEquals("b", FileCompanionSessionStore(directory).load().single().device.id)
            assertFalse(directory.listFiles()!!.any { it.name.endsWith(".tmp") })
        } finally { directory.deleteRecursively() }
    }

    @Test fun corruptedStoreIsNotSilentlyReplacedWithAnEmptyRoster() = runTest {
        val directory = Files.createTempDirectory("companion-corrupt-test").toFile()
        try {
            directory.resolve("companion-sessions.json").writeText("{corrupt")
            assertTrue(runCatching { FileCompanionSessionStore(directory).load() }.isFailure)
            assertEquals("{corrupt", directory.resolve("companion-sessions.json").readText())
        } finally { directory.deleteRecursively() }
    }
}
