/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.update

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import com.portalinstaller.MainActivity

/** Portal Toolkit's update notifications: "X is available" (with Update), download progress, and "tap to confirm". */
internal object UpdateNotifier {
    const val CHANNEL = "toolkit_updates"
    private const val CHANNEL_NAME = "Portal Toolkit updates"
    private const val AVAILABLE_ID = 0x7A01
    private const val PROGRESS_ID = 0x7A02
    private const val CONFIRM_ID = 0x7A03
    private const val REQUEST_OPEN = 0x7A10
    private const val REQUEST_UPDATE = 0x7A11
    private const val REQUEST_CONFIRM = 0x7A12

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun available(context: Context, manifest: OtaManifest) {
        val open = PendingIntent.getActivity(context, REQUEST_OPEN, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // An activity (not a broadcast): Update may need to open "Install unknown apps", which a receiver can't.
        val update = PendingIntent.getActivity(context, REQUEST_UPDATE,
            Intent(context, MainActivity::class.java).setAction(AppUpdater.ACTION_UPDATE)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        post(context, AVAILABLE_ID, NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(OtaCopy.available(manifest.versionName))
            .setContentText(manifest.notes.ifBlank { "Tap Update to install it." })
            .setContentIntent(open).addAction(0, "Update", update).setAutoCancel(true).build())
    }

    /** Shown when Android needs a tap to confirm but the app isn't on screen to show its dialog. */
    fun confirm(context: Context, confirmIntent: Intent) {
        val tap = PendingIntent.getActivity(context, REQUEST_CONFIRM, confirmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        post(context, CONFIRM_ID, NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Finish updating ${OtaCopy.APP}")
            .setContentText("Tap to confirm the update.")
            .setContentIntent(tap).setAutoCancel(true).build())
    }

    fun progress(context: Context, title: String): ForegroundInfo {
        ensureChannel(context)
        val notification: Notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(title)
            .setProgress(0, 0, true).setOngoing(true).setSilent(true).build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(PROGRESS_ID, notification)
    }

    fun cancelAvailable(context: Context) = NotificationManagerCompat.from(context).cancel(AVAILABLE_ID)

    fun cancelConfirm(context: Context) = NotificationManagerCompat.from(context).cancel(CONFIRM_ID)

    private fun post(context: Context, id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel(context)
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }
}
