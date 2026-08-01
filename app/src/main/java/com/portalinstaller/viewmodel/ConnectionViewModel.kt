package com.portalinstaller.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.portalinstaller.R
import com.portalinstaller.data.AppContainer
import com.portalinstaller.data.ConnectionEvent
import com.portalinstaller.data.ConnectionEventKind
import com.portalinstaller.data.ConnectionMonitor
import com.portalinstaller.data.DeviceStatus
import com.portalinstaller.data.DiscoveredPortal
import com.portalinstaller.data.MonitorSignal
import com.portalinstaller.data.OsUpdateBlockStatus
import com.portalinstaller.data.UsbPortalHelper
import com.portalinstaller.data.store.HistoryEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data object Connected : ConnectionState()
    data class Reconnecting(val attempt: Int, val nextRetryMs: Long) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

sealed class WirelessAdbState {
    data object Idle : WirelessAdbState()
    data class Enabling(val port: Int) : WirelessAdbState()
    data class Success(val message: String) : WirelessAdbState()
    data class Failed(val message: String) : WirelessAdbState()
}

sealed class PortalDiscoveryState {
    data object Idle : PortalDiscoveryState()
    data class Scanning(val scanned: Int, val total: Int, val found: Int) : PortalDiscoveryState()
    data class Success(val portals: List<DiscoveredPortal>) : PortalDiscoveryState()
    data class Failed(val message: String) : PortalDiscoveryState()
}

sealed class OsUpdateState {
    data object Idle : OsUpdateState()
    data object Loading : OsUpdateState()
    data class Ready(val status: OsUpdateBlockStatus) : OsUpdateState()
    data class Failed(val message: String) : OsUpdateState()
}

sealed class PowerControlState {
    data object Idle : PowerControlState()
    data object Working : PowerControlState()
    data class Success(val message: String) : PowerControlState()
    data class Failed(val message: String) : PowerControlState()
}

/**
 * Deține conexiunea ADB și tot ce ține de stabilirea/întreținerea ei:
 * conectare Wi-Fi/USB, deconectare, descoperire în rețea, activare ADB
 * wireless, blocarea actualizărilor OS și starea dispozitivului.
 * Conexiunea în sine trăiește în [AppContainer.connectionHolder], partajată
 * cu celelalte ViewModel-uri.
 */
class ConnectionViewModel(app: Application) : AndroidViewModel(app) {

    private val container = AppContainer.from(app)
    private val connectionManager = container.connectionManager
    private val discoveryManager = container.discoveryManager
    private val holder = container.connectionHolder
    private val historyStore = container.historyStore
    private val monitor = container.connectionMonitor

    /** Ultimele coordonate Wi-Fi folosite, pentru reconectarea automată. */
    private var lastWifiHost: String? = null
    private var lastWifiPort: Int = 5555
    private var isUsbConnection = false

    /** Jurnalul de conexiune (heartbeat + reconectări), pentru DevicesTab. */
    val connectionEvents: StateFlow<List<ConnectionEvent>> = monitor.events

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _connectionMethod = MutableStateFlow<String?>(null)
    val connectionMethod: StateFlow<String?> = _connectionMethod.asStateFlow()

    private val _wirelessAdbState = MutableStateFlow<WirelessAdbState>(WirelessAdbState.Idle)
    val wirelessAdbState: StateFlow<WirelessAdbState> = _wirelessAdbState.asStateFlow()

    private val _deviceStatus = MutableStateFlow<DeviceStatus?>(null)
    val deviceStatus: StateFlow<DeviceStatus?> = _deviceStatus.asStateFlow()

    private val _portalDiscoveryState = MutableStateFlow<PortalDiscoveryState>(PortalDiscoveryState.Idle)
    val portalDiscoveryState: StateFlow<PortalDiscoveryState> = _portalDiscoveryState.asStateFlow()

    private val _osUpdateState = MutableStateFlow<OsUpdateState>(OsUpdateState.Idle)
    val osUpdateState: StateFlow<OsUpdateState> = _osUpdateState.asStateFlow()

