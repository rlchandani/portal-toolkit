/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.ota

import org.json.JSONObject

/** Hearthpane's own update states, as the Portal reports them on `/remote/ota`. */
enum class PortalOtaPhase(val wire: String) {
    IDLE("idle"), CHECKING("checking"), AVAILABLE("available"), DOWNLOADING("downloading"),
    INSTALLING("installing"), FAILED("failed"), UP_TO_DATE("up_to_date"), UNKNOWN("");

    val busy: Boolean get() = this == CHECKING || this == DOWNLOADING || this == INSTALLING

    companion object {
        fun from(wire: String): PortalOtaPhase = entries.firstOrNull { it != UNKNOWN && it.wire == wire } ?: UNKNOWN
    }
}

data class PortalOtaRelease(val versionName: String, val versionCode: Int, val notes: String)

/** One Portal's Hearthpane update state. [available] is only kept when newer than what is installed. */
data class PortalOta(
    val phase: PortalOtaPhase,
    val installedVersionName: String,
    val installedVersionCode: Int,
    val available: PortalOtaRelease?,
    val lastCheckedAtMs: Long?,
    val error: String?,
    val autoInstall: Boolean,
)

/** What the phone knows about one paired Portal's updates. */
sealed interface RoomOta {
    data object Loading : RoomOta
    data class Ready(val ota: PortalOta) : RoomOta
    /** An older Hearthpane without `/remote/ota`: shown as nothing. */
    data object Unsupported : RoomOta
    data object Offline : RoomOta
    data class Failed(val message: String) : RoomOta
}

object PortalOtaParser {
    /**
     * Null when the answer has no `ota` object. Reads Hearthpane's wire shape (`status`,
     * `installed{versionCode,versionName}`, `lastCheckAt`, `error{code,message}`, `devMode`) and
     * tolerates the flat field names too, nulls, and states this phone doesn't know yet.
     */
    fun parse(response: JSONObject): PortalOta? {
        val o = response.optJSONObject("ota") ?: return null
        val installed = o.optJSONObject("installed")
        val installedCode = installed?.optInt("versionCode", 0) ?: o.optInt("installedVersionCode", 0)
        val installedName = installed?.optString("versionName") ?: o.optString("installedVersionName")
        val release = o.optJSONObject("available")?.let { a ->
            val code = a.optInt("versionCode", 0)
            PortalOtaRelease(a.optString("versionName").ifBlank { code.toString() }, code, text(a, "notes").orEmpty())
                .takeIf { code > installedCode }
        }
        val phase = PortalOtaPhase.from(o.optString("status").ifBlank { o.optString("state") })
        val checkedKey = if (o.has("lastCheckAt")) "lastCheckAt" else "lastCheckedAtMs"
        val checkedAt = if (o.isNull(checkedKey)) null else o.optLong(checkedKey)
        val autoInstall = if (o.has("devMode")) !o.optBoolean("devMode") else o.optBoolean("autoInstall", false)
        return PortalOta(phase, installedName.orEmpty(), installedCode, release, checkedAt, error(o), autoInstall)
    }

    /** `error` is `{code, message}` from Hearthpane; a plain string is accepted too. */
    private fun error(o: JSONObject): String? {
        if (o.isNull("error")) return null
        o.optJSONObject("error")?.let { e -> return text(e, "message") ?: text(e, "code") }
        return text(o, "error")
    }

    private fun text(o: JSONObject, key: String): String? = if (o.isNull(key)) null else o.optString(key).takeIf { it.isNotBlank() }
}
