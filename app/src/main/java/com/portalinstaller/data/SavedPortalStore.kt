package com.portalinstaller.data

import android.content.Context
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

object SavedPortalStore {
    private const val PREFS_NAME = "portal_installer_devices"
    private const val KEY_PORTALS = "saved_portals"

    fun load(context: Context): List<SavedPortal> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PORTALS, null)
            ?: return emptyList()

        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val ip = item.optString("ip")
                    val port = item.optInt("port", 5555)
                    if (ip.isBlank()) continue
                    add(
                        SavedPortal(
                            name = item.optString("name").ifBlank { "Portal $ip" },
                            ip = ip,
                            port = port,
                            model = item.optString("model"),
                            lastConnectedAt = item.optLong("lastConnectedAt")
                        )
                    )
                }
            }.sortedByDescending { it.lastConnectedAt }
        }.getOrDefault(emptyList())
    }

    fun upsert(context: Context, portal: SavedPortal): List<SavedPortal> {
        val updated = (load(context).filterNot { it.id == portal.id } + portal)
            .sortedByDescending { it.lastConnectedAt }
        save(context, updated)
        return updated
    }

    fun remove(context: Context, id: String): List<SavedPortal> {
        val updated = load(context).filterNot { it.id == id }
        save(context, updated)
        return updated
    }

    private fun save(context: Context, portals: List<SavedPortal>) {
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
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PORTALS, array.toString())
            .apply()
    }
}
