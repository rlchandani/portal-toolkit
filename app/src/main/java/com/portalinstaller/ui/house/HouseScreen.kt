/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.house

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.portalinstaller.data.house.HouseAct
import com.portalinstaller.data.house.HouseReach
import com.portalinstaller.data.house.HouseRepository
import com.portalinstaller.data.house.HouseSnapshot
import com.portalinstaller.data.house.HouseStatus
import com.portalinstaller.data.house.HouseStatusRules
import com.portalinstaller.data.house.HouseUiState
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

/** How often the phone asks the Portal while the screen is open (the answer is tiny when nothing moved). */
private const val POLL_MS = 1_500L
private const val OFFLINE_POLL_MS = 6_000L

/**
 * Control Center on the phone: the same live house as the Portals, through the paired Portal.
 * Map (plan, lenses, rooms, scenes), Tesla, Alerts, Security and Cameras; rooms and devices open
 * as sheets; asking actions confirm on the phone (fingerprint, else a dialog, PIN for disarm).
 */
@Composable
fun HouseScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repository = remember { HouseRepository.create(context) }
    val scope = rememberCoroutineScope()
    val controller = remember { HouseController(repository, scope) }
    val state by repository.state.collectAsStateWithLifecycle()
    var view by rememberSaveable { mutableStateOf(HouseView.MAP) }
    var floor by rememberSaveable { mutableStateOf<String?>(null) }
    var lens by rememberSaveable { mutableStateOf(HouseLens.LIVE) }
    var room by rememberSaveable { mutableStateOf<String?>(null) }
    var device by rememberSaveable { mutableStateOf<String?>(null) }
    var camera by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbars = remember { SnackbarHostState() }
    HousePolling(repository, controller, if (view == HouseView.TESLA) "tesla" else null)
    LaunchedEffect(state.notice?.seq) { state.notice?.let { snackbars.showSnackbar(it.text) } }
    val s = state.snapshot
    val status = HouseStatusRules.status(s, state.reach, state.lastOkAt, System.currentTimeMillis())
    val enabled = HouseStatusRules.canControl(status)
    val alerts = remember(s?.alerts, state.hidden) { s?.alerts.orEmpty().filter { it.id !in state.hidden } }
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            HouseHeader(title(view, s), s?.security?.people.orEmpty(), chips(s, alerts.size), view) { view = it }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp)) {
                statusItems(status, s, state) { controller.wake.trySend(Unit) }
                if (s != null && status != HouseStatus.LOADING) when (view) {
                    HouseView.MAP -> mapItems(state, s, floor, lens, enabled, controller, { floor = it }, { lens = it }, { room = it })
                    HouseView.TESLA -> houseTesla(s.tesla, enabled, { controller.send(HouseAct.car(it)) }, { controller.send(HouseAct.carLimit(it)) })
                    HouseView.ALERTS -> houseAlerts(alerts, enabled, onFix = { a -> fix(s, a.act, a.actEntity)?.let(controller::send) },
                        onHide = { repository.hide(it.id) }, onRoom = { room = it })
                    HouseView.SECURITY -> houseSecurity(s.security, enabled, { panel, mode -> controller.send(HouseAct.alarm(panel, mode)) },
                        { item -> item.action?.let { fix(s, it, item.id) }?.let(controller::send) })
                    HouseView.CAMERAS -> houseCameras(s.cameras, repository) { camera = it }
                }
            }
        }
        SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter))
    }
    if (s != null) HouseOverlays(s, state, repository, controller, enabled, room, device, camera,
        onRoom = { room = it }, onDevice = { device = it }, onCamera = { camera = it })
}

@Composable
private fun HouseOverlays(s: HouseSnapshot, state: HouseUiState, repository: HouseRepository, controller: HouseController, enabled: Boolean,
                          room: String?, device: String?, camera: String?, onRoom: (String?) -> Unit, onDevice: (String?) -> Unit,
                          onCamera: (String?) -> Unit) {
    room?.let { id ->
        HouseRoomSheet(id, state.plan, s, repository, state.pending, enabled, controller::send, { onDevice(it) }, { onCamera(it) }) { onRoom(null) }
    }
    device?.let(s::entity)?.let { e -> HouseDeviceSheet(e, enabled, s.conn.reason, controller::send) { onDevice(null) } }
    camera?.let { id -> s.cameras.firstOrNull { it.id == id } }?.let { cam ->
        HouseCameraFull(cam, repository, enabled, controller::send) { onCamera(null) }
    }
    controller.confirm?.let { p ->
        HouseConfirmSheet(p.confirm, repository, onConfirmed = controller::confirmed, onCancel = controller::cancelConfirm)
    }
}

