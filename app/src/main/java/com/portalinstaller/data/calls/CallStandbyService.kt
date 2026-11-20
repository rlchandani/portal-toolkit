/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.calls

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.portalinstaller.data.companion.FileCompanionSessionStore
import com.portalinstaller.data.companion.PrefsHouseholdKeySource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The owner's choice: "Ring when Portal Toolkit is closed". Off by default. */
object CallStandby {
    private const val PREFS = "call_standby"
    private const val KEY_ON = "on"
    private val mutableOn = MutableStateFlow(false)
    val on: StateFlow<Boolean> = mutableOn.asStateFlow()

    fun load(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false).also { mutableOn.value = it }

    fun set(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, value).apply()
        mutableOn.value = value
        if (value) CallStandbyService.start(context) else CallStandbyService.stop(context)
    }

    /** Whether Android will leave our network alone in deep Doze (the owner may allow it; optional). */
    fun ignoresBatteryOptimisations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
}

/**
 * The opt-in background ringing service ([CallStandbyPolicy]). A `connectedDevice` foreground
 * service: it talks to the Portals on the home network and shows a quiet, permanent notification
 * saying so. It watches Wi-Fi: on Wi-Fi, [PhoneCalls] parks a long-poll at each paired Portal; off
 * Wi-Fi it does nothing and the notification says it is paused. No wake locks are held.
 */
class CallStandbyService : Service() {
    private var callback: ConnectivityManager.NetworkCallback? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val wifi = HashSet<Network>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        goForeground(CallStandbyPolicy.Mode.WAITING_FOR_WIFI)
        watchWifi()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_OFF) { CallStandby.set(this, false); return START_NOT_STICKY }
        if (!CallStandby.load(this)) { stopSelf(); return START_NOT_STICKY }
        refresh()
        return START_STICKY
    }

    override fun onDestroy() {
        callback?.let { runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) } }
        callback = null
        scope.cancel()
        PhoneCalls.setStandby(CallStandbyPolicy.Mode.OFF)
        super.onDestroy()
    }

    private fun watchWifi() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { synchronized(wifi) { wifi.add(network) }; refresh() }
            override fun onLost(network: Network) { synchronized(wifi) { wifi.remove(network) }; refresh() }
        }
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        runCatching { cm.registerNetworkCallback(request, cb) }.onSuccess { callback = cb }
            .onFailure { Log.w(TAG, "no network callback: ${it.javaClass.simpleName}") }
    }

    private fun refresh() {
        scope.launch {
            val portals = withContext(Dispatchers.IO) { runCatching { FileCompanionSessionStore.create(this@CallStandbyService).load().size }.getOrDefault(0) }
            val onWifi = synchronized(wifi) { wifi.isNotEmpty() }
            val mode = CallStandbyPolicy.mode(CallStandby.load(this@CallStandbyService), onWifi, portals,
                PrefsHouseholdKeySource(this@CallStandbyService).peek() != null)
            if (mode == CallStandbyPolicy.Mode.OFF) { stopSelf(); return@launch }
            goForeground(mode, portals)
            PhoneCalls.setStandby(mode)
        }
    }

    private fun goForeground(mode: CallStandbyPolicy.Mode, portals: Int = 0) {
        val (title, text) = CallStandbyPolicy.notice(mode, portals)
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, REQ_OPEN, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val off = PendingIntent.getService(this, REQ_OFF, Intent(this, CallStandbyService::class.java).setAction(ACTION_OFF),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(title).setContentText(text)
            .setOngoing(true).setSilent(true).setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply { open?.let(::setContentIntent) }
            .addAction(0, "Turn off", off)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type) }
            .onFailure { Log.w(TAG, "foreground not allowed: ${it.javaClass.simpleName}"); stopSelf() }
    }

    companion object {
        private const val TAG = "ToolkitStandby"
        const val CHANNEL = "call_standby"
        private const val NOTIFICATION_ID = 0x7500
        private const val REQ_OPEN = 0x7501
        private const val REQ_OFF = 0x7502
        private const val ACTION_OFF = "com.portalinstaller.calls.STANDBY_OFF"

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, CallStandbyService::class.java)) }
                .onFailure { Log.w(TAG, "standby not started: ${it.javaClass.simpleName}") }
        }

        fun stop(context: Context) { context.stopService(Intent(context, CallStandbyService::class.java)) }

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL) != null) return
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Ready for Portal calls", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while Portal Toolkit waits for calls from your Portals on the home Wi-Fi"
                setShowBadge(false)
            })
        }
    }
}

/** After a restart, background ringing comes back on by itself if the owner turned it on. */
class CallStandbyBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (CallStandby.load(context)) CallStandbyService.start(context)
    }
}
