/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.update

import java.net.URI

/** What a fetched manifest means for the installed app. */
sealed interface Offer {
    data object UpToDate : Offer
    data class Available(val manifest: OtaManifest) : Offer
    data class Rejected(val reason: String) : Offer
}

/** What Android reads from the downloaded APK (via `getPackageArchiveInfo`). */
data class ArchiveFacts(val packageName: String, val versionCode: Int, val signerSha256: List<String>)

/**
 * The trust rules for wireless updates (docs/plans/ota-github-releases-2026-11-08.md): only our
 * own releases on GitHub, only newer versions of this app, only the exact announced bytes, only
 * APKs signed by the pinned certificate. Pure: every rule is unit-tested.
 */
object OtaPolicy {
    const val REPO = "rlchandani/portal-toolkit"
    const val FEED_URL = "https://github.com/$REPO/releases/latest/download/portal-toolkit-latest.json"
    const val PINNED_CERT_SHA256 = "4f9a3b1d0c86028e831add351642a6167d06e9d3ed6ded6dcf5a790a560b45d6"
    const val MAX_APK_BYTES = 200L * 1024 * 1024
    const val MAX_MANIFEST_BYTES = 16L * 1024
    const val MAX_REDIRECTS = 5
    private const val HTTPS = "https"
    private const val RELEASE_HOST = "github.com"
    private const val RELEASE_PATH = "/$REPO/releases/download/"
    private val DOWNLOAD_HOSTS = setOf(RELEASE_HOST, "objects.githubusercontent.com", "release-assets.githubusercontent.com")

    fun offer(manifest: OtaManifest, ownPackage: String, installedCode: Int, sdkInt: Int): Offer = when {
        manifest.packageName != ownPackage -> Offer.Rejected(OtaCopy.WRONG_APP)
        manifest.versionCode <= installedCode -> Offer.UpToDate
        manifest.minSdk > sdkInt -> Offer.Rejected(OtaCopy.NEEDS_NEWER_ANDROID)
        else -> Offer.Available(manifest)
    }

    /** Null when the downloaded bytes are exactly the announced ones. */
    fun verifyDownload(manifest: OtaManifest, sha256: String, size: Long): String? = when {
        size != manifest.size -> OtaCopy.SIZE_MISMATCH
        sha256.lowercase() != manifest.sha256 -> OtaCopy.HASH_MISMATCH
        else -> null
    }

    /** Null when the APK is this app, the announced version, and signed only by the pinned key. */
    fun verifyArchive(manifest: OtaManifest, facts: ArchiveFacts?): String? = when {
        facts == null -> OtaCopy.UNREADABLE_APK
        facts.packageName != manifest.packageName -> OtaCopy.WRONG_APP
        facts.versionCode != manifest.versionCode -> OtaCopy.VERSION_MISMATCH
        facts.signerSha256.isEmpty() || facts.signerSha256.any { it.lowercase() != PINNED_CERT_SHA256 } -> OtaCopy.WRONG_SIGNER
        else -> null
    }

    /** The manifest's `apkUrl`: https, github.com (default port, no credentials), our repo's release downloads. */
    fun isReleaseAssetUrl(url: String): Boolean {
        val uri = parse(url) ?: return false
        return isPlainHttps(uri) && uri.host == RELEASE_HOST && (uri.rawPath ?: "").startsWith(RELEASE_PATH)
    }

    /** Where a redirect from [current] may go, or null if it leaves GitHub's download hosts. */
    fun nextHop(current: String, location: String?): String? {
        val base = parse(current) ?: return null
        val target = location?.let { runCatching { base.resolve(it.trim()) }.getOrNull() } ?: return null
        return target.takeIf { isPlainHttps(it) && it.host in DOWNLOAD_HOSTS }?.toString()
    }

    private fun isPlainHttps(uri: URI) = uri.scheme == HTTPS && uri.rawUserInfo == null && uri.port == -1

    private fun parse(url: String): URI? = runCatching { URI(url) }.getOrNull()?.takeIf { it.isAbsolute && it.host != null }
}
