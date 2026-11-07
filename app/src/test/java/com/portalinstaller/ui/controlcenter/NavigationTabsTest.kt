package com.portalinstaller.ui.controlcenter

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationTabsTest {
    @Test fun barShowsTheHouseholdListAndReachesAppsFromHomeInstead() {
        assertEquals(listOf(CenterTab.HOME, CenterTab.REMOTE, CenterTab.PHOTOS, CenterTab.LIST, CenterTab.SETTINGS), navigationTabs)
    }

    @Test fun listTabLetsItsScreenOwnRefresh() {
        assertEquals(emptyList<CenterRefresh>(), visiblePageRefreshes(CenterTab.LIST, CenterDetail.NONE, showCatalog = false))
    }
}