private fun LazyListScope.statusItems(status: HouseStatus, s: HouseSnapshot?, state: HouseUiState, onRetry: () -> Unit) {
    val portal = s?.portal ?: state.via.orEmpty()
    val lastLive = s?.conn?.lastLiveAt?.takeIf { it > 0 }?.let(HouseTime::clock)
    when {
        s == null || status in FULL_SCREEN -> item(key = "state") {
            HouseStateCard(status, portal, s?.conn?.step.orEmpty(), onRetry)
        }
        else -> HouseStatusRules.banner(status, portal, lastLive)?.let { text ->
            item(key = "banner") { Box(Modifier.padding(bottom = 12.dp)) { HouseBanner(text, danger = status != HouseStatus.STALE) } }
        }
    }
    if (state.failover && s != null) item(key = "via") { Box(Modifier.padding(bottom = 12.dp)) { HouseBanner("Showing the house through ${state.via}", danger = false) } }
}


private val FULL_SCREEN = setOf(HouseStatus.LOADING, HouseStatus.NO_PORTAL, HouseStatus.NEEDS_UPDATE, HouseStatus.NOT_SET_UP, HouseStatus.CONNECTING)

private fun LazyListScope.mapItems(state: HouseUiState, s: HouseSnapshot, floor: String?, lens: HouseLens, enabled: Boolean, controller: HouseController,
                                   onFloor: (String) -> Unit, onLens: (HouseLens) -> Unit, onRoom: (String) -> Unit) {
    val model = mapModel(state.plan, s, floor)
    houseMap(state.plan, s, model, floor, lens, enabled, onFloor, onLens, onRoom, controller::send,
        onScene = { controller.send(HouseAct.run(it)) },
        onRoomLights = { id, on ->
            val lights = HouseRoomLogic.lights(state.plan, s, id).filter { it.isOn != on }
            controller.sendAll(lights.map { HouseAct.power(it, on) }, "${state.plan?.room(id)?.name ?: "Room"} lights ${if (on) "on" else "off"}")
        })
}

private fun fix(s: HouseSnapshot, key: String?, entity: String?): HouseAct? {
    val e = entity?.let(s::entity) ?: return null
    return key?.let { HouseAct.fix(it, e) }
}

private fun title(view: HouseView, s: HouseSnapshot?): String = when (view) {
    HouseView.MAP -> "Home"
    HouseView.TESLA -> s?.tesla?.label ?: "Tesla"
    HouseView.ALERTS -> "Alerts"
    HouseView.SECURITY -> "Security"
    HouseView.CAMERAS -> "Cameras"
}

private fun chips(s: HouseSnapshot?, alerts: Int): List<HouseChip> = listOfNotNull(
    HouseChip(HouseView.MAP, "Map"),
    s?.tesla?.let { HouseChip(HouseView.TESLA, it.label) },
    HouseChip(HouseView.ALERTS, if (alerts > 0) "Alerts · $alerts" else "Alerts"),
    HouseChip(HouseView.SECURITY, "Security"),
    s?.cameras?.takeIf { it.isNotEmpty() }?.let { HouseChip(HouseView.CAMERAS, "Cameras") },
)

/** Polls while the app is started; a finished control wakes it early. Backs off while no Portal answers. */
@Composable
private fun HousePolling(repository: HouseRepository, controller: HouseController, want: String?) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val wantNow by rememberUpdatedState(want)
    LaunchedEffect(repository, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                repository.poll(wantNow)
                val wait = if (repository.state.value.reach == HouseReach.OK) POLL_MS else OFFLINE_POLL_MS
                withTimeoutOrNull(wait) { controller.wake.receive() }
            }
        }
    }
}
