/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.share

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * The name Portals show as the sender ("New from Rohit's Pixel"). It is this phone's pairing
 * name, editable in Toolkit Settings; the default is the phone's own device name, never account data.
 */
object SenderName {
    const val MAX = 40
    private const val PREFS = "share"
    private const val KEY = "sender_name"

    fun clean(raw: String?): String =
        raw.orEmpty().filterNot { it.isISOControl() }.replace(Regex("\\s+"), " ").trim().take(MAX).trim().ifEmpty { "Phone" }

    fun get(context: Context): String {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        if (!saved.isNullOrBlank()) return clean(saved)
        val device = runCatching { Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()
        return clean(device ?: Build.MODEL)
    }

    fun set(context: Context, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, clean(name)).apply()
    }
}

/** The room the picker selected last time ("Last used"). */
object LastShareRoom {
    private const val PREFS = "share"
    private const val KEY = "last_room"
    fun get(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
    fun set(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, key).apply()
    }
}
