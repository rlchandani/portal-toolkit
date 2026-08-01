package com.portalinstaller.data.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray

/**
 * Istoricul comenzilor rulate in shell-ul de la distanta, persistat in DataStore
 * ca un singur sir JSON. Cele mai noi comenzi sunt primele, pastram cel mult
 * [MAX] intrari si nu re-adaugam un duplicat consecutiv (a rula aceeasi comanda
 * de doua ori la rand nu ar trebui sa umple istoricul). Primeste un [DataStore]
 * injectat, deci este testabil pe JVM fara Android (vezi ShellHistoryStoreTest).
 */
class ShellHistoryStore(private val dataStore: DataStore<Preferences>) {

    val commands: Flow<List<String>> = dataStore.data.map { prefs ->
        prefs[KEY]?.let(::parse).orEmpty()
    }

    /** Adauga o comanda in fata, ignorand valorile goale si duplicatele consecutive. */
    suspend fun add(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return
        dataStore.edit { prefs ->
            val current = prefs[KEY]?.let(::parse).orEmpty()
            if (current.firstOrNull() == trimmed) return@edit
            val updated = (listOf(trimmed) + current).take(MAX)
            prefs[KEY] = serialize(updated)
        }
    }

    companion object {
        const val MAX = 100
        internal val KEY = stringPreferencesKey("shell_command_history")

        internal fun parse(raw: String): List<String> = runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optString(index)
                    if (item.isNotBlank()) add(item)
                }
            }
        }.getOrDefault(emptyList())

        internal fun serialize(commands: List<String>): String {
            val array = JSONArray()
            commands.forEach { array.put(it) }
            return array.toString()
        }
    }
}
