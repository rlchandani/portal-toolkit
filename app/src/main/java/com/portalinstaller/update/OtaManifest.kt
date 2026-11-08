/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.update

import org.json.JSONObject

/** The answer to "is there a newer Portal Toolkit?", read from `portal-toolkit-latest.json`. */
data class OtaManifest(
    val schema: Int,
    val packageName: String,
    val versionCode: Int,
    val versionName: String,
    val sha256: String,
    val size: Long,
    val apkUrl: String,
    val minSdk: Int,
    val notes: String,
) {
    fun toJson(): String = JSONObject().put("schema", schema).put("packageName", packageName)
        .put("versionCode", versionCode).put("versionName", versionName).put("sha256", sha256)
        .put("size", size).put("apkUrl", apkUrl).put("minSdk", minSdk).put("notes", notes).toString()

    companion object {
        const val SCHEMA = 1
        private val SHA256_HEX = Regex("[0-9a-f]{64}")

        /** Never throws: anything incomplete or untrusted is [ManifestParse.Invalid] with a reason to show. */
        fun parse(text: String?): ManifestParse {
            val o = text?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return ManifestParse.Invalid(OtaCopy.BAD_MANIFEST)
            if (o.optInt("schema", -1) != SCHEMA) return ManifestParse.Invalid(OtaCopy.UNKNOWN_SCHEMA)
            val code = o.optInt("versionCode", -1)
            val pkg = o.optString("packageName").trim()
            val sha = o.optString("sha256").trim().lowercase()
            val size = o.optLong("size", -1)
            val url = o.optString("apkUrl").trim()
            if (code <= 0 || pkg.isEmpty() || !SHA256_HEX.matches(sha) || size !in 1..OtaPolicy.MAX_APK_BYTES) {
                return ManifestParse.Invalid(OtaCopy.BAD_MANIFEST)
            }
            if (!OtaPolicy.isReleaseAssetUrl(url)) return ManifestParse.Invalid(OtaCopy.BAD_SOURCE)
            return ManifestParse.Valid(OtaManifest(SCHEMA, pkg, code, o.optString("versionName").trim().ifEmpty { code.toString() },
                sha, size, url, o.optInt("minSdk", 0), o.optString("notes").trim()))
        }
    }
}

sealed interface ManifestParse {
    data class Valid(val manifest: OtaManifest) : ManifestParse
    data class Invalid(val reason: String) : ManifestParse
}
