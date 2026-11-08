/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.guests

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.portalinstaller.data.guests.GuestNetworks
import com.portalinstaller.data.guests.NearbyNetwork
import com.portalinstaller.data.guests.ScanEntry
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The phone's Wi-Fi scan and connected network for the guest card picker. Every call is
 * failure-tolerant: a missing permission or a throttled scan reads as "nothing new".
 */
internal class GuestWifiScanner(context: Context) {
    private val app = context.applicationContext
    private val wifi = app.getSystemService(WifiManager::class.java)
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val location = app.getSystemService(LocationManager::class.java)

    val permissions: Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }.toTypedArray()

    /** Scan results and the connected SSID both need precise location on every supported release. */
    fun hasPermission(): Boolean = granted(Manifest.permission.ACCESS_FINE_LOCATION) &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(Manifest.permission.NEARBY_WIFI_DEVICES))

    fun locationOn(): Boolean = location?.let(LocationManagerCompat::isLocationEnabled) ?: false

    /** Asks for a fresh scan; false when Android throttled it (the cached results still show). */
    @Suppress("DEPRECATION")
    fun startScan(): Boolean = runCatching { wifi?.startScan() == true }.getOrDefault(false)

    suspend fun networks(): List<NearbyNetwork> = GuestNetworks.fromScan(results(), connectedSsid())

    @Suppress("DEPRECATION") // ScanResult.SSID is the plain-string form we need on every API level.
    private fun results(): List<ScanEntry> = try {
        wifi?.scanResults.orEmpty().map { ScanEntry(it.SSID.orEmpty(), it.capabilities.orEmpty(), it.level) }
    } catch (e: SecurityException) {
        Log.w(TAG, "scan results unavailable: ${e.javaClass.simpleName}")
        emptyList()
    }

    private suspend fun connectedSsid(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) connectedSsidModern() else connectedSsidLegacy()

    @Suppress("DEPRECATION")
    private fun connectedSsidLegacy(): String? =
        runCatching { GuestNetworks.cleanConnectedSsid(wifi?.connectionInfo?.ssid) }.getOrNull()

    /** API 31+: the SSID is only in a location-flagged network callback's [WifiInfo]. */
    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun connectedSsidModern(): String? = withTimeoutOrNull(CONNECTED_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            val manager = connectivity ?: return@suspendCancellableCoroutine cont.resume(null)
            val callback = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val ssid = GuestNetworks.cleanConnectedSsid((caps.transportInfo as? WifiInfo)?.ssid)
                    runCatching { manager.unregisterNetworkCallback(this) }
                    if (cont.isActive) cont.resume(ssid)
                }
            }
            val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
            val registered = runCatching { manager.registerNetworkCallback(request, callback) }.isSuccess
            if (!registered) cont.resume(null)
            // Not on Wi-Fi: no callback arrives, the timeout cancels and this unregisters.
            cont.invokeOnCancellation { runCatching { manager.unregisterNetworkCallback(callback) } }
        }
    }

    /** Calls [onScan] whenever new scan results arrive; returns the unregister action. */
    fun observeScans(onScan: () -> Unit): () -> Unit {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = onScan()
        }
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        return { runCatching { app.unregisterReceiver(receiver) } }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "ToolkitGuestWifi"
        const val CONNECTED_TIMEOUT_MS = 1_500L
    }
}
