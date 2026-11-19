/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.house

/** What a device row says under its name, and which room it is in (pure, unit tested). */
object HouseWords {
    fun status(e: HouseEntity): String {
        if (e.unavailable) return "Not responding"
        return when (e.kind) {
            HouseKind.LIGHT -> if (e.isOn) e.brightnessPct?.let { "$it%" } ?: "On" else "Off"
            HouseKind.SWITCH, HouseKind.FAN -> if (e.isOn) e.percentage?.let { "On · $it%" } ?: "On" else "Off"
            HouseKind.LOCK -> when (e.state) {
                "locked" -> "Locked"; "unlocked" -> "Unlocked"; "locking" -> "Locking…"; "unlocking" -> "Unlocking…"; "jammed" -> "Jammed"
                else -> pretty(e.state)
            } + (e.changedBy?.let { " · $it" } ?: "")
            HouseKind.GARAGE -> when (e.state) { "opening" -> "Opening…"; "closing" -> "Closing…"; "open", "on" -> "Open"; "closed", "off" -> "Closed"; else -> pretty(e.state) }
            HouseKind.COVER -> e.position?.let { if (it == 0) "Closed" else "$it% open" } ?: pretty(e.state)
            HouseKind.VALVE -> if (e.isOpen || e.isOn) "Water on" else "Shut off"
            HouseKind.CLIMATE -> climate(e)
            HouseKind.MEDIA -> when (e.state) {
                "playing", "paused" -> listOfNotNull(e.mediaTitle, e.mediaArtist).joinToString(" · ").ifBlank { pretty(e.state) }
                else -> pretty(e.state)
            }
            HouseKind.DOOR, HouseKind.WINDOW -> if (e.isOpen) "Open" else "Closed"
            HouseKind.MOTION -> if (e.isOn) "Motion" else "Clear"
            HouseKind.LEAK -> if (e.state == "on") "WATER DETECTED" else "Dry"
            HouseKind.SMOKE -> if (e.state == "on") "DETECTED" else "Clear"
            HouseKind.TEMPERATURE, HouseKind.HUMIDITY, HouseKind.SENSOR -> e.numeric?.let { n -> "${trim(n)}${e.unit?.let { u -> if (u.startsWith("°") || u == "%") u else " $u" } ?: ""}" } ?: pretty(e.state)
            HouseKind.APPLIANCE -> pretty(e.state)
            else -> pretty(e.state)
        }
    }

    private fun climate(e: HouseEntity): String {
        val set = e.targetTemp?.let { "Set ${trim(it)}°" }
        val doing = e.hvacAction?.let { pretty(it) } ?: pretty(e.state)
        val now = e.currentTemp?.let { "${Math.round(it)}° now" }
        return listOfNotNull(set, doing, now).joinToString(" · ")
    }

    /** "71° · 2 on · Sam" under a room's name. */
    fun roomLine(view: HouseRoomView?, people: List<String>): String = listOfNotNull(
        view?.temp?.let { "${Math.round(it)}°" },
        view?.let { if (it.lights == 0) null else if (it.lightsOn == 0) "lights off" else "${it.lightsOn} on" },
        view?.open?.takeIf { it > 0 }?.let { "$it open" },
        people.takeIf { it.isNotEmpty() }?.joinToString(", "),
    ).joinToString(" · ").ifBlank { "No lights or sensors" }

    fun pretty(s: String): String = s.replace('_', ' ').replaceFirstChar { it.uppercase() }

    private fun trim(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else "%.1f".format(v)
}
