/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallContactsTest {
    private val t = 5_000_000L
    private fun info(inCall: Boolean = false, quiet: Boolean = false, phones: Boolean = true) =
        PortalInfo("p1", "Kitchen", inCall, quiet, phones)

    @Test fun syncFollowsPairingsAndKeepsKnowledge() {
        val polled = CallContacts.polled(CallContacts.sync(emptyList(), listOf("s1" to "Kitchen Portal")), "s1", info(), t)
        val again = CallContacts.sync(polled, listOf("s1" to "Kitchen (renamed)", "s2" to "Den"))
        assertEquals(listOf("s1", "s2"), again.map { it.sessionId })
        assertEquals("Kitchen", again[0].name) // the Portal's own room name wins once heard
        assertEquals("Den", again[1].name)
        assertEquals("p1", CallContacts.portalId(again, "s1"))
        assertNull(CallContacts.portalId(again, "s2"))
        assertTrue(CallContacts.sync(again, emptyList()).isEmpty())
    }

    @Test fun presence() {
        val c = CallContact("s1", "K")
        assertEquals(ContactPresence.CHECKING, CallContacts.presence(c, t))
        assertNull(c.ref())
        fun p(i: PortalInfo, at: Long = t) = CallContacts.presence(c.copy(portal = i, lastOkMs = t), at)
        assertEquals(ContactPresence.AVAILABLE, p(info()))
        assertEquals(ContactPresence.IN_CALL, p(info(inCall = true)))
        assertEquals(ContactPresence.QUIET, p(info(quiet = true)))
        assertEquals(ContactPresence.PHONES_OFF, p(info(phones = false, inCall = true)))
        assertEquals(ContactPresence.OFFLINE, p(info(), t + PhoneCallTimings.OFFLINE_AFTER_MS + 1))
        assertTrue(ContactPresence.AVAILABLE.callable)
        assertTrue(ContactPresence.entries.filter { it.callable } == listOf(ContactPresence.AVAILABLE))
        assertEquals(PortalRef("s1", "p1", "Kitchen"), c.copy(portal = info()).ref())
        assertEquals(ContactPresence.CHECKING, CallContacts.presence(c.copy(lastOkMs = t), t))
    }
}
