/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.house

/**
 * What a tap should look like at once, before the next poll brings the Portal's own (optimistic,
 * then confirmed) state (pure, unit tested). Only simple, reversible looks: on/off, brightness,
 * position, target temperature. Locks, garages and valves show "…ing" so nobody reads a door as
 * locked before it is.
 */
object HouseOptimistic {
    fun apply(s: HouseSnapshot, act: HouseAct): HouseSnapshot {
        val e = act.entity?.let(s::entity) ?: return s
        val next = look(e, act) ?: return s
        return s.copy(entities = s.entities + (e.id to next))
    }

    private fun look(e: HouseEntity, act: HouseAct): HouseEntity? = when (act.action) {
        "power" -> act.on?.let { e.copy(state = if (it) "on" else "off") }
        "brightness" -> (act.value as? Number)?.toInt()?.let { pct ->
            if (pct == 0) e.copy(state = "off") else e.copy(state = "on", attrs = e.attrs + ("brightness" to Math.round(pct * BYTE / PCT)))
        }
        "fan" -> (act.value as? Number)?.toInt()?.let { e.copy(state = if (it == 0) "off" else "on", attrs = e.attrs + ("percentage" to it)) }
        "position" -> (act.value as? Number)?.toInt()?.let { e.copy(attrs = e.attrs + ("current_position" to it)) }
        "temperature" -> (act.value as? Number)?.toDouble()?.let { e.copy(attrs = e.attrs + ("temperature" to it)) }
        "lock" -> e.copy(state = "locking")
        "unlock" -> e.copy(state = "unlocking")
        "open" -> e.copy(state = "opening")
        "close" -> e.copy(state = "closing")
        "play_pause" -> e.copy(state = if (e.state == "playing") "paused" else "playing")
        else -> null
    }

    private const val BYTE = 255.0
    private const val PCT = 100.0
}
