package com.portalinstaller.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.portalinstaller.R
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class UsbPortalStatus(
    val portalDeviceDetected: Boolean,
    val adbCapableDeviceDetected: Boolean,
    val hasPermission: Boolean,
    val deviceLabel: String?,
    val details: String
)

object UsbPortalHelper {

    const val ACTION_USB_PERMISSION = "com.portalinstaller.USB_PERMISSION"

    private const val META_VENDOR_ID = 11974
    private const val USB_CONNECTION_ATTEMPTS = 6
    private const val USB_DEVICE_READY_TIMEOUT_MS = 12_000L
    private const val USB_PERMISSION_TIMEOUT_MS = 20_000L
    private const val USB_POLL_DELAY_MS = 250L
    private val USB_RETRY_DELAYS_MS = longArrayOf(350L, 700L, 1_200L, 1_800L, 2_500L)

    fun getStatus(context: Context): UsbPortalStatus {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return UsbPortalStatus(
                portalDeviceDetected = false,
                adbCapableDeviceDetected = false,
                hasPermission = false,
                deviceLabel = null,
                details = context.getString(R.string.usb_host_unavailable)
            )

        val allDevices = usbManager.deviceList.values.toList()
        val adbDevice = findAdbDevice(allDevices)
        val portalDevice = adbDevice
            ?: allDevices.firstOrNull(::looksLikePortalDevice)

        if (portalDevice == null) {
            return UsbPortalStatus(
                portalDeviceDetected = false,
                adbCapableDeviceDetected = false,
                hasPermission = false,
                deviceLabel = null,
                details = context.getString(R.string.usb_no_portal_detected)
            )
        }

        val deviceLabel = buildString {
            val productName = runCatching { portalDevice.productName }.getOrNull()
            val manufacturerName = runCatching { portalDevice.manufacturerName }.getOrNull()
            if (!productName.isNullOrBlank()) append(productName)
            if (!manufacturerName.isNullOrBlank()) {
                if (isNotBlank()) append(" ")
                append(manufacturerName)
            }
            if (isBlank()) {
                append(
                    String.format(
                        Locale.US,
                        "USB %04X:%04X",
                        portalDevice.vendorId,
                        portalDevice.productId
                    )
                )
            }
        }

        val hasPermission = usbManager.hasPermission(portalDevice)
        val details = when {
            adbDevice != null && hasPermission ->
                context.getString(R.string.usb_portal_ready)
            adbDevice != null ->
                context.getString(R.string.usb_permission_required)
            else ->
                context.getString(R.string.usb_adb_not_active)
        }

        return UsbPortalStatus(
            portalDeviceDetected = true,
            adbCapableDeviceDetected = adbDevice != null,
            hasPermission = hasPermission,
            deviceLabel = deviceLabel,
            details = details
        )
    }

    fun requestPermission(context: Context): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        val device = findAdbDevice(usbManager.deviceList.values)
            ?: usbManager.deviceList.values.firstOrNull(::looksLikePortalDevice)
            ?: return false

