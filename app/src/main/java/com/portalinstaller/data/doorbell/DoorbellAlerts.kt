/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.doorbell

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.portalinstaller.MainActivity
import com.portalinstaller.data.companion.CompanionClient
import com.portalinstaller.data.companion.FileCompanionSessionStore

private const val TAG = "ToolkitDoorbell"

/** The production repository (OkHttp, saved pairings). */
fun doorbellRepository(context: Context): DoorbellRepository =
    DoorbellRepository(CompanionClient(), FileCompanionSessionStore.create(context.applicationContext)) { Log.w(TAG, it) }

/**
 * Remembers which doorbell events this phone already announced (so reopening the app doesn't
 * repeat one) and posts the "Someone's at the door" notification.
 */
object DoorbellAlerts {
    private const val CHANNEL = "doorbell"
    private const val PREFS = "doorbell_alerts"
    private const val KEY_SEEN = "seen"
    private const val NOTIFICATION_BASE = 0x7200
    private const val NOTIFICATION_SPAN = 0xFF

    /** New events to show, already remembered as announced. */
    @Synchronized fun announce(context: Context, reports: List<List<DoorbellEvent>>): List<DoorbellEvent> {
        val prefs = prefs(context)
        val seen = prefs.getString(KEY_SEEN, null)?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
        val result = DoorbellBannerPolicy.announce(reports, seen)
        if (result.fresh.isNotEmpty()) prefs.edit().putString(KEY_SEEN, result.remembered.joinToString("\n")).apply()
        return result.fresh
    }

    fun notify(context: Context, event: DoorbellEvent) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel(context)
        val id = NOTIFICATION_BASE + (event.id.hashCode() and NOTIFICATION_SPAN)
        val open = PendingIntent.getActivity(context, id, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(event.title).setContentText(event.detail)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setTimeoutAfter(DoorbellBannerPolicy.FRESH_MS * NOTIFICATION_LIFETIME_FACTOR)
            .setContentIntent(open).setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
            .onFailure { Log.w(TAG, "doorbell notification failed: ${it.javaClass.simpleName}") }
    }

    private const val NOTIFICATION_LIFETIME_FACTOR = 10L

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Doorbell", NotificationManager.IMPORTANCE_HIGH))
    }
}
