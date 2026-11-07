/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.share

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Job

/**
 * Sends queued shares when the phone is back on an unmetered (home Wi-Fi) network. The system
 * runs it; nothing polls. If the Portal still can't be reached the job asks for a linear backoff.
 */
class ShareRetryJobService : JobService() {
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val hub = ShareHub.get(this)
        work = hub.runner.resumeHeld().also { job ->
            job.invokeOnCompletion {
                val stillHeld = hub.runner.jobs.value.any { !it.cancelled && it.hasHeld() }
                jobFinished(params, stillHeld)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return true
    }

    companion object {
        private const val JOB_ID = 0x5A4E
        private const val BACKOFF_MS = 60_000L

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(JOB_ID) != null) return
            fun info(persisted: Boolean) = JobInfo.Builder(JOB_ID, ComponentName(context, ShareRetryJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setBackoffCriteria(BACKOFF_MS, JobInfo.BACKOFF_POLICY_LINEAR)
                .setPersisted(persisted)
                .build()
            // Persisting across reboots needs RECEIVE_BOOT_COMPLETED; fall back rather than crash without it.
            val scheduled = runCatching { scheduler.schedule(info(true)) }
                .recoverCatching { scheduler.schedule(info(false)) }
            if (scheduled.getOrNull() != JobScheduler.RESULT_SUCCESS) Log.w("PortalShare", "could not schedule queued share retry")
        }
    }
}
