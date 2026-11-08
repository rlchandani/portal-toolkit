/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateRowTest {
    private val m = OtaManifest(1, "com.portalinstaller", 40, "1.9", "ab".repeat(32), 1000L,
        "https://github.com/rlchandani/portal-toolkit/releases/download/t/portal-toolkit.apk", 26, "Faster")

    private fun row(state: AppUpdateState) = AppUpdateRow.present("1.8.9", state)

    @Test fun idleOffersACheck() {
        val r = row(AppUpdateState.Idle)
        assertEquals("Portal Toolkit 1.8.9", r.title)
        assertEquals(RowAction.CHECK, r.action)
        assertEquals("Check for updates", r.actionLabel)
    }

    @Test fun upToDateStillOffersACheck() {
        val r = row(AppUpdateState.UpToDate(1L))
        assertEquals("Up to date", r.status)
        assertEquals(RowAction.CHECK, r.action)
        assertFalse(r.isError)
    }

    @Test fun availableOffersUpdate() {
        val r = row(AppUpdateState.Available(m))
        assertEquals("1.9 available · Faster", r.status)
        assertEquals(RowAction.UPDATE, r.action)
        assertEquals("Update", r.actionLabel)
    }

    @Test fun downloadingShowsProgressAndNoAction() {
        val r = row(AppUpdateState.Downloading(m, 250L))
        assertEquals(0.25f, r.progress!!, 0.0001f)
        assertEquals("Downloading 1.9 · 25%", r.status)
        assertEquals(RowAction.NONE, r.action)
        assertTrue(r.busy)
    }

    @Test fun checkingAndInstallingAreBusyAndIndeterminate() {
        listOf(AppUpdateState.Checking, AppUpdateState.Verifying(m), AppUpdateState.Installing(m)).forEach {
            val r = row(it)
            assertTrue(r.busy)
            assertNull(r.progress)
            assertEquals(RowAction.NONE, r.action)
        }
    }

    @Test fun permissionWaitExplainsAndRetries() {
        val r = row(AppUpdateState.NeedsPermission(m))
        assertEquals(OtaCopy.ALLOW_SOURCE, r.status)
        assertEquals(RowAction.UPDATE, r.action)
    }

    @Test fun failuresAreClearAndRetryable() {
        val withUpdate = row(AppUpdateState.Failed(m, OtaCopy.HASH_MISMATCH))
        assertTrue(withUpdate.isError)
        assertEquals(OtaCopy.HASH_MISMATCH, withUpdate.status)
        assertEquals(RowAction.UPDATE, withUpdate.action)
        assertEquals("Try again", withUpdate.actionLabel)
        val checkFailed = row(AppUpdateState.Failed(null, OtaCopy.OFFLINE))
        assertEquals(RowAction.CHECK, checkFailed.action)
    }

    @Test fun bannerOnlyWhenAnUpdateIsReady() {
        assertEquals("Portal Toolkit 1.9 is available", AppUpdateRow.banner(AppUpdateState.Available(m)))
        assertEquals("Portal Toolkit 1.9 is available", AppUpdateRow.banner(AppUpdateState.NeedsPermission(m)))
        assertNull(AppUpdateRow.banner(AppUpdateState.UpToDate(1L)))
        assertNull(AppUpdateRow.banner(AppUpdateState.Downloading(m, 1L)))
        assertNull(AppUpdateRow.banner(AppUpdateState.Failed(null, "x")))
    }
}
