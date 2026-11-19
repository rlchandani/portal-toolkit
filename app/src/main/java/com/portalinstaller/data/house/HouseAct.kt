/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.house

import androidx.compose.runtime.Immutable
import org.json.JSONObject

/**
 * One control request in the Portal's vocabulary (`CcRemoteActions`): an entity the Portal
 * watches plus an action and its value. The phone never names a Home Assistant service; the
 * Portal builds the call with its own builders and policy. [label] is the toast on success.
 */
@Immutable
data class HouseAct(
    val action: String,
    val entity: String? = null,
    val label: String,
    val on: Boolean? = null,
    val value: Number? = null,
    val text: String? = null,
    val mode: String? = null,
    val key: String? = null,
) {
    /** The request body; a confirm [ticket] and an alarm [code] are added only to the confirmed resend. */
    fun body(ticket: String? = null, code: String? = null): JSONObject = JSONObject().put("action", action).apply {
        entity?.let { put("entity", it) }
        on?.let { put("on", it) }
        value?.let { put("value", it) }
        text?.let { put("value", it) }
        mode?.let { put("mode", it) }
        key?.let { put("key", it) }
        ticket?.let { put("ticket", it) }
        code?.takeIf { it.isNotEmpty() }?.let { put("code", it) }
    }

    override fun toString() = "HouseAct(action=$action, entity=$entity)"

    companion object {
        fun power(e: HouseEntity, on: Boolean) = HouseAct("power", e.id, "${e.name} ${if (on) "on" else "off"}", on = on)
        fun brightness(e: HouseEntity, pct: Int) = HouseAct("brightness", e.id, "${e.name} $pct%", value = pct.coerceIn(0, 100))
        fun kelvin(e: HouseEntity, k: Int) = HouseAct("kelvin", e.id, "${e.name} white", value = k.coerceIn(e.minKelvin, e.maxKelvin))
        fun color(e: HouseEntity, rgb: Int) = HouseAct("color", e.id, "${e.name} colour", value = rgb and 0xFFFFFF)
        fun fan(e: HouseEntity, pct: Int) = HouseAct("fan", e.id, "${e.name} $pct%", value = pct.coerceIn(0, 100))
        fun lock(e: HouseEntity) = HouseAct("lock", e.id, "${e.name} locked")
        fun unlock(e: HouseEntity) = HouseAct("unlock", e.id, "${e.name} unlocked")
        fun open(e: HouseEntity) = HouseAct("open", e.id, "${e.name} opening")
        fun close(e: HouseEntity) = HouseAct("close", e.id, "${e.name} closing")
        fun stop(e: HouseEntity) = HouseAct("stop", e.id, "${e.name} stopped")
        fun position(e: HouseEntity, pct: Int) = HouseAct("position", e.id, "${e.name} $pct% open", value = pct.coerceIn(0, 100))
        fun temperature(e: HouseEntity, t: Double) = HouseAct("temperature", e.id, "${e.name} ${format(t)}°", value = t.coerceIn(e.minTemp, e.maxTemp))
        fun hvac(e: HouseEntity, mode: String) = HouseAct("hvac", e.id, "${e.name} ${mode.replace('_', ' ')}", text = mode)
        fun playPause(e: HouseEntity) = HouseAct("play_pause", e.id, e.name)
        fun next(e: HouseEntity) = HouseAct("next", e.id, e.name)
        fun previous(e: HouseEntity) = HouseAct("previous", e.id, e.name)
        fun volume(e: HouseEntity, v: Float) = HouseAct("volume", e.id, e.name, value = Math.round(v.coerceIn(0f, 1f) * 100) / 100.0)
        fun run(scene: HouseScene) = HouseAct("run", scene.id, scene.name)
        fun alarm(panel: HouseAlarm, mode: String) = HouseAct("alarm", panel.id, ALARM_WORDS[mode] ?: mode, mode = mode)
        fun cameraRefresh(cameraId: String) = HouseAct("camera_refresh", cameraId, "Asked for a new picture")
        fun car(a: HouseCarAction) = HouseAct("car", label = a.label, key = a.key)
        fun carLimit(pct: Int) = HouseAct("car_limit", label = "Charge limit $pct%", value = pct)

        /** An alert's or a Security row's one fix, by the Portal's key (close, power_off, lock). */
        fun fix(key: String, e: HouseEntity): HouseAct? = when (key) {
            "close" -> close(e)
            "power_off" -> power(e, false)
            "lock" -> lock(e)
            else -> null
        }

        private val ALARM_WORDS = mapOf("disarm" to "Disarmed", "home" to "Armed · Home", "away" to "Armed · Away",
            "night" to "Armed · Night", "vacation" to "Armed · Vacation")

        private fun format(t: Double) = if (t % 1.0 == 0.0) t.toInt().toString() else "%.1f".format(t)
    }
}
