package com.portalinstaller.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.portalinstaller.R
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.IOException
import java.lang.reflect.InvocationTargetException
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
    private const val USB_CONNECTION_ATTEMPTS = 3
    private const val USB_RETRY_DELAY_MS = 400L

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
        val adbDevice = allDevices.firstOrNull(::isAdbUsbDevice)
        val portalDevice = adbDevice ?: allDevices.firstOrNull(::looksLikePortalDevice)

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
            if (!portalDevice.productName.isNullOrBlank()) append(portalDevice.productName)
            if (!portalDevice.manufacturerName.isNullOrBlank()) {
                if (isNotBlank()) append(" ")
                append(portalDevice.manufacturerName)
            }
            if (isBlank()) append(portalDevice.deviceName)
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
        val device = usbManager.deviceList.values.firstOrNull(::isAdbUsbDevice)
            ?: usbManager.deviceList.values.firstOrNull(::looksLikePortalDevice)
            ?: return false

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
        return true
    }

    suspend fun connect(context: Context): Result<Dadb> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                val device = usbManager.deviceList.values.firstOrNull(::isAdbUsbDevice)
                    ?: error(context.getString(R.string.usb_portal_no_longer_detected))
                if (!usbManager.hasPermission(device)) {
                    error(context.getString(R.string.usb_permission_missing))
                }
                val keyPair = AdbKeyPairProvider.getOrCreate(context)
                connectWithRetries(usbManager, device, keyPair)
            }.recoverCatching { throwable ->
                throw unwrapUsbError(context, throwable)
            }
        }
    }

    private suspend fun connectWithRetries(
        usbManager: UsbManager,
        device: UsbDevice,
        keyPair: AdbKeyPair
    ): Dadb {
        var lastFailure: Throwable? = null

        repeat(USB_CONNECTION_ATTEMPTS) { attempt ->
            try {
                return UsbAdbBridge.create(usbManager, device, keyPair)
            } catch (error: Throwable) {
                lastFailure = error
                if (attempt < USB_CONNECTION_ATTEMPTS - 1) {
                    delay(USB_RETRY_DELAY_MS)
                }
            }
        }

        throw lastFailure ?: IOException("USB ADB connection failed")
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

        val product = device.productName?.lowercase().orEmpty()
        val manufacturer = device.manufacturerName?.lowercase().orEmpty()
        val deviceName = device.deviceName.lowercase()
        return listOf(product, manufacturer, deviceName).any {
            it.contains("portal") || it.contains("facebook") || it.contains("meta")
        }
    }
}
