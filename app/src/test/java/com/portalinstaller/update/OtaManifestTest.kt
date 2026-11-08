/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.update

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OtaManifestTest {
    private val sha = "ab".repeat(32)
    private val url = "https://github.com/rlchandani/portal-toolkit/releases/download/toolkit-v1.9/portal-toolkit.apk"

    private fun json(edit: JSONObject.() -> Unit = {}) = JSONObject()
        .put("schema", 1).put("packageName", "com.portalinstaller").put("versionCode", 33)
        .put("versionName", "1.9").put("sha256", sha).put("size", 1234L).put("apkUrl", url)
        .put("minSdk", 26).put("notes", "Wireless updates").apply(edit).toString()

    private fun reason(text: String?) = (OtaManifest.parse(text) as ManifestParse.Invalid).reason

    @Test fun parsesACompleteManifest() {
        val m = (OtaManifest.parse(json()) as ManifestParse.Valid).manifest
        assertEquals(OtaManifest(1, "com.portalinstaller", 33, "1.9", sha, 1234L, url, 26, "Wireless updates"), m)
    }

    @Test fun normalisesTheFingerprintToLowercase() {
        val m = (OtaManifest.parse(json { put("sha256", sha.uppercase()) }) as ManifestParse.Valid).manifest
        assertEquals(sha, m.sha256)
    }

    @Test fun missingNotesAndVersionNameFallBack() {
        val m = (OtaManifest.parse(json { remove("notes"); remove("versionName") }) as ManifestParse.Valid).manifest
        assertEquals("", m.notes)
        assertEquals("33", m.versionName)
    }

    @Test fun rejectsBrokenOrIncompleteAnswers() {
        assertTrue(reason(null).isNotBlank())
        assertTrue(reason("not json").isNotBlank())
        assertEquals(OtaCopy.UNKNOWN_SCHEMA, reason(json { put("schema", 2) }))
        assertEquals(OtaCopy.BAD_MANIFEST, reason(json { put("versionCode", 0) }))
        assertEquals(OtaCopy.BAD_MANIFEST, reason(json { remove("packageName") }))
        assertEquals(OtaCopy.BAD_MANIFEST, reason(json { put("sha256", "abc") }))
        assertEquals(OtaCopy.BAD_MANIFEST, reason(json { put("sha256", "zz".repeat(32)) }))
        assertEquals(OtaCopy.BAD_MANIFEST, reason(json { put("size", 0) }))
        assertEquals(OtaCopy.BAD_MANIFEST, reason(json { put("size", OtaPolicy.MAX_APK_BYTES + 1) }))
    }

    @Test fun rejectsDownloadsFromAnywhereButOurReleases() {
        listOf(
            "http://github.com/rlchandani/portal-toolkit/releases/download/t/portal-toolkit.apk",
            "https://evil.example/rlchandani/portal-toolkit/releases/download/t/portal-toolkit.apk",
            "https://github.com.evil.example/rlchandani/portal-toolkit/releases/download/t/a.apk",
            "https://github.com/someone/else/releases/download/t/portal-toolkit.apk",
            "https://user@github.com/rlchandani/portal-toolkit/releases/download/t/portal-toolkit.apk",
            "https://github.com:8443/rlchandani/portal-toolkit/releases/download/t/portal-toolkit.apk",
            "https://objects.githubusercontent.com/rlchandani/portal-toolkit/releases/download/t/a.apk",
        ).forEach { bad -> assertEquals(bad, OtaCopy.BAD_SOURCE, reason(json { put("apkUrl", bad) })) }
    }
}
