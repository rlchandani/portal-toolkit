/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.calls

/** How a Portal looks in the phone's Calls list. */
enum class ContactPresence(val label: String, val callable: Boolean) {
    AVAILABLE("Available · rings this room only", true),
    IN_CALL("In a call", false),
    QUIET("Quiet hours · won’t ring", false),
    PHONES_OFF("Doesn’t take calls from phones", false),
    CHECKING("Checking…", false),
    OFFLINE("Offline", false),
}

/** One paired Portal as a call contact: its session, and what its last poll said. */
data class CallContact(
    val sessionId: String,
    val sessionName: String,
    val portal: PortalInfo? = null,
    val lastOkMs: Long? = null,
) {
    val name: String get() = portal?.name ?: sessionName
    fun ref(): PortalRef? = portal?.let { PortalRef(sessionId, it.id, it.name) }
}

/** Pure contact-list rules (JVM-tested). */
object CallContacts {
    /** The paired sessions changed: keep what we knew about the ones still paired, in pairing order. */
    fun sync(list: List<CallContact>, sessions: List<Pair<String, String>>): List<CallContact> =
        sessions.map { (id, name) -> list.firstOrNull { it.sessionId == id }?.copy(sessionName = name) ?: CallContact(id, name) }

    fun polled(list: List<CallContact>, sessionId: String, portal: PortalInfo, nowMs: Long): List<CallContact> =
        list.map { if (it.sessionId == sessionId) it.copy(portal = portal, lastOkMs = nowMs) else it }

    fun presence(c: CallContact, nowMs: Long): ContactPresence {
        val ok = c.lastOkMs ?: return ContactPresence.CHECKING
        val portal = c.portal ?: return ContactPresence.CHECKING
        return when {
            nowMs - ok > PhoneCallTimings.OFFLINE_AFTER_MS -> ContactPresence.OFFLINE
            !portal.acceptsPhones -> ContactPresence.PHONES_OFF
            portal.inCall -> ContactPresence.IN_CALL
            portal.quiet -> ContactPresence.QUIET
            else -> ContactPresence.AVAILABLE
        }
    }

    /** The Portal's id for a session, from its last poll (rings carry only the name). */
    fun portalId(list: List<CallContact>, sessionId: String): String? = list.firstOrNull { it.sessionId == sessionId }?.portal?.id
}
