package com.portalinstaller.data.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/**
 * O intrare în jurnalul de operații afișat în tab-ul „More”.
 * Trăia în PortalViewModel; a fost mutată aici pentru ca [HistoryStore]
 * să dețină propriul model de date.
 */
data class HistoryEntry(
    val timestamp: Long,
    val description: String,
    val success: Boolean
)

/**
 * Jurnalul de operații, persistat în DataStore ca un singur șir JSON.
 *
 * Înlocuiește codul SharedPreferences din ui/more. Păstrează cel mult
 * [MAX_ENTRIES] intrări, cele mai noi primele. Primește un [DataStore]
 * injectat, deci poate fi testat pe JVM fără Android (vezi HistoryStoreTest).
 */
class HistoryStore(private val dataStore: DataStore<Preferences>) {

    val history: Flow<List<HistoryEntry>> = dataStore.data.map { prefs ->
        prefs[KEY]?.let(::parse).orEmpty()
    }

    /** Adaugă o intrare în față și taie lista la [MAX_ENTRIES]. */
    suspend fun add(entry: HistoryEntry) {
        dataStore.edit { prefs ->
            val current = prefs[KEY]?.let(::parse).orEmpty()
            val updated = (listOf(entry) + current).take(MAX_ENTRIES)
            prefs[KEY] = serialize(updated)
        }
    }

    /** Înlocuiește întregul jurnal (folosit la restaurarea dintr-o sursă externă). */
    suspend fun replaceAll(entries: List<HistoryEntry>) {
        dataStore.edit { prefs ->
            prefs[KEY] = serialize(entries.take(MAX_ENTRIES))
        }
    }

    suspend fun clear() {
        dataStore.edit { prefs -> prefs.remove(KEY) }
    }

    companion object {
        const val MAX_ENTRIES = 50
        internal val KEY = stringPreferencesKey("operation_history")

        internal fun parse(raw: String): List<HistoryEntry> = runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val description = item.optString("description")
                    if (description.isBlank()) continue
                    add(
                        HistoryEntry(
                            timestamp = item.optLong("timestamp"),
                            description = description,
                            success = item.optBoolean("success")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())

        internal fun serialize(entries: List<HistoryEntry>): String {
            val array = JSONArray()
            entries.forEach { entry ->
                array.put(
                    JSONObject()
                        .put("timestamp", entry.timestamp)
                        .put("description", entry.description)
                        .put("success", entry.success)
                )
            }
            return array.toString()
        }
    }
}
