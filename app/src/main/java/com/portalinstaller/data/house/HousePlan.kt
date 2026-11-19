/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.house

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/** The floor plan shared by every Portal (Hearthpane's `CcPlan`): a 48 × 30 grid per floor. */
@Immutable
data class HousePlan(val floors: List<HouseFloor>, val updatedAt: Long) {
    val isEmpty: Boolean get() = floors.all { it.rooms.isEmpty() }
    fun floor(id: String?): HouseFloor? = floors.firstOrNull { it.id == id } ?: floors.firstOrNull()
    fun room(id: String): HouseRoom? = floors.asSequence().flatMap { it.rooms.asSequence() }.firstOrNull { it.id == id }

    companion object {
        const val GRID_W = 48
        const val GRID_H = 30
    }
}

@Immutable
data class HouseFloor(val id: String, val name: String, val rooms: List<HouseRoom>, val openings: List<HouseOpening>)

@Immutable
data class HouseRoom(val id: String, val name: String, val x: Int, val y: Int, val w: Int, val h: Int, val outdoor: Boolean,
                     val devices: List<HousePlaced>)

@Immutable
data class HousePlaced(val entityId: String, val fx: Float, val fy: Float)

/** A door/window/garage on one wall ([side] N/E/S/W) of a room, at [at] with [span] (0..1 of the wall). */
@Immutable
data class HouseOpening(val entityId: String, val roomId: String, val side: Char, val at: Float, val span: Float)

/** Tolerant read of `CcPlanJson`: bad rooms are dropped, positions clamped into the grid. */
object HousePlanCodec {
    private const val MAX_FLOORS = 8
    private const val MAX_ROOMS = 80
    private const val MAX_DEVICES = 120
    private const val MAX_OPENINGS = 120
    private const val RECT_PARTS = 4

    fun decode(json: JSONObject?): HousePlan? {
        val floors = json?.optJSONArray("floors") ?: return null
        return HousePlan(objects(floors).take(MAX_FLOORS).mapNotNull(::floor), json.optLong("updatedAt"))
    }

    private fun floor(o: JSONObject): HouseFloor? {
        val id = o.optString("id").ifBlank { return null }
        val rooms = objects(o.optJSONArray("rooms")).take(MAX_ROOMS).mapNotNull(::room)
        val ids = rooms.map { it.id }.toSet()
        val openings = objects(o.optJSONArray("openings")).take(MAX_OPENINGS).mapNotNull(::opening).filter { it.roomId in ids }
        return HouseFloor(id, o.optString("name").ifBlank { id }, rooms, openings)
    }

    private fun room(o: JSONObject): HouseRoom? {
        val id = o.optString("id").ifBlank { return null }
        val r = o.optJSONArray("r")?.takeIf { it.length() == RECT_PARTS } ?: return null
        val w = r.optInt(2, 1).coerceIn(1, HousePlan.GRID_W)
        val h = r.optInt(3, 1).coerceIn(1, HousePlan.GRID_H)
        val x = r.optInt(0).coerceIn(0, HousePlan.GRID_W - w)
        val y = r.optInt(1).coerceIn(0, HousePlan.GRID_H - h)
        val devices = objects(o.optJSONArray("d")).take(MAX_DEVICES).mapNotNull { d ->
            d.optString("e").takeIf { it.contains('.') }?.let { HousePlaced(it, frac(d.optDouble("x", 0.5)), frac(d.optDouble("y", 0.5))) }
        }
        return HouseRoom(id, o.optString("name").ifBlank { id }, x, y, w, h, o.optBoolean("out"), devices)
    }

    private fun opening(o: JSONObject): HouseOpening? {
        val id = o.optString("e").takeIf { it.contains('.') } ?: return null
        val side = o.optString("side").singleOrNull()?.takeIf { it in "NESW" } ?: return null
        return HouseOpening(id, o.optString("room"), side, frac(o.optDouble("at", 0.5)), frac(o.optDouble("span", 0.3)))
    }

    private fun frac(v: Double): Float = (if (v.isFinite()) v else 0.5).toFloat().coerceIn(0f, 1f)

    internal fun objects(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
}
