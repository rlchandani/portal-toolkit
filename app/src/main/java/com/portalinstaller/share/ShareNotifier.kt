/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.share

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * The waiting state lives in a notification outside the share sheet ("Waiting to send · Nothing
 * sent yet" with Cancel send), and results arrive as a heads-up when the sheet isn't open.
 */
class ShareNotifier(private val context: Context) : ShareEvents {
    /** Job id whose sheet is on screen; it shows the result itself. */
    @Volatile var visibleJobId: String? = null

    override fun held(job: ShareJob) {
        ShareRetryJobService.schedule(context)
        val n = job.items.size
        val what = if (job.kind == ShareKind.PHOTOS) (if (n == 1) "1 photo" else "$n photos") else job.kind.name.lowercase()
        val text = if (job.summary().confirmed == 0) "For ${ShareRooms.destination(job.rooms)}. Nothing sent yet." else ShareCopy.line(job)
        post(job.id, waitingId(job.id), "Waiting to send $what", text, ongoing = true, cancelAction = true)
    }

    override fun finished(job: ShareJob) {
        cancel(waitingId(job.id))
        if (visibleJobId == job.id) return
        val sm = job.summary()
        post(job.id, resultId(job.id), ShareCopy.resultTitle(job),
            if (sm.allConfirmed) "Confirmed by the Portal" else "Tap to see what didn’t arrive", ongoing = false, cancelAction = false)
    }

    fun cancel(id: Int) = NotificationManagerCompat.from(context).cancel(id)

    private fun post(jobId: String, id: Int, title: String, text: String, ongoing: Boolean, cancelAction: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel()
        val open = PendingIntent.getActivity(context, id, ShareActivity.openJob(context, jobId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle(title).setContentText(text)
            .setContentIntent(open).setOngoing(ongoing).setAutoCancel(!ongoing)
            .setPriority(if (ongoing) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
        if (cancelAction) {
            val cancel = PendingIntent.getBroadcast(context, id, Intent(context, ShareActionReceiver::class.java)
                .setAction(ShareActionReceiver.ACTION_CANCEL).putExtra(ShareActivity.EXTRA_JOB, jobId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(0, "Cancel send", cancel)
        }
        runCatching { NotificationManagerCompat.from(context).notify(id, builder.build()) }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Sharing to Portals", NotificationManager.IMPORTANCE_HIGH))
    }

    companion object {
        const val CHANNEL = "share"
        fun waitingId(jobId: String) = 0x5000 + (jobId.hashCode() and 0xFFF)
        fun resultId(jobId: String) = 0x6000 + (jobId.hashCode() and 0xFFF)
    }
}

/** "Cancel send" from the waiting notification. Nothing that was not sent will be sent. */
class ShareActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CANCEL) return
        val jobId = intent.getStringExtra(ShareActivity.EXTRA_JOB) ?: return
        val hub = ShareHub.get(context)
        hub.runner.cancelWaiting(jobId)
        hub.notifier.cancel(ShareNotifier.waitingId(jobId))
    }

    companion object { const val ACTION_CANCEL = "com.portalinstaller.share.CANCEL" }
}
