/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.house

import androidx.compose.runtime.Immutable

/** The Portal's Home Assistant connection as it reported it (Hearthpane's `CcConnection`). */
@Immutable
data class HouseConn(val phase: String = "connecting", val step: String = "", val lastLiveAt: Long = 0L,
                     val retryAt: Long = 0L, val reason: String? = null) {
    val live: Boolean get() = phase == "live"
}

@Immutable
data class HouseAsks(val unlock: Boolean = true, val garage: Boolean = true, val valve: Boolean = true, val scenes: Boolean = true)

/** An alert card; [act]/[actEntity] is the one fix the Portal offers (close the garage, shut the water). */
@Immutable
data class HouseAlert(
    val id: String, val severity: String, val kind: String, val entityId: String, val title: String, val body: String,
    val since: Long, val icon: String, val action: String, val label: String?, val act: String?, val actEntity: String?,
    val room: String?,
) {
    val critical: Boolean get() = severity == "critical"
    val warning: Boolean get() = severity == "warning"
    /** Dismiss / Snooze / Remind hide the card on this phone only. */
    val hidesLocally: Boolean get() = action in setOf("dismiss", "snooze", "remind")
}

@Immutable
data class HouseSecItem(val id: String, val name: String, val area: String?, val state: String, val since: Long,
                        val attention: Boolean, val unavailable: Boolean, val action: String?)

@Immutable
data class HouseSecSection(val group: String, val title: String, val icon: String, val tag: String, val ok: Boolean?,
                           val summary: String, val items: List<HouseSecItem>)

@Immutable
data class HouseAlarm(val id: String, val state: String, val title: String, val subtitle: String, val tone: String,
                      val code: Boolean, val modes: List<String>, val current: String?)

@Immutable
data class HousePerson(val id: String, val name: String, val home: Boolean, val line: String)

@Immutable
data class HouseCheck(val icon: String, val title: String, val detail: String, val tag: String?, val ok: Boolean?)

@Immutable
data class HouseSecurity(
    val alarm: HouseAlarm? = null,
    val readinessTitle: String = "",
    val readinessLine: String? = null,
    val sections: List<HouseSecSection> = emptyList(),
    val people: List<HousePerson> = emptyList(),
    val checks: List<HouseCheck> = emptyList(),
)

@Immutable
data class HouseCamera(val id: String, val name: String, val canStream: Boolean, val captured: Long?, val platform: String?,
                       val why: String, val refresh: Boolean, val unavailable: Boolean, val room: String?)

@Immutable
data class HouseScene(val id: String, val name: String, val tone: String, val icon: String, val sensitive: Boolean)

@Immutable
data class HouseGlow(val fx: Float, val fy: Float, val rgb: Int, val strength: Float)

/** A person the Portal placed in a room (only when the household shows names and they allowed it). */
@Immutable
data class HouseRoomPerson(val key: String, val name: String, val initial: String, val rgb: Int)

/**
 * A room's live summary from the Portal (Hearthpane's `CcRoomView`). [occupancy] 0..1 is the Portal's
 * fused "someone is here" (0 = nobody, or the household turned it off); [people] are named chips the
 * Portal's privacy policy already allowed. An older Portal sends neither.
 */
@Immutable
data class HouseRoomView(val id: String, val floor: String, val lightsOn: Int, val lights: Int, val temp: Double?,
                         val humidity: Int?, val motion: Boolean, val open: Int, val powerW: Double?, val climate: String?,
                         val glows: List<HouseGlow>, val occupancy: Float = 0f, val people: List<HouseRoomPerson> = emptyList())

@Immutable
data class HouseCarRow(val icon: String, val label: String, val value: String, val tone: String)

@Immutable
data class HouseCarAction(val key: String, val icon: String, val label: String, val on: Boolean, val confirm: Boolean)

@Immutable
data class HouseCharger(val source: String, val powerKw: Double?, val sessionKwh: Double?, val status: String?, val cost: Double?)

@Immutable
data class HouseChargeWeek(val kwh: List<Double>, val days: List<String>, val price: Double?)

@Immutable
data class HouseTesla(
    val label: String, val model: String?, val life: String, val paintTop: Int, val paintBottom: Int, val paintName: String,
    val battery: Int?, val range: String?, val headline: String, val charging: Boolean, val powerKw: Double?,
    val addedKwh: Double?, val toFull: String?, val limit: Int?, val limitMin: Int, val limitMax: Int, val limitSettable: Boolean,
    val idleTitle: String, val idleDetail: String?, val rows: List<HouseCarRow>, val actions: List<HouseCarAction>,
    val charger: HouseCharger?, val week: HouseChargeWeek?,
)

/** Everything one poll of the paired Portal said, merged with what the phone already held. */
@Immutable
data class HouseSnapshot(
    val rev: Long = 0L,
    val minute: Long = -1L,
    val now: Long = 0L,
    val portal: String = "",
    val conn: HouseConn = HouseConn(),
    val asks: HouseAsks = HouseAsks(),
    val fahrenheit: Boolean = false,
    val planAt: Long = -1L,
    val hasPlan: Boolean = false,
    val entities: Map<String, HouseEntity> = emptyMap(),
    val alerts: List<HouseAlert> = emptyList(),
    val security: HouseSecurity = HouseSecurity(),
    val cameras: List<HouseCamera> = emptyList(),
    val scenes: List<HouseScene> = emptyList(),
    val rooms: List<HouseRoomView> = emptyList(),
    val tesla: HouseTesla? = null,
    /** The Portal's version of each derived section held here (`sv`); empty for an older Portal. */
    val sectionVersions: Map<String, String> = emptyMap(),
) {
    fun entity(id: String): HouseEntity? = entities[id]
    fun room(id: String): HouseRoomView? = rooms.firstOrNull { it.id == id }
}

/** The Portal asking first: a one-time [ticket] for exactly the call the phone sent. */
@Immutable
data class HouseConfirm(val ask: String, val ticket: String, val title: String, val body: String, val button: String,
                        val code: Boolean, val camera: String?) {
    val pin: Boolean get() = ask == "pin"
    val withCamera: Boolean get() = ask == "camera_confirm"
}
