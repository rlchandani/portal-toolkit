/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OtaPolicyTest {
    private val sha = "cd".repeat(32)
    private val manifest = OtaManifest(1, "com.portalinstaller", 40, "1.9", sha, 100L,
        "https://github.com/rlchandani/portal-toolkit/releases/download/toolkit-v1.9/portal-toolkit.apk", 26, "")

    @Test fun newerVersionForThisAppIsOffered() {
        assertEquals(Offer.Available(manifest), OtaPolicy.offer(manifest, "com.portalinstaller", 39, 35))
    }

    @Test fun sameOrOlderVersionIsUpToDate() {
        assertEquals(Offer.UpToDate, OtaPolicy.offer(manifest, "com.portalinstaller", 40, 35))
        assertEquals(Offer.UpToDate, OtaPolicy.offer(manifest, "com.portalinstaller", 41, 35))
    }

    @Test fun anotherAppsManifestIsRejected() {
        assertEquals(Offer.Rejected(OtaCopy.WRONG_APP), OtaPolicy.offer(manifest, "com.other", 1, 35))
    }

    @Test fun tooOldAndroidIsRejected() {
        assertEquals(Offer.Rejected(OtaCopy.NEEDS_NEWER_ANDROID), OtaPolicy.offer(manifest.copy(minSdk = 36), "com.portalinstaller", 1, 35))
    }

    @Test fun downloadMustMatchFingerprintAndSize() {
        assertNull(OtaPolicy.verifyDownload(manifest, sha, 100L))
        assertNull(OtaPolicy.verifyDownload(manifest, sha.uppercase(), 100L))
        assertEquals(OtaCopy.SIZE_MISMATCH, OtaPolicy.verifyDownload(manifest, sha, 99L))
        assertEquals(OtaCopy.HASH_MISMATCH, OtaPolicy.verifyDownload(manifest, "ef".repeat(32), 100L))
    }

    @Test fun archiveMustBeOurAppSignedWithThePinnedKey() {
        val good = ArchiveFacts("com.portalinstaller", 40, listOf(OtaPolicy.PINNED_CERT_SHA256))
        assertNull(OtaPolicy.verifyArchive(manifest, good))
        assertNull(OtaPolicy.verifyArchive(manifest, good.copy(signerSha256 = listOf(OtaPolicy.PINNED_CERT_SHA256.uppercase()))))
        assertEquals(OtaCopy.UNREADABLE_APK, OtaPolicy.verifyArchive(manifest, null))
        assertEquals(OtaCopy.WRONG_APP, OtaPolicy.verifyArchive(manifest, good.copy(packageName = "com.other")))
        assertEquals(OtaCopy.VERSION_MISMATCH, OtaPolicy.verifyArchive(manifest, good.copy(versionCode = 41)))
        assertEquals(OtaCopy.WRONG_SIGNER, OtaPolicy.verifyArchive(manifest, good.copy(signerSha256 = emptyList())))
        assertEquals(OtaCopy.WRONG_SIGNER, OtaPolicy.verifyArchive(manifest, good.copy(signerSha256 = listOf("00".repeat(32)))))
        assertEquals(OtaCopy.WRONG_SIGNER, OtaPolicy.verifyArchive(manifest,
            good.copy(signerSha256 = listOf(OtaPolicy.PINNED_CERT_SHA256, "00".repeat(32)))))
    }

    @Test fun redirectsStayOnGithubDownloadHosts() {
        val start = "https://github.com/rlchandani/portal-toolkit/releases/download/t/portal-toolkit.apk"
        assertEquals("https://release-assets.githubusercontent.com/x?sig=1",
            OtaPolicy.nextHop(start, "https://release-assets.githubusercontent.com/x?sig=1"))
        assertEquals("https://objects.githubusercontent.com/y",
            OtaPolicy.nextHop(start, "https://objects.githubusercontent.com/y"))
        assertEquals("https://github.com/rlchandani/portal-toolkit/releases/download/toolkit-v1/portal-toolkit-latest.json",
            OtaPolicy.nextHop("https://github.com/rlchandani/portal-toolkit/releases/latest/download/portal-toolkit-latest.json",
                "/rlchandani/portal-toolkit/releases/download/toolkit-v1/portal-toolkit-latest.json"))
        assertNull(OtaPolicy.nextHop(start, "http://objects.githubusercontent.com/y"))
        assertNull(OtaPolicy.nextHop(start, "https://evil.example/y"))
        assertNull(OtaPolicy.nextHop(start, "https://githubusercontent.com.evil.example/y"))
        assertNull(OtaPolicy.nextHop(start, null))
        assertNull(OtaPolicy.nextHop(start, "::not a url"))
    }

    @Test fun feedIsTheToolkitLatestRelease() {
        assertEquals("https://github.com/rlchandani/portal-toolkit/releases/latest/download/portal-toolkit-latest.json", OtaPolicy.FEED_URL)
        assertEquals("e24bdc86f409906e9db72807989760c6bbad43675f176553339f8fe299bb5f15", OtaPolicy.PINNED_CERT_SHA256)
    }
}
