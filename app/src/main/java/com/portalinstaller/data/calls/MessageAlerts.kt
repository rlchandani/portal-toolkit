/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.calls

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.portalinstaller.ui.calls.PhoneMessageActivity

/**
 * Mock `phnotifs`: "Video message from Grandpa Walt" · "1:12" with Watch. Content-light on the
 * lock screen (the public version names nobody). Uses the default notification sound: a message
 * is never a ring.
 */
object MessageAlerts {
    private const val TAG = "ToolkitMessages"
    const val CHANNEL = "call_messages"
    private const val BASE_ID = 0x7400
    private const val REQ_WATCH = 0x7400

    fun arrived(context: Context, m: PhoneMessage) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel(context)
        val watch = PendingIntent.getActivity(context, REQ_WATCH + (m.id.hashCode() and 0xff), PhoneMessageActivity.watch(context, m.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val public = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("New message").setContentText("Open Portal Toolkit to watch").build()
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(PhoneMessageCopy.notifyTitle(m))
            .setContentText(PhoneMessageCopy.clock(m.durationMs))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(watch).setAutoCancel(true)
            .addAction(0, "Watch", watch)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(BASE_ID + (m.id.hashCode() and 0xff), n) }
            .onFailure { Log.w(TAG, "message notification failed: ${it.javaClass.simpleName}") }
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Video messages", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Video messages left for you by a Portal"
        })
    }
}
