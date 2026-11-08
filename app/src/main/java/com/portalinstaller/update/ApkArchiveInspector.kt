/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

/** Reads package, version and signing certificates from a downloaded APK, before it is installed. */
object ApkArchiveInspector {
    fun facts(context: Context, apk: File): ArchiveFacts? = runCatching {
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags()) ?: return null
        val signers = signerCertificates(info)
        ArchiveFacts(info.packageName, info.longVersionCode.toInt(), signers.map(::sha256Hex))
    }.getOrNull()

    private fun flags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
        else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES

    private fun signerCertificates(info: android.content.pm.PackageInfo): List<ByteArray> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo ?: return emptyList()
            return signing.apkContentsSigners.orEmpty().map { it.toByteArray() }
        }
        @Suppress("DEPRECATION")
        return info.signatures.orEmpty().map { it.toByteArray() }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