        requestPermission(context, usbManager, device)
        return true
    }

    private fun requestPermission(
        context: Context,
        usbManager: UsbManager,
        device: UsbDevice
    ) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val permissionIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            flags
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    suspend fun connect(context: Context): Result<Dadb> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                val keyPair = AdbKeyPairProvider.getOrCreate(context)
                waitForAdbDevice(usbManager, USB_DEVICE_READY_TIMEOUT_MS)
                    ?: error(context.getString(R.string.usb_adb_interface_not_ready))
                connectWithRetries(context, usbManager, keyPair)
            }.recoverCatching { throwable ->
                throw unwrapUsbError(context, throwable)
            }
        }
    }

    private suspend fun connectWithRetries(
        context: Context,
        usbManager: UsbManager,
        keyPair: AdbKeyPair
    ): Dadb {
        var lastFailure: Throwable? = null

        repeat(USB_CONNECTION_ATTEMPTS) { attempt ->
            val device = waitForAdbDevice(
                usbManager,
                if (attempt == 0) 0L else 2_500L
            )
            if (device == null) {
                lastFailure = IOException(context.getString(R.string.usb_adb_interface_not_ready))
                delayBeforeRetry(attempt)
                return@repeat
            }

            val permittedDevice = ensurePermission(context, usbManager, device)
            if (permittedDevice == null) {
                lastFailure = IOException(context.getString(R.string.usb_permission_missing))
                delayBeforeRetry(attempt)
                return@repeat
            }

            var connection: Dadb? = null
            try {
                connection = UsbAdbBridge.create(usbManager, permittedDevice, keyPair)
                val response = connection.shell("echo portal_usb_ready").output.trim()
                require(response.contains("portal_usb_ready")) {
                    "USB ADB validation failed"
                }
                return connection
            } catch (error: Throwable) {
                lastFailure = error
                runCatching { connection?.close() }
                Log.w(
                    "PortalUsb",
                    "USB ADB attempt ${attempt + 1}/$USB_CONNECTION_ATTEMPTS failed " +
                        "for ${permittedDevice.deviceName}",
                    error
                )
                if (attempt < USB_CONNECTION_ATTEMPTS - 1) {
                    delayBeforeRetry(attempt)
                }
            }
        }

        throw lastFailure ?: IOException("USB ADB connection failed")
    }

    private suspend fun ensurePermission(
        context: Context,
        usbManager: UsbManager,
        device: UsbDevice
    ): UsbDevice? {
        if (usbManager.hasPermission(device)) return device
        requestPermission(context, usbManager, device)

        val deadline = SystemClock.elapsedRealtime() + USB_PERMISSION_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val current = findAdbDevice(usbManager.deviceList.values)
            if (current != null && usbManager.hasPermission(current)) return current
            delay(USB_POLL_DELAY_MS)
        }
        return null
    }

    private suspend fun waitForAdbDevice(
        usbManager: UsbManager,
        timeoutMs: Long
    ): UsbDevice? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            findAdbDevice(usbManager.deviceList.values)?.let { return it }
            if (timeoutMs <= 0L) break
            delay(USB_POLL_DELAY_MS)
        } while (SystemClock.elapsedRealtime() < deadline)
        return null
    }

    private suspend fun delayBeforeRetry(attempt: Int) {
        if (attempt < USB_CONNECTION_ATTEMPTS - 1) {
            delay(USB_RETRY_DELAYS_MS.getOrElse(attempt) { USB_RETRY_DELAYS_MS.last() })
        }
    }

    private fun findAdbDevice(devices: Collection<UsbDevice>): UsbDevice? {
        return devices.firstOrNull { isAdbUsbDevice(it) && looksLikePortalDevice(it) }
            ?: devices.firstOrNull(::isAdbUsbDevice)
    }

    private fun unwrapUsbError(context: Context, throwable: Throwable): Throwable {
        val root = when (throwable) {
            is InvocationTargetException -> throwable.targetException ?: throwable
            else -> throwable.cause ?: throwable
        }
        val rawMessage = root.message.orEmpty()
        val normalized = rawMessage.lowercase()
        val message = when {
            normalized.contains("claim") ->
                context.getString(R.string.error_usb_claim_interface)
            normalized.contains("bulk read timed out") || normalized.contains("timed out") ->
                context.getString(R.string.error_usb_handshake_timeout)
            normalized.contains("eof") ->
                context.getString(R.string.error_usb_handshake_closed)
            rawMessage.isNotBlank() -> rawMessage
            else -> root.javaClass.simpleName
        }
        return IllegalStateException(message, root)
    }

    private fun isAdbUsbDevice(device: UsbDevice): Boolean {
        return UsbAdbBridge.hasAdbInterface(device)
    }

    private fun looksLikePortalDevice(device: UsbDevice): Boolean {
        if (device.vendorId == META_VENDOR_ID) return true

        val product = runCatching { device.productName?.lowercase().orEmpty() }.getOrDefault("")
        val manufacturer = runCatching {
            device.manufacturerName?.lowercase().orEmpty()
        }.getOrDefault("")
        val deviceName = device.deviceName.lowercase()
        return listOf(product, manufacturer, deviceName).any {
            it.contains("portal") || it.contains("facebook") || it.contains("meta")
        }
    }
}
