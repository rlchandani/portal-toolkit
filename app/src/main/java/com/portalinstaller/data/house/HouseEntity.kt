/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.house

import androidx.compose.runtime.Immutable

/**
 * One Home Assistant entity as the paired Portal hands it to the phone: id, state, name, last
 * change and only the attributes a control reads (the Portal allow-lists them; no tokens, no GPS).
 * Mirrors Hearthpane's `CcEntity` readers so both show the same values.
 */
@Immutable
data class HouseEntity(
    val id: String,
    val state: String,
    val name: String,
    val lastChanged: Long = 0L,
    val attrs: Map<String, Any?> = emptyMap(),
) {
    val domain: String get() = id.substringBefore('.', "")
    val deviceClass: String? get() = attrs["device_class"] as? String
    val unit: String? get() = attrs["unit_of_measurement"] as? String
    val unavailable: Boolean get() = state == "unavailable" || state == "unknown" || state.isEmpty()
    val isOn: Boolean get() = state in ON_STATES

    val isOpen: Boolean
        get() = when (domain) {
            "cover" -> state == "open" || state == "opening" || state == "closing"
            "valve" -> state == "open" || state == "opening"
            "lock" -> state == "unlocked" || state == "open" || state == "unlocking"
            "binary_sensor" -> deviceClass in OPENING_CLASSES && state == "on"
            else -> false
        }

    val numeric: Double? get() = state.toDoubleOrNull()
    val brightnessPct: Int? get() = num("brightness")?.let { Math.round(it * PCT / BYTE).toInt() }
    val rgb: Int?
        get() = (attrs["rgb_color"] as? List<*>)?.takeIf { it.size == RGB }?.let { c ->
            val v = c.map { (it as? Number)?.toInt()?.coerceIn(0, BYTE.toInt()) ?: return null }
            (v[0] shl 16) or (v[1] shl 8) or v[2]
        }
    val colorTempK: Int? get() = num("color_temp_kelvin")?.toInt()
    val colorModes: List<String> get() = (attrs["supported_color_modes"] as? List<*>)?.filterIsInstance<String>().orEmpty()
    val supportsColor: Boolean get() = colorModes.any { it in COLOR_MODES }
    val supportsColorTemp: Boolean get() = "color_temp" in colorModes || supportsColor && attrs.containsKey("min_color_temp_kelvin")
    val dimmable: Boolean get() = colorModes.any { it != "onoff" } || attrs.containsKey("brightness")
    val minKelvin: Int get() = num("min_color_temp_kelvin")?.toInt() ?: DEFAULT_MIN_K
    val maxKelvin: Int get() = num("max_color_temp_kelvin")?.toInt() ?: DEFAULT_MAX_K

    val currentTemp: Double? get() = num("current_temperature")
    val targetTemp: Double? get() = num("temperature")
    val minTemp: Double get() = num("min_temp") ?: if ((targetTemp ?: 0.0) > CELSIUS_CEILING) MIN_F else MIN_C
    val maxTemp: Double get() = num("max_temp") ?: if ((targetTemp ?: 0.0) > CELSIUS_CEILING) MAX_F else MAX_C
    val tempStep: Double get() = num("target_temp_step")?.takeIf { it > 0 } ?: 1.0
    val hvacModes: List<String> get() = (attrs["hvac_modes"] as? List<*>)?.filterIsInstance<String>().orEmpty()
    val hvacAction: String? get() = attrs["hvac_action"] as? String
    val humidity: Int? get() = (num("current_humidity") ?: num("humidity"))?.toInt()

    val position: Int? get() = num("current_position")?.toInt() ?: when (state) { "open" -> PCT.toInt(); "closed" -> 0; else -> null }
    val percentage: Int? get() = num("percentage")?.toInt()
    val mediaTitle: String? get() = attrs["media_title"] as? String
    val mediaArtist: String? get() = (attrs["media_artist"] ?: attrs["media_album_name"]) as? String
    val volume: Float? get() = num("volume_level")?.toFloat()
    val changedBy: String? get() = (attrs["changed_by"] as? String)?.takeIf { it.isNotBlank() }
    val battery: Int? get() = num("battery_level")?.toInt()

    val kind: HouseKind get() = HouseKind.of(this)

    private fun num(key: String): Double? = (attrs[key] as? Number)?.toDouble()

    companion object {
        private const val PCT = 100.0
        private const val BYTE = 255.0
        private const val RGB = 3
        private const val CELSIUS_CEILING = 40.0
        private const val MIN_C = 7.0
        private const val MAX_C = 35.0
        private const val MIN_F = 45.0
        private const val MAX_F = 95.0
        const val DEFAULT_MIN_K = 2000
        const val DEFAULT_MAX_K = 6500
        private val ON_STATES = setOf("on", "playing", "open", "heat", "cool", "heat_cool", "auto", "dry", "fan_only")
        val OPENING_CLASSES = setOf("door", "window", "garage_door", "opening", "garage")
        private val COLOR_MODES = setOf("hs", "rgb", "rgbw", "rgbww", "xy")
    }
}

/** What a row does with an entity (Hearthpane's `CcKind`, the subset the phone draws). */
enum class HouseKind(val glyph: String, val order: Int) {
    LIGHT("bulb", 0), SWITCH("power", 4), FAN("fan", 3), LOCK("lock", 1), GARAGE("garage", 1), COVER("blinds", 2),
    CLIMATE("therm", 2), MEDIA("tv", 5), CAMERA("cam", 9), VALVE("valve", 1), ALARM("shield", 1),
    DOOR("door", 6), WINDOW("window", 6), MOTION("motion", 7), LEAK("drop", 6), SMOKE("warn", 6),
    TEMPERATURE("therm", 8), HUMIDITY("drop", 8), APPLIANCE("washer", 5), SCENE("sparkle", 9), SENSOR("dot", 9);

    val controllable: Boolean get() = this in CONTROLLABLE

    companion object {
        private val CONTROLLABLE = setOf(LIGHT, SWITCH, FAN, LOCK, GARAGE, COVER, CLIMATE, MEDIA, VALVE, APPLIANCE)
        private val APPLIANCE_HINTS = listOf("washer", "washing", "dryer", "dishwasher", "oven")

        fun of(e: HouseEntity): HouseKind = when (e.domain) {
            "light" -> LIGHT
            "switch", "input_boolean" -> if (APPLIANCE_HINTS.any { it in e.id }) APPLIANCE else SWITCH
            "fan" -> FAN
            "lock" -> LOCK
            "cover" -> if (e.deviceClass == "garage" || e.deviceClass == "gate") GARAGE else COVER
            "climate" -> CLIMATE
            "media_player" -> MEDIA
            "camera" -> CAMERA
            "valve" -> VALVE
            "alarm_control_panel" -> ALARM
            "scene", "script" -> SCENE
            "binary_sensor" -> when (e.deviceClass) {
                "door", "opening" -> DOOR
                "garage_door", "garage" -> GARAGE
                "window" -> WINDOW
                "motion", "occupancy", "presence" -> MOTION
                "moisture" -> LEAK
                "smoke", "gas", "carbon_monoxide", "safety" -> SMOKE
                else -> SENSOR
            }
            "sensor" -> when (e.deviceClass) {
                "temperature" -> TEMPERATURE
                "humidity" -> HUMIDITY
                else -> if (APPLIANCE_HINTS.any { it in e.id }) APPLIANCE else SENSOR
            }
            else -> SENSOR
        }
    }
}
