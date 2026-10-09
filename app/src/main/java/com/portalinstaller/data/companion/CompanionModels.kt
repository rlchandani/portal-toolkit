/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.companion

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

enum class CompanionConnection { UNPAIRED, CONNECTING, CONNECTED, OFFLINE, UNAUTHORIZED }

data class CompanionDevice(
    val id: String,
    val name: String,
    val endpoint: String,
    val model: String? = null,
    val apiLevel: Int? = null,
    val photoFolder: String = CompanionPhotos.DEFAULT_FOLDER,
    val photoBrowseAll: Boolean = true,
)

/** Credentials belong to persistence/transport, never ControlCenterState. */
class CompanionSession(val device: CompanionDevice, internal val token: String) {
    override fun toString() = "CompanionSession(device=${device.id}, credentials=redacted)"
}

data class DiscoveredPortal(val name: String, val endpoint: String)

data class CompanionError(val code: String, val message: String, val status: Int? = null, val details: JSONObject? = null) {
    override fun toString() = "CompanionError(code=$code, status=$status)"
}

data class SettingControl(val key: String, val type: String, val title: String, val value: Any?, val raw: JSONObject) {
    val advanced: Boolean get() = raw.optString("tier") == "advanced" || raw.optBoolean("advanced")
    val readOnly: Boolean get() = raw.optBoolean("readOnly") || raw.optBoolean("readonly") || type == "readonly"
    val help: String get() = raw.optString("shortHelp").ifBlank { raw.optString("help") }
}

data class SettingsDomain(val id: String, val title: String, val controls: List<SettingControl>, val raw: JSONObject)

data class ControlCenterState(
    val devices: List<CompanionDevice> = emptyList(),
    val discovered: List<DiscoveredPortal> = emptyList(),
    val discovering: Boolean = false,
    val activeDeviceId: String? = null,
    val selectedTargetIds: Set<String> = emptySet(),
    val groupOperations: List<GroupOperation> = emptyList(),
    val targetSelectionSaving: Boolean = false,
    val sessionGeneration: Long = 0,
    val connection: CompanionConnection = CompanionConnection.UNPAIRED,
    val busy: Boolean = false,
    val info: JSONObject? = null,
    val settings: List<SettingsDomain> = emptyList(),
    val schema: JSONObject? = null,
    val apps: JSONArray = JSONArray(),
    val sources: JSONObject? = null,
    val nowPlaying: JSONObject? = null,
    val layout: JSONObject? = null,
    val snapshot: JSONObject? = null,
    val catalog: JSONObject? = null,
    val operations: JSONArray = JSONArray(),
    val scenes: JSONArray = JSONArray(),
    val scenesRevision: String? = null,
    val scenesLoaded: Boolean = false,
    val jarvis: JSONObject? = null,
    val lastUpdatedAt: Long? = null,
    val error: CompanionError? = null,
    val lastResult: JSONObject? = null,
    val photoFolder: String = CompanionPhotos.DEFAULT_FOLDER,
    val photos: List<CompanionPhoto> = emptyList(),
    val photoFreeBytes: Long? = null,
    val photoTransfer: PhotoTransferState? = null,
    val photoBrowseAll: Boolean = true,
    val photoFolders: List<CompanionPhotoFolder> = emptyList(),
    val photoListingTruncated: Boolean = false,
    val photoListingLoaded: Boolean = false,
    val photoListingError: String? = null,
    val photoListingAll: Boolean = false,
) {
    val selectedTargets: List<CompanionDevice> get() = devices.filter { it.id in selectedTargetIds }
    val activeDevice: CompanionDevice? get() = devices.firstOrNull { it.id == activeDeviceId }
    val deviceName: String get() = activeDevice?.name ?: "Portal"
    val connected: Boolean get() = connection == CompanionConnection.CONNECTED
    val sessions: List<CompanionDevice> get() = devices
    val status: JSONObject? get() = info
    val media: JSONObject? get() = nowPlaying
    fun supports(capability: String): Boolean = info?.optJSONObject("capabilities")?.optBoolean(capability) == true
    override fun toString() = "ControlCenterState(connection=$connection, activeDeviceId=$activeDeviceId, busy=$busy)"
}