    private val _powerControlState = MutableStateFlow<PowerControlState>(PowerControlState.Idle)
    val powerControlState: StateFlow<PowerControlState> = _powerControlState.asStateFlow()

    private suspend fun addHistory(description: String, success: Boolean) {
        historyStore.add(HistoryEntry(System.currentTimeMillis(), description, success))
    }

    fun connect(context: Context, ip: String, port: Int = 5555) {
        if (_connectionState.value is ConnectionState.Connecting) return
        viewModelScope.launch {
            _connectionState.value = ConnectionState.Connecting
            connectionManager.connect(context, ip, port)
                .onSuccess { d ->
                    holder.set(d, context.getString(R.string.connection_method_wifi))
                    _connectionMethod.value = context.getString(R.string.connection_method_wifi)
                    _connectionState.value = ConnectionState.Connected
                    lastWifiHost = ip
                    lastWifiPort = port
                    isUsbConnection = false
                    monitor.logEvent(ConnectionEventKind.CONNECTED_WIFI)
                    startMonitor(context)
                    refreshDeviceStatus()
                }
                .onFailure { e ->
                    _connectionState.value = ConnectionState.Error(
                        e.message ?: context.getString(R.string.error_unknown)
                    )
                }
        }
    }

    fun connectUsb(context: Context) {
        if (_connectionState.value is ConnectionState.Connecting) return
        viewModelScope.launch {
            _connectionState.value = ConnectionState.Connecting
            UsbPortalHelper.connect(context)
                .onSuccess { d ->
                    holder.set(d, context.getString(R.string.connection_method_usb))
                    _connectionMethod.value = context.getString(R.string.connection_method_usb)
                    _connectionState.value = ConnectionState.Connected
                    isUsbConnection = true
                    addHistory(context.getString(R.string.history_connected_usb), success = true)
                    monitor.logEvent(ConnectionEventKind.CONNECTED_USB)
                    startMonitor(context)
                    refreshDeviceStatus()
                }
                .onFailure { e ->
                    val msg = e.message ?: context.getString(R.string.error_usb_connection_open_failed)
                    _connectionState.value = ConnectionState.Error(msg)
                    addHistory(context.getString(R.string.history_usb_connect_failed, msg), success = false)
                }
        }
    }

    /**
     * Pornește heartbeat-ul. La pierderea conexiunii încearcă reconectarea:
     * Wi-Fi refolosește ultimul host/port; USB refolosește helper-ul USB.
     */
    private fun startMonitor(context: Context) {
        monitor.start(
            scope = viewModelScope,
            ping = { pingConnection() },
            reconnect = { attemptReconnect(context) },
            onSignal = { signal -> handleMonitorSignal(context, signal) }
        )
    }

    private fun pingConnection(): Boolean {
        val d = holder.dadb.value ?: return false
        return runCatching { d.shell("echo ok").output.trim() == "ok" }.getOrDefault(false)
    }

    private suspend fun attemptReconnect(context: Context): Boolean {
        val result = if (isUsbConnection) {
            UsbPortalHelper.connect(context)
        } else {
            val host = lastWifiHost ?: return false
            connectionManager.connect(context, host, lastWifiPort)
        }
        return result.onSuccess { d ->
            val label = if (isUsbConnection) {
                context.getString(R.string.connection_method_usb)
            } else {
                context.getString(R.string.connection_method_wifi)
            }
            holder.set(d, label)
        }.isSuccess
    }

    private fun handleMonitorSignal(context: Context, signal: MonitorSignal) {
        when (signal) {
            is MonitorSignal.Lost -> {
                _connectionState.value = ConnectionState.Reconnecting(attempt = 0, nextRetryMs = 0)
            }
            is MonitorSignal.Reconnecting -> {
                _connectionState.value =
                    ConnectionState.Reconnecting(signal.attempt, signal.nextRetryMs)
            }
            is MonitorSignal.Reconnected -> {
                _connectionState.value = ConnectionState.Connected
                viewModelScope.launch {
                    addHistory(context.getString(R.string.history_reconnected), success = true)
                }
                refreshDeviceStatus()
            }
            is MonitorSignal.Healthy -> {
                _connectionState.value = ConnectionState.Connected
            }
        }
    }

