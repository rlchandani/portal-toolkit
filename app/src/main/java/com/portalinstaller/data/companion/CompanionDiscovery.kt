/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.companion

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.util.ArrayDeque

interface CompanionDiscovery {
    fun start(onChanged: (List<DiscoveredPortal>) -> Unit, onError: (String) -> Unit)
    fun stop()
}

/** Legacy API resolves one service at a time; callbacks from stopped scans cannot re-add peers. */
class NsdCompanionDiscovery(context: Context) : CompanionDiscovery {
    private val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val lock = Any()
    private var listener: NsdManager.DiscoveryListener? = null
    private var generation = 0L
    private var resolving = false
    private val queue = ArrayDeque<NsdServiceInfo>()
    private val found = linkedMapOf<String, DiscoveredPortal>()
    private val present = mutableSetOf<String>()

    override fun start(onChanged: (List<DiscoveredPortal>) -> Unit, onError: (String) -> Unit) {
        stop()
        val scan = synchronized(lock) { ++generation }
        fun publish() { onChanged(found.values.sortedBy { it.name }) }
        fun resolveNext() {
            synchronized(lock) {
                if (scan != generation || resolving || queue.isEmpty()) return
                val service = queue.removeFirst()
                resolving = true
                @Suppress("DEPRECATION")
                try {
                    manager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            synchronized(lock) { if (scan != generation) return; resolving = false }
                            resolveNext()
                        }
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            synchronized(lock) {
                                if (scan != generation) return
                                resolving = false
                                val address = serviceInfo.host?.hostAddress
                                if (address != null && serviceInfo.serviceName in present && serviceInfo.port in 1..65535) {
                                    val host = if (address.contains(':')) "[$address]" else address
                                    runCatching { CompanionEndpoint.normalize("http://$host:${serviceInfo.port}") }.getOrNull()?.let {
                                        found[serviceInfo.serviceName] = DiscoveredPortal(serviceInfo.serviceName, it)
                                        publish()
                                    }
                                }
                            }
                            resolveNext()
                        }
                    })
                } catch (_: Exception) { resolving = false; resolveNext() }
            }
        }
        val scanListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                synchronized(lock) { if (scan != generation) return }
                stop()
                onError("LAN discovery could not start. Enter the Portal address manually.")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                synchronized(lock) {
                    if (scan != generation || !serviceInfo.serviceType.contains("_immortal-remote._tcp")) return
                    if (!present.add(serviceInfo.serviceName)) return
                    queue.addLast(serviceInfo)
                }
                resolveNext()
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                synchronized(lock) {
                    if (scan != generation) return
                    present.remove(serviceInfo.serviceName)
                    found.remove(serviceInfo.serviceName)
                    publish()
                }
            }
        }
        synchronized(lock) { listener = scanListener }
        onChanged(emptyList())
        try { manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, scanListener) }
        catch (_: Exception) {
            stop()
            onError("LAN discovery is unavailable. Enter the Portal address manually.")
        }
    }

    override fun stop() {
        val old = synchronized(lock) {
            generation++
            resolving = false
            queue.clear(); found.clear(); present.clear()
            listener.also { listener = null }
        }
        if (old != null) runCatching { manager.stopServiceDiscovery(old) }
    }
    companion object { const val SERVICE_TYPE = "_immortal-remote._tcp." }
}
