package com.portalinstaller.data.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Modul de temă ales de utilizator. Este persistat după [name] (nu ordinal),
 * ca reordonarea enum-ului să nu remapeze setările salvate.
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK;

    companion object {
        /** Tolerantă la valori necunoscute/nule: revine la [SYSTEM]. */
        fun fromName(raw: String?): ThemeMode =
            entries.firstOrNull { it.name == raw } ?: SYSTEM
    }
}

/**
 * Preferințele de aspect ale aplicației (temă + culoare dinamică), persistate
 * în DataStore. Primește un [DataStore] injectat, deci este testabil pe JVM
 * fără Android (vezi SettingsStoreTest).
 */
class SettingsStore(private val dataStore: DataStore<Preferences>) {

    val themeMode: Flow<ThemeMode> = dataStore.data.map { prefs ->
        ThemeMode.fromName(prefs[THEME_MODE_KEY])
    }

    val useDynamicColor: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[DYNAMIC_COLOR_KEY] ?: false
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { prefs -> prefs[THEME_MODE_KEY] = mode.name }
    }

    suspend fun setUseDynamicColor(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[DYNAMIC_COLOR_KEY] = enabled }
    }

    companion object {
        internal val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
        internal val DYNAMIC_COLOR_KEY = booleanPreferencesKey("use_dynamic_color")
    }
}
