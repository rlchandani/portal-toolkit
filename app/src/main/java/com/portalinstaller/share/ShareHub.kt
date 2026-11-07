/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.share

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.portalinstaller.data.companion.CompanionClient
import com.portalinstaller.data.companion.CompanionSession
import com.portalinstaller.data.companion.FileCompanionSessionStore
import java.io.File
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Process-wide owner of share jobs, so a transfer keeps going after the share sheet is hidden
 * and queued jobs survive until [ShareRetryJobService] sends them.
 */
class ShareHub private constructor(context: Context) {
    private val app = context.applicationContext
    val store = ShareJobStore(File(app.noBackupFilesDir, "share"))
    private val sessions = FileCompanionSessionStore.create(app)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @OptIn(ExperimentalCoroutinesApi::class)
    private val disk = Dispatchers.IO.limitedParallelism(1)
    val notifier = ShareNotifier(app)
    val sender = ShareSender(CompanionClient(),
        sessionFor = { id -> pairedSessions().firstOrNull { it.device.id == id } },
        uploads = { job, item -> ShareStaging.upload(store, job, item) },
        network = { currentNetwork(app) })
    val runner = ShareRunner(sender, scope,
        persist = { jobs -> scope.launch(disk) { runCatching { store.save(jobs) }.onFailure { Log.w(TAG, "share queue save failed", it) } } },
        events = notifier, initial = runCatching { store.load() }.getOrDefault(emptyList()))

    /** Paired Portals as rooms, in pairing order. Tokens stay inside the companion session store. */
    suspend fun rooms(): List<ShareRoom> = pairedSessions().map { ShareRoom(it.device.id, it.device.name) }

    private suspend fun pairedSessions(): List<CompanionSession> =
        withContext(Dispatchers.IO) { runCatching { sessions.load() }.getOrDefault(emptyList()) }

    fun newJobId(): String = "s" + java.lang.Long.toString(System.currentTimeMillis(), 36) +
        java.lang.Long.toString((random.nextInt(1 shl 20)).toLong(), 36)

    /** Pulls the Portal's word on earlier notes and links (dismissed, canceled, opened…). */
    fun refreshRemoteStates(): kotlinx.coroutines.Job = scope.launch {
        val jobs = runner.jobs.value.filter { it.kind != ShareKind.PHOTOS }
        jobs.flatMap { it.rooms }.distinctBy { it.id }.forEach { room ->
            val states = sender.remoteStates(room.id)
            jobs.filter { j -> j.rooms.any { it.id == room.id } && states.containsKey(j.id) }.forEach { j ->
                runner.update(j.id) { job -> job.items.fold(job) { acc, item ->
                    val s = acc.state(room.id, item.id)
                    if (s.status == ItemStatus.CONFIRMED) acc.with(room.id, item.id, s.copy(remoteState = states[j.id])) else acc
                } }
            }
        }
    }

    companion object {
        private const val TAG = "PortalShare"
        private val random = SecureRandom()
        @Volatile private var instance: ShareHub? = null
        fun get(context: Context): ShareHub = instance ?: synchronized(this) {
            instance ?: ShareHub(context).also { instance = it }
        }

        fun currentNetwork(context: Context): PhoneNetwork {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return PhoneNetwork.NONE
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return PhoneNetwork.NONE
            return when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> PhoneNetwork.WIFI
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> PhoneNetwork.OTHER
                else -> PhoneNetwork.NONE
            }
        }
    }
}
