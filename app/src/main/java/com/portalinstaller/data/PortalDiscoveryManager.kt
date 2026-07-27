package com.portalinstaller.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import com.portalinstaller.R
import dadb.AdbKeyPair
import dadb.AdbStream
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

private const val DISCOVERY_CONNECT_TIMEOUT_MS = 500
private const val DISCOVERY_BATCH_SIZE = 48
private const val MAX_DISCOVERY_HOSTS = 65_534

private class ReflectiveTcpDadb(
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

data class DiscoveredPortal(
    val ip: String,
    val port: Int,
    val model: String,
    val manufacturer: String,
    val brand: String
) {
    val displayName: String
        get() = listOf(manufacturer, model)
            .filter { it.isNotBlank() && it != "unknown" }
            .joinToString(" ")
            .ifBlank { ip }
}

class PortalDiscoveryManager(
    private val connectionManager: PortalConnectionManager = PortalConnectionManager()
) {

    suspend fun discoverPortals(
        context: Context,
        port: Int = 5555,
        onProgress: suspend (scanned: Int, total: Int, found: Int) -> Unit = { _, _, _ -> }
    ): Result<List<DiscoveredPortal>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val hosts = findLocalNetworkHosts(context)
                    ?: error(context.getString(R.string.discovery_network_unavailable))

                val keyPair = AdbKeyPairProvider.getOrCreate(context)
                val total = hosts.size
                val scanned = AtomicInteger(0)
                val found = AtomicInteger(0)
                val semaphore = Semaphore(24)

                val results = coroutineScope {
                    buildList {
                        hosts.chunked(DISCOVERY_BATCH_SIZE).forEach { batch ->
                            val batchResults = batch.map { ip ->
                                async {
                                    semaphore.withPermit {
                                        val portal = probePortal(ip, port, keyPair)
                                        val scannedNow = scanned.incrementAndGet()
                                        if (portal != null) {
                                            found.incrementAndGet()
                                        }
                                        onProgress(scannedNow, total, found.get())
                                        portal
                                    }
                                }
                            }.awaitAll()
                            addAll(batchResults)
                        }
                    }
                }

                results.filterNotNull().sortedBy { it.ip }
            }
        }
    }

    private fun probePortal(ip: String, port: Int, keyPair: AdbKeyPair): DiscoveredPortal? {
        val dadb = connectForDiscovery(ip, port, keyPair) ?: return null
        return try {
            val model = dadb.shell("getprop ro.product.model").output.trim().ifBlank { "unknown" }
            val manufacturer = dadb.shell("getprop ro.product.manufacturer").output.trim().ifBlank { "unknown" }
            val brand = dadb.shell("getprop ro.product.brand").output.trim().ifBlank { "unknown" }

            if (!looksLikePortal(model, manufacturer, brand)) {
                null
            } else {
                DiscoveredPortal(
                    ip = ip,
                    port = port,
                    model = model,
                    manufacturer = manufacturer,
                    brand = brand
                )
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { dadb.close() }
        }
    }

    private fun connectForDiscovery(ip: String, port: Int, keyPair: AdbKeyPair): Dadb? {
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(ip, port), DISCOVERY_CONNECT_TIMEOUT_MS)
            socket.soTimeout = DISCOVERY_CONNECT_TIMEOUT_MS

            val adbConnectionClass = Class.forName("dadb.AdbConnection")
            val companionField = adbConnectionClass.getField("Companion")
            val companion = companionField.get(null)
            val companionClass = companion.javaClass
            val method = companionClass.getDeclaredMethod(
                "connect",
                Socket::class.java,
                AdbKeyPair::class.java
            )
            method.isAccessible = true
            val connection = method.invoke(companion, socket, keyPair)
                ?: throw IOException("Nu s-a putut crea conexiunea ADB pentru discovery.")
            ReflectiveTcpDadb(connection)
        } catch (_: Throwable) {
            runCatching { socket.close() }
            null
        }
    }

    private fun looksLikePortal(model: String, manufacturer: String, brand: String): Boolean {
        val haystack = listOf(model, manufacturer, brand).joinToString(" ").lowercase()
        return haystack.contains("portal") || haystack.contains("facebook") || haystack.contains("meta")
    }

    private fun findLocalNetworkHosts(context: Context): List<String>? {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
            ?: return null
        val linkAddresses = connectivityManager
            .getLinkProperties(connectivityManager.activeNetwork)
            ?.linkAddresses
            .orEmpty()

        return linkAddresses
            .asSequence()
            .filter { it.address.address.size == IPV4_ADDRESS_BYTES }
            .flatMap { it.hostAddresses().asSequence() }
            .distinct()
            .toList()
            .ifEmpty { null }
    }

    private fun LinkAddress.hostAddresses(): List<String> {
        val addressBytes = address.address
        val prefixLength = prefixLength.coerceIn(0, IPV4_ADDRESS_BITS)
        val address = addressBytes.fold(0L) { value, byte ->
            (value shl BYTE_BITS) or (byte.toLong() and BYTE_MASK)
        }
        val hostBits = IPV4_ADDRESS_BITS - prefixLength
        val subnetMask = if (prefixLength == 0) 0L else MAX_IPV4_ADDRESS shl hostBits
        val network = address and subnetMask
        val broadcast = network or (MAX_IPV4_ADDRESS ushr prefixLength)
        val hostCount = broadcast - network - 1

        if (hostCount !in 1..MAX_DISCOVERY_HOSTS) return emptyList()

        return ((network + 1) until broadcast)
            .filter { it != address }
            .map(::toIpv4Address)
    }

    private fun toIpv4Address(address: Long): String {
        return (IPV4_OCTETS - 1 downTo 0)
            .joinToString(".") { index ->
                ((address shr (index * BYTE_BITS)) and BYTE_MASK).toString()
            }
    }

    private companion object {
        const val IPV4_ADDRESS_BYTES = 4
        const val IPV4_ADDRESS_BITS = 32
        const val IPV4_OCTETS = 4
        const val BYTE_BITS = 8
        const val BYTE_MASK = 0xffL
        const val MAX_IPV4_ADDRESS = 0xffffffffL
    }
}
