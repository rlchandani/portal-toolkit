/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.list

import org.junit.Assert.assertEquals
import org.junit.Test

class HouseholdListStatusTest {
    private fun line(reached: List<String>, unreached: List<String> = emptyList(), pending: Int = 0,
                     network: NetworkKind = NetworkKind.WIFI) =
        HouseholdListStatus.line(reached, unreached, pending, network)

    @Test fun upToDateCopy() {
        assertEquals("Up to date with both Portals", line(listOf("Family Area", "Living Area")).text)
        assertEquals("Up to date with Family Area", line(listOf("Family Area")).text)
        assertEquals("Up to date with all 3 Portals", line(listOf("A", "B", "C")).text)
        assertEquals(StatusKind.OK, line(listOf("Family Area")).kind)
    }

    @Test fun partialCopy() {
        val s = line(listOf("Family Area"), listOf("Living Area"))
        assertEquals("Up to date with Family Area. Living Area is offline and will catch up.", s.text)
        assertEquals(StatusKind.PARTIAL, s.kind)
    }

    @Test fun awayAndOfflineCopy() {
        assertEquals("Away from home. 2 changes waiting to send.", line(emptyList(), listOf("Family Area"), 2, NetworkKind.OTHER).text)
        assertEquals("Away from home. 1 change waiting to send.", line(emptyList(), listOf("Family Area"), 1, NetworkKind.WIFI).text)
        assertEquals("Away from home. The list may be out of date.", line(emptyList(), listOf("Family Area"), 0, NetworkKind.OTHER).text)
        assertEquals(StatusKind.AWAY, line(emptyList(), listOf("Family Area"), 0, NetworkKind.OTHER).kind)
        val none = line(emptyList(), listOf("Family Area"), 3, NetworkKind.NONE)
        assertEquals("No connection", none.text)
        assertEquals(StatusKind.OFFLINE, none.kind)
    }

    @Test fun noPortalsPaired() {
        assertEquals("Pair a Portal to share the list", line(emptyList()).text)
    }

    @Test fun sentSnackCopy() {
        assertEquals("Sent 2 changes to both Portals.", HouseholdListStatus.sent(2, listOf("Family Area", "Living Area")))
        assertEquals("Sent 1 change to Family Area.", HouseholdListStatus.sent(1, listOf("Family Area")))
    }
}
