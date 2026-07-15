package com.portalinstaller.data

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import dadb.AdbKeyPair
import dadb.AdbStream
import dadb.Dadb
import okio.Buffer
import okio.Sink
import okio.Source
import okio.Timeout
import java.io.Closeable
import java.io.IOException
import java.net.SocketTimeoutException

private const val USB_TIMEOUT_MS = 5000
private const val USB_READ_RETRY_COUNT = 12
private const val ADB_HEADER_LENGTH = 24

private class UsbBulkSource(
    private val connection: UsbDeviceConnection,
    private val endpoint: UsbEndpoint
) : Source {
    private var closed = false

    override fun read(sink: Buffer, byteCount: Long): Long {
        if (closed) throw IOException("Source closed")
        if (byteCount == 0L) return 0

        val chunkSize = minOf(byteCount, 16 * 1024L).toInt()
        repeat(USB_READ_RETRY_COUNT) { attempt ->
            val buffer = ByteArray(chunkSize)
            val read = connection.bulkTransfer(endpoint, buffer, buffer.size, USB_TIMEOUT_MS)
            if (read > 0) {
                sink.write(buffer, 0, read)
                return read.toLong()
            }
            if (read == 0) {
                return 0
            }
            if (closed) throw IOException("Source closed")
            if (attempt == USB_READ_RETRY_COUNT - 1) {
                throw SocketTimeoutException(
                    "USB bulk read timed out waiting for ADB response " +
                        "(endpoint addr=${endpoint.address} dir=${endpoint.direction} type=${endpoint.type})"
                )
            }
        }
        throw SocketTimeoutException("USB bulk read timed out waiting for ADB response")
    }

    override fun timeout(): Timeout = Timeout.NONE

    override fun close() {
        closed = true
    }
}

private class UsbBulkSink(
    private val connection: UsbDeviceConnection,
    private val endpoint: UsbEndpoint
) : Sink {
    private var closed = false

    override fun write(source: Buffer, byteCount: Long) {
        if (closed) throw IOException("Sink closed")
        if (byteCount == 0L) return

        val bytes = source.readByteArray(byteCount)
        var offset = 0

        while (offset < bytes.size) {
            val remaining = bytes.size - offset
            if (remaining < ADB_HEADER_LENGTH) {
                writeChunk(bytes, offset, remaining)
                return
            }

            val payloadLength = readIntLe(bytes, offset + 12)
            val messageLength = ADB_HEADER_LENGTH + payloadLength
            if (payloadLength < 0 || messageLength > remaining) {
                writeChunk(bytes, offset, remaining)
                return
            }

            // On USB, adbd implementations are often happier when the ADB
            // header and payload are sent as separate bulk transfers.
            writeChunk(bytes, offset, ADB_HEADER_LENGTH)
            if (payloadLength > 0) {
                writeChunk(bytes, offset + ADB_HEADER_LENGTH, payloadLength)
            }
            offset += messageLength
        }
    }

    override fun flush() = Unit

    override fun timeout(): Timeout = Timeout.NONE

    override fun close() {
        closed = true
    }

    private fun writeChunk(bytes: ByteArray, offset: Int, length: Int) {
        var cursor = offset
        var remaining = length
        while (remaining > 0) {
            val chunkSize = minOf(remaining, 16 * 1024)
            val chunk = bytes.copyOfRange(cursor, cursor + chunkSize)
            val written = connection.bulkTransfer(endpoint, chunk, chunk.size, USB_TIMEOUT_MS)
            if (written < 0 || written != chunk.size) {
                throw IOException(
                    "USB bulk write failed " +
                        "(endpoint addr=${endpoint.address} dir=${endpoint.direction} type=${endpoint.type})"
                )
            }
            cursor += written
            remaining -= written
        }
    }

    private fun readIntLe(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
    }
}

private class ReflectiveUsbDadb(
    private val connection: Any
) : Dadb {
    private val connectionClass = connection.javaClass
    private val openMethod = connectionClass.getMethod("open", String::class.java)
    private val supportsFeatureMethod = connectionClass.getMethod("supportsFeature", String::class.java)
    private val closeMethod = connectionClass.getMethod("close")

    override fun open(destination: String): AdbStream {
        return openMethod.invoke(connection, destination) as AdbStream
    }

    override fun supportsFeature(feature: String): Boolean {
        return supportsFeatureMethod.invoke(connection, feature) as Boolean
    }

    override fun close() {
        closeMethod.invoke(connection)
    }
}