    fun connectDiscoveredPortal(context: Context, portal: DiscoveredPortal) {
        connect(context, portal.ip, portal.port)
    }

    fun disconnect() {
        monitor.stop()
        monitor.logEvent(ConnectionEventKind.DISCONNECTED)
        holder.clear()
        _connectionState.value = ConnectionState.Disconnected
        _connectionMethod.value = null
        _wirelessAdbState.value = WirelessAdbState.Idle
        _deviceStatus.value = null
        _osUpdateState.value = OsUpdateState.Idle
    }

    private fun refreshDeviceStatus() {
        val d = holder.dadb.value ?: return
        viewModelScope.launch {
            _deviceStatus.value = connectionManager.getDeviceStatus(d)
        }
    }

    /** Reîmprospătează starea dispozitivului la cerere (ex. buton din dashboard). */
    fun refreshDeviceDashboard() = refreshDeviceStatus()

    /**
     * Repornire (normală sau recovery). La succes, Portal-ul se deconectează;
     * oprim monitorul și marcăm starea deconectată — nu reîncercăm reconectarea,
     * fiindcă dispozitivul repornește intenționat.
     */
    fun reboot(context: Context, recovery: Boolean) {
        val d = holder.dadb.value ?: return
        if (_powerControlState.value is PowerControlState.Working) return
        viewModelScope.launch {
            _powerControlState.value = PowerControlState.Working
            connectionManager.reboot(d, recovery)
                .onSuccess {
                    val msg = context.getString(
                        if (recovery) R.string.power_reboot_recovery_done
                        else R.string.power_reboot_done
                    )
                    _powerControlState.value = PowerControlState.Success(msg)
                    addHistory(msg, success = true)
                    monitor.stop()
                    monitor.logEvent(ConnectionEventKind.DISCONNECTED)
                    holder.clear()
                    _connectionState.value = ConnectionState.Disconnected
                    _connectionMethod.value = null
                    _deviceStatus.value = null
                    _osUpdateState.value = OsUpdateState.Idle
                }
                .onFailure { e ->
                    val msg = e.message ?: context.getString(R.string.error_unknown)
                    _powerControlState.value = PowerControlState.Failed(msg)
                    addHistory(context.getString(R.string.power_reboot_failed, msg), success = false)
                }
        }
    }

    fun setScreenOn(context: Context, on: Boolean) {
        val d = holder.dadb.value ?: return
        if (_powerControlState.value is PowerControlState.Working) return
        viewModelScope.launch {
            _powerControlState.value = PowerControlState.Working
            connectionManager.setScreenOn(d, on)
                .onSuccess {
                    _powerControlState.value = PowerControlState.Success(
                        context.getString(
                            if (on) R.string.power_screen_on_done else R.string.power_screen_off_done
                        )
                    )
                }
                .onFailure { e ->
                    _powerControlState.value = PowerControlState.Failed(
                        e.message ?: context.getString(R.string.error_unknown)
                    )
                }
        }
    }

    fun launchPortalApp(context: Context, packageName: String) {
        val d = holder.dadb.value ?: return
        if (_powerControlState.value is PowerControlState.Working) return
        viewModelScope.launch {
            _powerControlState.value = PowerControlState.Working
            connectionManager.launchApp(d, packageName)
                .onSuccess {
                    _powerControlState.value = PowerControlState.Success(
                        context.getString(R.string.power_launch_done, packageName)
                    )
                    addHistory(context.getString(R.string.power_launch_done, packageName), success = true)
                }
                .onFailure { e ->
                    val msg = e.message ?: context.getString(R.string.error_unknown)
                    _powerControlState.value = PowerControlState.Failed(msg)
                }
        }
    }

