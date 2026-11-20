/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.calls

/** Calling 2b copy on the phone, from the approved mock where it says (pure, JVM-tested). */
object PhoneCallCopy {
    data class Ring(val title: String, val sub: String, val answer: String)

    /**
     * Mock `phgroup`: "Group video call" / "Sam added you to a call with Grandma Rose". A move
     * ("To phone" on the Portal): "Continue on this phone" / "Kitchen is handing you its call".
     */
    fun ring(call: PhoneCall): Ring {
        val kind = if (call.video) "video call" else "audio call"
        val others = call.invited.map { it.name }
        return when {
            call.move != null -> Ring("Continue on this phone",
                "${call.portal.name} is handing you its $kind" + if (others.isEmpty()) "" else " with ${names(others)}", "Pick up")
            others.isNotEmpty() -> Ring("Group $kind", "${call.inviter ?: call.portal.name} added you to a call with ${names(others)}",
                "Answer")
            else -> Ring(call.portal.name, if (call.video) "Portal video call" else "Portal audio call", "Answer")
        }
    }

    /** The in-call title: "Kitchen" or "Kitchen, Den and you". */
    fun title(call: PhoneCall): String = if (!call.group) call.portal.name else names(call.members.map { it.name } + "you")

    /** Mock tile labels: "Rose · Kitchen Portal". */
    fun tile(p: PortalRef) = "${p.name} · Portal"

    fun movingLine(call: PhoneCall): String? {
        val move = call.move ?: return null
        val name = move.target?.name ?: return null
        return when {
            move.ringing -> "Ringing $name · pick up there to move the call"
            move.targetLiveMs == null -> "Moving to $name…"
            else -> "$name is in · this phone leaves in a moment"
        }
    }

    /** Mock scene `handoff`, from the phone's side. */
    fun movedTitle(ended: EndedPhoneCall) = "Call moved to ${ended.movedTo?.name ?: "your Portal"}"
    const val MOVED_BODY = "This phone’s camera and mic are off. The call carries on, still end-to-end encrypted."
    const val MOVE_BACK = "Move it back here"

    fun names(list: List<String>): String = when (list.size) {
        0 -> ""
        1 -> list[0]
        else -> list.dropLast(1).joinToString(", ") + " and " + list.last()
    }
}
