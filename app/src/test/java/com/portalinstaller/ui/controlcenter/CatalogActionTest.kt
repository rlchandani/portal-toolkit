/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.controlcenter

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CatalogActionTest {
    @Test fun unknownCatalogVersionDoesNotClaimTheInstalledAppIsCurrent() {
        val action = catalogAction(entry(true).put("updateAvailable", JSONObject.NULL))
        assertTrue("Unknown versions need an actionable server-side check", action.enabled)
        assertEquals("Check for updates", action.label)
        assertEquals("update", action.operation)
    }

    @Test fun missingVersionInformationStillAllowsAnUpdateCheck() {
        assertTrue(catalogAction(entry(true)).enabled)
    }

    @Test fun aKnownCurrentVersionDoesNotReinstallTheApp() {
        val action = catalogAction(entry(true).put("updateAvailable", false))
        assertFalse(action.enabled)
        assertEquals("Up to date", action.label)
    }

    @Test fun aKnownNewVersionOffersUpdate() {
        val action = catalogAction(entry(true).put("updateAvailable", true))
        assertTrue(action.enabled)
        assertEquals("Update", action.label)
    }

    @Test fun newCompatibleAppsOfferInstall() {
        val action = catalogAction(entry(false))
        assertTrue(action.enabled)
        assertEquals("install", action.operation)
    }

    @Test fun incompatibleAppsNeverOfferInstallOrUpdate() {
        assertFalse(catalogAction(entry(false).put("compatible", false)).enabled)
        assertFalse(catalogAction(entry(true).put("compatible", false).put("updateAvailable", true)).enabled)
    }

    @Test fun immortalUpdatesNeverOfferAnOperationTheServerCannotRun() {
        for (packageName in listOf("com.immortal.launcher", "com.immortal.launcher.debug")) {
            val action = catalogAction(entry(true).put("packageName", packageName)
                .put("updateAvailable", true))
            assertFalse("Immortal self-update is rejected by the paired API", action.enabled)
            assertEquals("Manage on Portal", action.label)
        }
    }

    @Test fun anUnknownImmortalVersionStillCannotBeCheckedThroughTheInstallOperation() {
        val action = catalogAction(entry(true).put("packageName", "com.immortal.launcher")
            .put("updateAvailable", JSONObject.NULL))
        assertFalse(action.enabled)
        assertEquals("Manage on Portal", action.label)
    }

    private fun entry(installed: Boolean) = JSONObject()
        .put("packageName", "org.example.radio").put("installed", installed).put("compatible", true)
}
