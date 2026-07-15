package com.portalinstaller.data

import android.content.Context
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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

private const val DISCOVERY_CONNECT_TIMEOUT_MS = 500

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
                val subnetPrefix = findSubnetPrefix()
                    ?: error(context.getString(R.string.discovery_network_unavailable))

                val keyPair = AdbKeyPairProvider.getOrCreate(context)
                val hosts = (1..254).map { "$subnetPrefix.$it" }
                val total = hosts.size
                val scanned = AtomicInteger(0)
                val found = AtomicInteger(0)
                val semaphore = Semaphore(24)

                val results = coroutineScope {
                    hosts.map { ip ->
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

    private fun findSubnetPrefix(): String? {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        interfaces.forEach { networkInterface ->
            if (!networkInterface.isUp || networkInterface.isLoopback || networkInterface.isVirtual) {
                return@forEach
            }
            networkInterface.inetAddresses.toList()
                .filterIsInstance<InetAddress>()
                .mapNotNull { address ->
                    val host = address.hostAddress ?: return@mapNotNull null
                    if (host.contains(":") || host.startsWith("127.")) null else host
                }
                .forEach { host ->
                    val lastDot = host.lastIndexOf('.')
                    if (lastDot > 0) {
                        return host.substring(0, lastDot)
                    }
                }
        }
        return null
    }
}
