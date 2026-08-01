package com.portalinstaller.data.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

data class SavedPortal(
    val name: String,
    val ip: String,
    val port: Int,
    val model: String,
    val lastConnectedAt: Long
) {
    val id: String
        get() = "$ip:$port"
}

/**
 * Portalurile salvate, persistate în DataStore ca un singur șir JSON sub
 * cheia [KEY]. Cheia și forma JSON sunt identice cu vechiul SharedPreferences
 * („portal_installer_devices” / „saved_portals”), astfel încât migrarea
 * unică din [PortalDataStores] să transfere datele existente neatinse.
 */
class SavedPortalStore(private val dataStore: DataStore<Preferences>) {

    val portals: Flow<List<SavedPortal>> = dataStore.data.map { prefs ->
        prefs[KEY]?.let(::parse).orEmpty()
    }

    suspend fun upsert(portal: SavedPortal): List<SavedPortal> = mutate { current ->
        (current.filterNot { it.id == portal.id } + portal)
            .sortedByDescending { it.lastConnectedAt }
    }

    suspend fun remove(id: String): List<SavedPortal> = mutate { current ->
        current.filterNot { it.id == id }
    }

    suspend fun rename(id: String, name: String): List<SavedPortal> {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return portalsSnapshot()
        return mutate { current ->
            current.map { if (it.id == id) it.copy(name = trimmed) else it }
        }
    }

    private suspend fun portalsSnapshot(): List<SavedPortal> = mutate { it }

    private suspend fun mutate(transform: (List<SavedPortal>) -> List<SavedPortal>): List<SavedPortal> {
        var result: List<SavedPortal> = emptyList()
        dataStore.edit { prefs ->
            val current = prefs[KEY]?.let(::parse).orEmpty()
            result = transform(current).sortedByDescending { it.lastConnectedAt }
            prefs[KEY] = serialize(result)
        }
        return result
    }

    companion object {
        internal val KEY = stringPreferencesKey("saved_portals")

        internal fun parse(raw: String): List<SavedPortal> = runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val ip = item.optString("ip")
                    if (ip.isBlank()) continue
                    add(
                        SavedPortal(
                            name = item.optString("name").ifBlank { "Portal $ip" },
                            ip = ip,
                            port = item.optInt("port", 5555),
                            model = item.optString("model"),
                            lastConnectedAt = item.optLong("lastConnectedAt")
                        )
                    )
                }
            }.sortedByDescending { it.lastConnectedAt }
        }.getOrDefault(emptyList())

        internal fun serialize(portals: List<SavedPortal>): String {
            val array = JSONArray()
            portals.forEach { portal ->
                array.put(
                    JSONObject()
                        .put("name", portal.name)
                        .put("ip", portal.ip)
                        .put("port", portal.port)
                        .put("model", portal.model)
                        .put("lastConnectedAt", portal.lastConnectedAt)
                )
            }
            return array.toString()
        }
    }
}