object UsbAdbBridge {
    fun create(usbManager: UsbManager, device: UsbDevice, keyPair: AdbKeyPair): Dadb {
        val adbInterface = findAdbInterface(device) ?: throw IOException("ADB USB interface not found")
        val (inEndpoint, outEndpoint) = try {
            findBulkEndpoints(adbInterface)
        } catch (t: Throwable) {
            throw IOException(
                "Failed to inspect ADB USB endpoints: ${describeInterface(adbInterface)}",
                t
            )
        }
        val input = inEndpoint ?: throw IOException("ADB USB IN endpoint not found")
        val output = outEndpoint ?: throw IOException("ADB USB OUT endpoint not found")

        val connection = usbManager.openDevice(device) ?: throw IOException("Unable to open USB device")
        val claimed = connection.claimInterface(adbInterface, false) ||
            connection.claimInterface(adbInterface, true)
        if (!claimed) {
            connection.close()
            throw IOException("Unable to claim ADB USB interface")
        }

        val source = UsbBulkSource(connection, input)
        val sink = UsbBulkSink(connection, output)
        val closeable = Closeable {
            runCatching { connection.releaseInterface(adbInterface) }
            connection.close()
        }

        val adbConnection = createAdbConnection(source, sink, keyPair, closeable)
        return ReflectiveUsbDadb(adbConnection)
    }

    private fun createAdbConnection(
        source: Source,
        sink: Sink,
        keyPair: AdbKeyPair,
        closeable: Closeable
    ): Any {
        // Verified against dev.mobile:dadb:1.2.6. This reaches an internal
        // transport hook because dadb does not expose a public USB transport API.
        // Re-test this path after every dadb version bump.
        val adbConnectionClass = Class.forName("dadb.AdbConnection")
        val companionField = adbConnectionClass.getField("Companion")
        val companion = companionField.get(null)
        val companionClass = companion.javaClass
        val method = companionClass.getDeclaredMethod(
            "connect\$default",
            companionClass,
            Source::class.java,
            Sink::class.java,
            AdbKeyPair::class.java,
            Closeable::class.java,
            Int::class.javaPrimitiveType,
            Any::class.java
        )
        method.isAccessible = true
        return method.invoke(null, companion, source, sink, keyPair, closeable, 0, null)
            ?: throw IOException("Failed to create dadb USB connection")
    }

    private fun findAdbInterface(device: UsbDevice): UsbInterface? {
        for (index in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(index)
            if (usbInterface.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC &&
                usbInterface.interfaceSubclass == 0x42 &&
                usbInterface.interfaceProtocol == 0x01
            ) {
                return usbInterface
            }
        }
        return null
    }

    private fun findBulkEndpoints(adbInterface: UsbInterface): Pair<UsbEndpoint?, UsbEndpoint?> {
        var inEndpoint: UsbEndpoint? = null
        var outEndpoint: UsbEndpoint? = null
        for (index in 0 until adbInterface.endpointCount) {
            val endpoint = adbInterface.getEndpoint(index) ?: continue
            if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            when (endpoint.direction) {
                UsbConstants.USB_DIR_IN -> inEndpoint = endpoint
                UsbConstants.USB_DIR_OUT -> outEndpoint = endpoint
            }
        }
        return inEndpoint to outEndpoint
    }

    private fun describeInterface(adbInterface: UsbInterface): String {
        if (adbInterface.endpointCount == 0) {
            return "endpointCount=0"
        }

        return buildString {
            append("endpointCount=")
            append(adbInterface.endpointCount)
            for (index in 0 until adbInterface.endpointCount) {
                val endpoint = runCatching { adbInterface.getEndpoint(index) }.getOrNull()
                append(" | #")
                append(index)
                if (endpoint == null) {
                    append("=<null>")
                } else {
                    append(" addr=")
                    append(endpoint.address)
                    append(" dir=")
                    append(endpoint.direction)
                    append(" type=")
                    append(endpoint.type)
                    append(" maxPacket=")
                    append(endpoint.maxPacketSize)
                }
            }
        }
    }
}
