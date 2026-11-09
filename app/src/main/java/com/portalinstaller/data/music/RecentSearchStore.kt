/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.music

import android.content.Context
import org.json.JSONArray
import org.json.JSONException

/** Where the phone keeps its recent music searches (they never leave the phone). */
internal interface RecentSearchStore {
    fun load(): List<String>
    fun save(recent: List<String>)
}

/** Keeps nothing: for previews and tests. */
internal class MemoryRecentSearchStore(initial: List<String> = emptyList()) : RecentSearchStore {
    @Volatile private var saved: List<String> = initial
    override fun load(): List<String> = saved
    override fun save(recent: List<String>) { saved = recent.toList() }
}

/** App-private SharedPreferences, one JSON array of strings. */
internal class PrefsRecentSearchStore(context: Context) : RecentSearchStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(): List<String> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            RecentSearches.sanitize((0 until array.length()).map { array.optString(it) })
        } catch (_: JSONException) {
            // A damaged entry only costs the recent-search list; start a fresh one.
            prefs.edit().remove(KEY).apply()
            emptyList()
        }
    }

    override fun save(recent: List<String>) {
        prefs.edit().putString(KEY, JSONArray(RecentSearches.sanitize(recent)).toString()).apply()
    }

    private companion object {
        const val PREFS = "music"
        const val KEY = "recent_searches"
    }
}
