/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.ota

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalOtaTest {
    private fun response(state: String = "available", available: Boolean = true, error: String? = null) = JSONObject()
        .put("ok", true).put("ota", JSONObject().put("state", state)
            .put("installedVersionName", "1.228").put("installedVersionCode", 222)
            .put("available", if (available) JSONObject().put("versionName", "1.229").put("versionCode", 223)
                .put("notes", "Music page") else JSONObject.NULL)
            .put("lastCheckedAtMs", 1_000L).put("error", error ?: JSONObject.NULL).put("autoInstall", true))

    @Test fun parsesTheOtaRoute() {
        val ota = PortalOtaParser.parse(response())!!
        assertEquals(PortalOta(PortalOtaPhase.AVAILABLE, "1.228", 222, PortalOtaRelease("1.229", 223, "Music page"),
            1_000L, null, true), ota)
    }

    @Test fun nullsAndUnknownStatesAreTolerated() {
        val ota = PortalOtaParser.parse(response(state = "rebooting", available = false).apply {
            getJSONObject("ota").put("lastCheckedAtMs", JSONObject.NULL)
        })!!
        assertEquals(PortalOtaPhase.UNKNOWN, ota.phase)
        assertNull(ota.available)
        assertNull(ota.lastCheckedAtMs)
        assertNull(ota.error)
    }

    @Test fun missingOtaObjectIsNotAnAnswer() {
        assertNull(PortalOtaParser.parse(JSONObject().put("ok", true)))
    }

    @Test fun availableReleaseNotNewerThanInstalledIsIgnored() {
        val ota = PortalOtaParser.parse(response().apply {
            getJSONObject("ota").getJSONObject("available").put("versionCode", 222)
        })!!
        assertNull(ota.available)
    }

    @Test fun availableUpdateReadsAsAnOfferWithUpdateNow() {
        val line = PortalOtaCopy.line("Living", PortalOtaParser.parse(response())!!, pending = false)!!
        assertEquals("Hearthpane 1.229 available on Living", line.text)
        assertEquals("Update now", line.action)
        assertFalse(line.busy)
    }

    @Test fun downloadingAndInstallingAreBusy() {
        val downloading = PortalOtaCopy.line("Den", PortalOtaParser.parse(response(state = "downloading"))!!, pending = false)!!
        assertEquals("Downloading Hearthpane 1.229 on Den…", downloading.text)
        assertNull(downloading.action)
        assertTrue(downloading.busy)
        val installing = PortalOtaCopy.line("Den", PortalOtaParser.parse(response(state = "installing"))!!, pending = false)!!
        assertEquals("Installing Hearthpane 1.229 on Den…", installing.text)
        assertTrue(installing.busy)
    }

    @Test fun pendingRequestIsBusy() {
        val line = PortalOtaCopy.line("Den", PortalOtaParser.parse(response())!!, pending = true)!!
        assertTrue(line.busy)
        assertNull(line.action)
    }

    @Test fun failureShowsTheReasonAndRetries() {
        val line = PortalOtaCopy.line("Den", PortalOtaParser.parse(response(state = "failed", error = "Download didn't match"))!!, pending = false)!!
        assertEquals("Hearthpane 1.229 on Den: Download didn't match", line.text)
        assertEquals("Try again", line.action)
        assertTrue(line.isError)
    }

    @Test fun nothingToShowWithoutAnUpdate() {
        assertNull(PortalOtaCopy.line("Den", PortalOtaParser.parse(response(state = "up_to_date", available = false))!!, pending = false))
        assertNull(PortalOtaCopy.line("Den", PortalOtaParser.parse(response(state = "idle", available = false))!!, pending = false))
        assertNull(PortalOtaCopy.line("Den", PortalOtaParser.parse(response(state = "failed", available = false, error = "x"))!!, pending = false))
    }

    /** The exact shape Hearthpane's OtaRoutes sends (see the Portal's OtaState wire format). */
    @Test fun parsesHearthpaneWireShape() {
        val json = JSONObject("""{"ok":true,"ota":{"installed":{"versionCode":223,"versionName":"1.229"},
            "status":"available","available":{"versionCode":224,"versionName":"1.230","notes":"Fixes","size":10},
            "lastCheckAt":1700000000000,"error":null,"waitingFor":"music","devMode":false}}""")
        val ota = PortalOtaParser.parse(json)!!
        assertEquals(PortalOtaPhase.AVAILABLE, ota.phase)
        assertEquals("1.229", ota.installedVersionName)
        assertEquals(223, ota.installedVersionCode)
        assertEquals(PortalOtaRelease("1.230", 224, "Fixes"), ota.available)
        assertEquals(1700000000000L, ota.lastCheckedAtMs)
        assertNull(ota.error)
        assertTrue(ota.autoInstall)
    }

    @Test fun readsHearthpaneErrorObjectAndDevMode() {
        val json = JSONObject("""{"ok":true,"ota":{"installed":{"versionCode":223,"versionName":"1.229"},
            "status":"failed","available":null,"lastCheckAt":null,
            "error":{"code":"sha_mismatch","message":"The download didn't match."},"devMode":true}}""")
        val ota = PortalOtaParser.parse(json)!!
        assertEquals(PortalOtaPhase.FAILED, ota.phase)
        assertEquals("The download didn't match.", ota.error)
        assertNull(ota.lastCheckedAtMs)
        assertFalse(ota.autoInstall)
    }
}