    fun clearPowerControlState() {
        _powerControlState.value = PowerControlState.Idle
    }

    fun refreshOsUpdateStatus(context: Context) {
        val connection = holder.dadb.value
        if (connection == null) {
            _osUpdateState.value = OsUpdateState.Failed(
                context.getString(R.string.update_block_connect_first)
            )
            return
        }
        viewModelScope.launch {
            _osUpdateState.value = OsUpdateState.Loading
            runCatching { connectionManager.getOsUpdateBlockStatus(connection) }
                .onSuccess { status -> _osUpdateState.value = OsUpdateState.Ready(status) }
                .onFailure { error ->
                    _osUpdateState.value = OsUpdateState.Failed(
                        error.message ?: context.getString(R.string.error_unknown)
                    )
                }
        }
    }

    fun setOsUpdatesBlocked(context: Context, blocked: Boolean) {
        val connection = holder.dadb.value
        if (connection == null) {
            _osUpdateState.value = OsUpdateState.Failed(
                context.getString(R.string.update_block_connect_first)
            )
            return
        }
        viewModelScope.launch {
            _osUpdateState.value = OsUpdateState.Loading
            connectionManager.setOsUpdatesBlocked(connection, blocked)
                .onSuccess { status ->
                    _osUpdateState.value = OsUpdateState.Ready(status)
                    addHistory(
                        context.getString(
                            if (status.isBlocked) R.string.update_block_enabled_history
                            else R.string.update_block_disabled_history
                        ),
                        success = true
                    )
                }
                .onFailure { error ->
                    val message = error.message ?: context.getString(R.string.error_unknown)
                    _osUpdateState.value = OsUpdateState.Failed(message)
                    addHistory(
                        context.getString(R.string.update_block_failed_history, message),
                        success = false
                    )
                }
        }
    }

    fun enableWirelessAdb(context: Context, port: Int = 5555) {
        val d = holder.dadb.value ?: return
        viewModelScope.launch {
            _wirelessAdbState.value = WirelessAdbState.Enabling(port)
            connectionManager.enableWirelessAdb(context, d, port)
                .onSuccess { message ->
                    _wirelessAdbState.value = WirelessAdbState.Success(message)
                    addHistory(context.getString(R.string.history_wireless_adb_enabled, port), success = true)
                    monitor.stop()
                    holder.clear()
                    _connectionState.value = ConnectionState.Disconnected
                    _connectionMethod.value = null
                    _deviceStatus.value = null
                }
                .onFailure { e ->
                    val msg = e.message ?: context.getString(R.string.error_enable_wireless_adb)
                    _wirelessAdbState.value = WirelessAdbState.Failed(msg)
                    addHistory(context.getString(R.string.history_wireless_adb_failed, msg), success = false)
                }
        }
    }

    fun discoverWirelessPortals(context: Context, port: Int = 5555) {
        if (_portalDiscoveryState.value is PortalDiscoveryState.Scanning) return
        viewModelScope.launch {
            _portalDiscoveryState.value = PortalDiscoveryState.Scanning(scanned = 0, total = 254, found = 0)
            discoveryManager.discoverPortals(
                context = context,
                port = port,
                onProgress = { scanned, total, found ->
                    _portalDiscoveryState.value = PortalDiscoveryState.Scanning(scanned, total, found)
                }
            ).onSuccess { portals ->
                _portalDiscoveryState.value = PortalDiscoveryState.Success(portals)
            }.onFailure { error ->
                _portalDiscoveryState.value = PortalDiscoveryState.Failed(
                    error.message ?: context.getString(R.string.error_discovery_failed)
                )
            }
        }
    }

    fun clearConnectionError() {
        if (_connectionState.value is ConnectionState.Error) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    fun clearWirelessAdbState() {
        _wirelessAdbState.value = WirelessAdbState.Idle
    }

    fun clearPortalDiscoveryState() {
        _portalDiscoveryState.value = PortalDiscoveryState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        monitor.stop()
        holder.clear()
    }
}
