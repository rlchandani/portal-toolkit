/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.update

import android.content.Context

/** The last manifest GitHub offered and which version was already notified. Tiny, so SharedPreferences. */
internal class UpdateStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun latest(): OtaManifest? = (OtaManifest.parse(prefs.getString(LATEST, null)) as? ManifestParse.Valid)?.manifest

    fun saveLatest(manifest: OtaManifest?) =
        prefs.edit().apply { if (manifest == null) remove(LATEST) else putString(LATEST, manifest.toJson()) }.apply()

    fun notifiedCode(): Int = prefs.getInt(NOTIFIED, 0)

    fun saveNotified(code: Int) = prefs.edit().putInt(NOTIFIED, code).apply()

    fun lastCheckedAt(): Long = prefs.getLong(CHECKED_AT, 0L)

    fun saveCheckedAt(ms: Long) = prefs.edit().putLong(CHECKED_AT, ms).apply()

    private companion object {
        const val PREFS = "toolkit_updates"
        const val LATEST = "latest"
        const val NOTIFIED = "notified_code"
        const val CHECKED_AT = "checked_at"
    }
}
