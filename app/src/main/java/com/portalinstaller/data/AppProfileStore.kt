package com.portalinstaller.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class AppProfile(
    val id: String,
    val name: String,
    val packageNames: Set<String>,
    val updatedAt: Long
)

object AppProfileStore {
    private const val PREFS_NAME = "portal_installer_profiles"
    private const val KEY_PROFILES = "app_profiles"

    fun load(context: Context): List<AppProfile> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PROFILES, null)
            ?: return emptyList()

        return runCatching {
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
                            id = item.optString("id").ifBlank {
                                UUID.randomUUID().toString()
                            },
                            name = item.optString("name").ifBlank { "Profile" },
                            packageNames = packages,
                            updatedAt = item.optLong("updatedAt")
                        )
                    )
                }
            }.sortedBy { it.name.lowercase() }
        }.getOrDefault(emptyList())
    }

    fun saveProfile(
        context: Context,
        name: String,
        packageNames: Set<String>
    ): List<AppProfile> {
        val profiles = load(context)
        val existing = profiles.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
        val profile = AppProfile(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = name.trim(),
            packageNames = packageNames.toSortedSet(),
            updatedAt = System.currentTimeMillis()
        )
        val updated = (profiles.filterNot { it.id == profile.id } + profile)
            .sortedBy { it.name.lowercase() }
        save(context, updated)
        return updated
    }

    fun remove(context: Context, id: String): List<AppProfile> {
        val updated = load(context).filterNot { it.id == id }
        save(context, updated)
        return updated
    }

    private fun save(context: Context, profiles: List<AppProfile>) {
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
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROFILES, array.toString())
            .apply()
    }
}