object CompanionEndpoint {
    const val DEFAULT_PORT = 8723

    /** Keeps configured reverse-proxy prefixes and HTTPS; removes the remote UI / QR suffix. */
    fun normalize(input: String): String {
        val trimmed = input.trim()
        require(trimmed.isNotEmpty()) { "Enter a Portal address." }
        val explicitScheme = trimmed.contains("://")
        val candidate = if (explicitScheme) trimmed else "http://$trimmed"
        val uri = runCatching { URI(candidate) }.getOrElse { throw IllegalArgumentException("Invalid Portal address.") }
        require(uri.scheme == "http" || uri.scheme == "https") { "Use an HTTP or HTTPS endpoint." }
        require(uri.rawUserInfo == null) { "Credentials must not be included in the address." }
        require(uri.path.orEmpty().split('/').none { it == "." || it == ".." }) { "Invalid endpoint path." }
        require(uri.port == -1 || uri.port in 1..65535) { "Port must be between 1 and 65535." }
        val parsed = candidate.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid Portal address.")
        require(parsed.host.isNotBlank()) { "Enter a Portal host." }
        val path = parsed.encodedPath.trimEnd('/').removeSuffix("/remote/ui")
        val builder = parsed.newBuilder().encodedPath(if (path.isBlank()) "/" else path).query(null).fragment(null)
        if (!explicitScheme && uri.port == -1) builder.port(DEFAULT_PORT)
        return builder.build().toString().trimEnd('/')
    }
}

class PairingCode(val endpoint: String, val pin: String? = null, val name: String? = null) {
    override fun toString() = "PairingCode(endpoint=$endpoint, pin=redacted)"
    companion object {
        fun parse(input: String): PairingCode {
            val value = input.trim()
            if (value.startsWith("{")) {
                val json = JSONObject(value)
                val endpoint = json.optString("endpoint").ifBlank { json.optString("base").ifBlank { json.optString("url") } }
                return PairingCode(CompanionEndpoint.normalize(endpoint), validPin(json.optString("pin")), json.optString("name").ifBlank { null })
            }
            val uri = runCatching { URI(if (value.contains("://")) value else "http://$value") }.getOrNull()
            val params = listOfNotNull(uri?.rawQuery, uri?.rawFragment).flatMap { it.split('&') }
            val pin = params.firstOrNull { it.substringBefore('=') == "pin" }?.substringAfter('=')?.let { URLDecoder.decode(it, "UTF-8") }
            return PairingCode(CompanionEndpoint.normalize(value), validPin(pin))
        }
        private fun validPin(value: String?): String? {
            if (value.isNullOrBlank()) return null
            require(value.matches(Regex("[0-9]{6}"))) { "Enter the six-digit PIN shown on the Portal." }
            return value
        }
    }
}

object CompanionParser {
    fun settings(response: JSONObject): List<SettingsDomain> {
        val schema = response.optJSONObject("settings") ?: response
        val domains = schema.optJSONArray("domains") ?: JSONArray()
        return (0 until domains.length()).mapNotNull { index ->
            val domain = domains.optJSONObject(index) ?: return@mapNotNull null
            val id = domain.optString("id")
            if (id.isBlank()) return@mapNotNull null
            val controls = domain.optJSONArray("controls") ?: JSONArray()
            SettingsDomain(id, domain.optString("title", id), (0 until controls.length()).mapNotNull { controlIndex ->
                val control = controls.optJSONObject(controlIndex) ?: return@mapNotNull null
                val key = control.optString("key")
                if (key.isBlank()) return@mapNotNull null
                SettingControl(key, control.optString("type", "unknown"), control.optString("title", key),
                    control.opt("value").takeUnless { it == JSONObject.NULL }, JSONObject(control.toString()))
            }, JSONObject(domain.toString()))
        }
    }
}
