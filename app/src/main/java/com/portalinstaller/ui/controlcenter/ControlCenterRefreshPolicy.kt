/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.controlcenter

internal enum class CenterTab(val label: String, val glyph: String) {
    HOME("Home", "home"), REMOTE("Remote", "remote"), PHOTOS("Photos", "photos"),
    LIST("List", "list"), APPS("Apps", "apps"), SETTINGS("Settings", "settings")
}

/** Bottom-bar tabs. Apps is reached from the Manage apps card on Home, not the bar. */
internal val navigationTabs: List<CenterTab> = CenterTab.entries.filter { it != CenterTab.APPS }

internal enum class CenterDetail { NONE, LAYOUT, SCENES, JARVIS, SNAPSHOTS, OPERATIONS, DINNER, ROUTINES, GUESTS }

internal enum class CenterRefresh { STATUS, LAYOUT, SCENES, JARVIS, OPERATIONS, SOURCES, PHOTOS, SETTINGS, APPS, CATALOG }

internal fun visiblePageRefreshes(tab: CenterTab, detail: CenterDetail, showCatalog: Boolean): List<CenterRefresh> =
    when (detail) {
        CenterDetail.LAYOUT -> listOf(CenterRefresh.LAYOUT)
        CenterDetail.SCENES -> listOf(CenterRefresh.SCENES)
        CenterDetail.JARVIS -> listOf(CenterRefresh.JARVIS)
        CenterDetail.OPERATIONS -> listOf(CenterRefresh.OPERATIONS)
        CenterDetail.SNAPSHOTS, CenterDetail.DINNER, CenterDetail.ROUTINES, CenterDetail.GUESTS -> emptyList() // feature screens own their refresh
        CenterDetail.NONE -> when (tab) {
            CenterTab.PHOTOS -> listOf(CenterRefresh.SOURCES, CenterRefresh.PHOTOS, CenterRefresh.SETTINGS)
            CenterTab.SETTINGS -> listOf(CenterRefresh.SETTINGS)
            CenterTab.APPS -> listOf(if (showCatalog) CenterRefresh.CATALOG else CenterRefresh.APPS)
            CenterTab.HOME, CenterTab.REMOTE -> emptyList()
            CenterTab.LIST -> emptyList() // the list screen owns its sync
        }
    }

internal fun centerRefreshRequests(
    page: List<CenterRefresh>,
    connected: Boolean,
    includeStatus: Boolean,
): List<CenterRefresh> = buildList {
    if (includeStatus) add(CenterRefresh.STATUS)
    // A successful recovery changes connected; the page-entry effect then loads the page once.
    if (connected) addAll(page)
}
