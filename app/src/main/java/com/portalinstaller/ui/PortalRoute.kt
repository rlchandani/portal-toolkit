package com.portalinstaller.ui

import androidx.annotation.StringRes
import com.portalinstaller.R

/**
 * Destinatiile principale din bara de navigare.
 * glyphIndex corespunde iconitei desenate de NavigationGlyph.
 */
sealed class PortalRoute(
    val route: String,
    @StringRes val labelRes: Int,
    val glyphIndex: Int
) {
    data object Apps : PortalRoute("apps", R.string.tab_apps, 0)
    // Pastram id-ul rutei „photos” pentru compatibilitate, dar eticheta si
    // continutul sunt acum managerul de screensaver.
    data object Photos : PortalRoute("photos", R.string.tab_screensaver, 1)
    data object Devices : PortalRoute("devices", R.string.tab_devices, 2)
    data object Tools : PortalRoute("tools", R.string.tab_tools, 3)

    companion object {
        val bottomNavOrder: List<PortalRoute> = listOf(Devices, Apps, Photos, Tools)
        const val START_DESTINATION = "devices"
    }
}
