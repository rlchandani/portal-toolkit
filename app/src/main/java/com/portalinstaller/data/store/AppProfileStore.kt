package com.portalinstaller.data.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class AppProfile(
    val id: String,
    val name: String,
    val packageNames: Set<String>,
    val updatedAt: Long
)

/**
 * Profilurile de aplicații, persistate în DataStore sub cheia [KEY].
 * Cheia și forma JSON coincid cu vechiul SharedPreferences
 * („portal_installer_profiles” / „app_profiles”) pentru migrare curată.
 *
 * `now`/`newId` sunt injectate pentru determinism în teste.
 */
class AppProfileStore(
    private val dataStore: DataStore<Preferences>,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() }
) {

    val profiles: Flow<List<AppProfile>> = dataStore.data.map { prefs ->
        prefs[KEY]?.let(::parse).orEmpty()
    }

    suspend fun saveProfile(name: String, packageNames: Set<String>): List<AppProfile> {
        val trimmed = name.trim()
        return mutate { current ->
            val existing = current.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
            val profile = AppProfile(
                id = existing?.id ?: newId(),
                name = trimmed,
                packageNames = packageNames.toSortedSet(),
                updatedAt = now()
            )
            current.filterNot { it.id == profile.id } + profile
        }
    }

    suspend fun remove(id: String): List<AppProfile> = mutate { current ->
        current.filterNot { it.id == id }
    }

    private suspend fun mutate(transform: (List<AppProfile>) -> List<AppProfile>): List<AppProfile> {
        var result: List<AppProfile> = emptyList()
        dataStore.edit { prefs ->
            val current = prefs[KEY]?.let(::parse).orEmpty()
            result = transform(current).sortedBy { it.name.lowercase() }
            prefs[KEY] = serialize(result)
        }
        return result
    }

    companion object {
        internal val KEY = stringPreferencesKey("app_profiles")

        internal fun parse(raw: String): List<AppProfile> = runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val packagesJson = item.optJSONArray("packages") ?: JSONArray()
                    val packages = buildSet {
                        for (packageIndex in 0 until packagesJson.length()) {
                            packagesJson.optString(packageIndex)
                                .takeIf { it.isNotBlank() }
                                ?.let(::add)
                        }
                    }
                    add(
                        AppProfile(
                            id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                            name = item.optString("name").ifBlank { "Profile" },
                            packageNames = packages,
                            updatedAt = item.optLong("updatedAt")
                        )
                    )
                }
            }.sortedBy { it.name.lowercase() }
        }.getOrDefault(emptyList())

        internal fun serialize(profiles: List<AppProfile>): String {
            val array = JSONArray()
            profiles.forEach { profile ->
                array.put(
                    JSONObject()
                        .put("id", profile.id)
                        .put("name", profile.name)
                        .put("packages", JSONArray(profile.packageNames.toList()))
                        .put("updatedAt", profile.updatedAt)
                )
            }
            return array.toString()
        }
    }
}
