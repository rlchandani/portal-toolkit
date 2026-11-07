/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.viewmodel

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.portalinstaller.data.companion.CompanionClient
import com.portalinstaller.data.companion.FileCompanionSessionStore
import com.portalinstaller.data.list.CompanionListPortal
import com.portalinstaller.data.list.HouseholdListController
import com.portalinstaller.data.list.HouseholdListParser
import com.portalinstaller.data.list.NetworkKind
import com.portalinstaller.data.list.PrefsHouseholdListStore
import kotlinx.coroutines.launch

/**
 * Owns the phone's [HouseholdListController] for the List tab. Sessions are read from the same
 * store the control center uses (every paired Portal). The only background trigger is the
 * system's network-available callback; nothing polls.
 */
class HouseholdListViewModel(app: Application) : AndroidViewModel(app) {
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val sessions = FileCompanionSessionStore.create(app)
    private val client = CompanionClient()

    val controller = HouseholdListController(
        scope = viewModelScope,
        portals = { sessions.load().map { CompanionListPortal(client, it) } },
        store = PrefsHouseholdListStore(app),
        device = { deviceName(app) },
        network = ::networkKind,
    )

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { viewModelScope.launch { controller.onNetworkAvailable() } }
    }

    init {
        controller.start()
        runCatching { connectivity?.registerDefaultNetworkCallback(callback) }
            .onFailure { Log.w(TAG, "network callback unavailable; list syncs on open and refresh only", it) }
    }

    override fun onCleared() {
        runCatching { connectivity?.unregisterNetworkCallback(callback) }
        super.onCleared()
    }

    private fun networkKind(): NetworkKind {
        val caps = connectivity?.activeNetwork?.let { connectivity.getNetworkCapabilities(it) } ?: return NetworkKind.NONE
        return if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            NetworkKind.WIFI else NetworkKind.OTHER
    }

    private companion object {
        const val TAG = "HouseholdList"

        /** The name Portals show under items ("Rohit’s Pixel"): the phone's own device name. */
        fun deviceName(app: Application): String =
            (runCatching { Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull() ?: Build.MODEL)
                .orEmpty().trim().take(HouseholdListParser.MAX_NAME).ifEmpty { "Phone" }
    }
}
