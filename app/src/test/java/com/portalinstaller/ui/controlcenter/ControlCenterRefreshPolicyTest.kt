/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.controlcenter

import org.junit.Assert.assertEquals
import org.junit.Test

class ControlCenterRefreshPolicyTest {
    private data class Page(
        val tab: CenterTab,
        val detail: CenterDetail = CenterDetail.NONE,
        val catalog: Boolean = false,
        val requests: List<CenterRefresh> = emptyList(),
    )

    private val pages = listOf(
        Page(CenterTab.HOME),
        Page(CenterTab.REMOTE),
        Page(CenterTab.PHOTOS, requests = listOf(CenterRefresh.SOURCES, CenterRefresh.PHOTOS, CenterRefresh.SETTINGS)),
        Page(CenterTab.APPS, requests = listOf(CenterRefresh.APPS)),
        Page(CenterTab.APPS, catalog = true, requests = listOf(CenterRefresh.CATALOG)),
        Page(CenterTab.SETTINGS, requests = listOf(CenterRefresh.SETTINGS)),
        Page(CenterTab.HOME, CenterDetail.LAYOUT, requests = listOf(CenterRefresh.LAYOUT)),
        Page(CenterTab.HOME, CenterDetail.SCENES, requests = listOf(CenterRefresh.SCENES)),
        Page(CenterTab.HOME, CenterDetail.JARVIS, requests = listOf(CenterRefresh.JARVIS)),
        Page(CenterTab.HOME, CenterDetail.SNAPSHOTS),
        Page(CenterTab.HOME, CenterDetail.OPERATIONS, requests = listOf(CenterRefresh.OPERATIONS)),
    )

    @Test fun enteringEachPageLoadsOnlyItsVisibleData() {
        pages.forEach { page ->
            assertEquals(page.toString(), page.requests, visiblePageRefreshes(page.tab, page.detail, page.catalog))
        }
    }

    @Test fun detailsTakePrecedenceOverEveryUnderlyingTab() {
        val details = listOf(
            CenterDetail.LAYOUT to listOf(CenterRefresh.LAYOUT),
            CenterDetail.SCENES to listOf(CenterRefresh.SCENES),
            CenterDetail.JARVIS to listOf(CenterRefresh.JARVIS),
            CenterDetail.SNAPSHOTS to emptyList(),
            CenterDetail.OPERATIONS to listOf(CenterRefresh.OPERATIONS),
            CenterDetail.GUESTS to emptyList(),
        )
        details.forEach { (detail, expected) ->
            CenterTab.entries.forEach { tab ->
                listOf(false, true).forEach { catalog ->
                    assertEquals("$tab/$detail/catalog=$catalog", expected, visiblePageRefreshes(tab, detail, catalog))
                }
            }
        }
    }

    @Test fun catalogSelectionDoesNotFetchHiddenDataOnOtherTabs() {
        pages.filter { it.tab != CenterTab.APPS }.forEach { page ->
            assertEquals(page.toString(), page.requests, visiblePageRefreshes(page.tab, page.detail, showCatalog = true))
        }
    }

    @Test fun connectedToolbarRefreshIncludesStatusAndVisibleDataExactlyOnce() {
        pages.forEach { page ->
            val requests = centerRefreshRequests(
                visiblePageRefreshes(page.tab, page.detail, page.catalog), connected = true, includeStatus = true)
            assertEquals(page.toString(), listOf(CenterRefresh.STATUS) + page.requests, requests)
            assertEquals(page.toString(), requests.size, requests.distinct().size)
        }
    }

    @Test fun disconnectedToolbarRefreshLeavesPageLoadingToRecovery() {
        pages.forEach { page ->
            val requests = centerRefreshRequests(
                visiblePageRefreshes(page.tab, page.detail, page.catalog), connected = false, includeStatus = true)
            assertEquals(page.toString(), listOf(CenterRefresh.STATUS), requests)
        }
    }

    @Test fun disconnectedPageEntryDoesNotMakeRequests() {
        pages.forEach { page ->
            assertEquals(page.toString(), emptyList<CenterRefresh>(), centerRefreshRequests(
                visiblePageRefreshes(page.tab, page.detail, page.catalog), connected = false, includeStatus = false))
        }
    }

    @Test fun recoveryLoadsVisibleDataOnceWithoutRepeatingStatus() {
        pages.forEach { page ->
            val visible = visiblePageRefreshes(page.tab, page.detail, page.catalog)
            val retry = centerRefreshRequests(visible, connected = false, includeStatus = true)
            val recovered = centerRefreshRequests(visible, connected = true, includeStatus = false)
            assertEquals(page.toString(), listOf(CenterRefresh.STATUS) + page.requests, retry + recovered)
        }
    }
}
