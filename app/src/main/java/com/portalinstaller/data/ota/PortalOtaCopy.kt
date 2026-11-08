/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.ota

/** One line on Home: "Hearthpane 1.229 available on Living · Update now". */
data class PortalUpdateLine(val text: String, val action: String?, val busy: Boolean, val isError: Boolean = false)

object PortalOtaCopy {
    const val APP = "Hearthpane"
    private const val UPDATE_NOW = "Update now"
    private const val RETRY = "Try again"

    /** Null when there is nothing to offer for this room. */
    fun line(room: String, ota: PortalOta, pending: Boolean): PortalUpdateLine? {
        val release = ota.available ?: return null
        val version = "$APP ${release.versionName}"
        return when {
            ota.phase == PortalOtaPhase.DOWNLOADING -> PortalUpdateLine("Downloading $version on $room…", null, busy = true)
            ota.phase == PortalOtaPhase.INSTALLING -> PortalUpdateLine("Installing $version on $room…", null, busy = true)
            pending || ota.phase == PortalOtaPhase.CHECKING -> PortalUpdateLine("$version available on $room", null, busy = true)
            ota.phase == PortalOtaPhase.FAILED ->
                PortalUpdateLine("$version on $room: ${ota.error ?: "the update didn't finish."}", RETRY, busy = false, isError = true)
            else -> PortalUpdateLine("$version available on $room", UPDATE_NOW, busy = false)
        }
    }

    fun installFailed(room: String) = "$room couldn't start the $APP update. Try again in a moment."
    fun offline(room: String) = "$room didn't answer. Check that it's on and on Wi-Fi."
    fun repair(room: String) = "Pair this phone with $room again."
    fun unsupported(room: String) = "$APP on $room is too old to update from the phone. Update it once by cable."
}
