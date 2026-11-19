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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.portalinstaller.ui.calls.PhoneCallActivity

/**
 * The incoming-call notification (full-screen intent + Answer/Decline), the missed-call
 * notification and the ringtone. The ringtone uses the phone's ring stream, so silent and
 * vibrate modes and Do Not Disturb apply as for any call.
 */
object CallAlerts {
    private const val TAG = "ToolkitCalls"
    const val CHANNEL = "calls"
    private const val INCOMING_ID = 0x7300
    private const val MISSED_ID = 0x7301
    private const val REQ_OPEN = 1
    private const val REQ_ANSWER = 2
    private const val REQ_DECLINE = 3
    private var ringtone: Ringtone? = null

    fun incoming(context: Context, call: PhoneCall) {
        if (!allowed(context)) return
        ensureChannel(context)
        val open = PendingIntent.getActivity(context, REQ_OPEN, PhoneCallActivity.intent(context), flags())
        // Answer opens the call screen (it asks for camera/mic first); a receiver can't start an activity on Android 12+.
        val answer = PendingIntent.getActivity(context, REQ_ANSWER, PhoneCallActivity.intent(context, answer = true), flags())
        val decline = PendingIntent.getBroadcast(context, REQ_DECLINE, action(context, CallActionReceiver.DECLINE), flags())
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(call.portal.name)
            .setContentText(if (call.video) "Portal video call" else "Portal audio call")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setFullScreenIntent(open, true)
            .setContentIntent(open)
            .addAction(0, "Decline", decline)
            .addAction(0, "Answer", answer)
            .setTimeoutAfter(PhoneCallTimings.RING_MS + PhoneCallTimings.RING_GRACE_MS)
            .build()
        post(context, INCOMING_ID, n)
    }

    fun clearIncoming(context: Context) { runCatching { NotificationManagerCompat.from(context).cancel(INCOMING_ID) } }

    fun missed(context: Context, ended: EndedPhoneCall) {
        if (!allowed(context)) return
        ensureChannel(context)
        val open = PendingIntent.getActivity(context, REQ_OPEN, context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: PhoneCallActivity.intent(context), flags())
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_call_missed)
            .setContentTitle("Missed call · ${ended.portal.name}")
            .setContentText("Open Calls in Portal Toolkit to call back.")
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setContentIntent(open).setAutoCancel(true)
            .build()
        post(context, MISSED_ID, n)
    }

    fun startRingtone(context: Context) {
        stopRingtone()
        ringtone = runCatching {
            RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                if (Build.VERSION.SDK_INT >= 28) isLooping = true
                play()
            }
        }.onFailure { Log.w(TAG, "ringtone failed: ${it.javaClass.simpleName}") }.getOrNull()
    }

    fun stopRingtone() { runCatching { ringtone?.stop() }; ringtone = null }

    private fun action(context: Context, what: String) = Intent(context, CallActionReceiver::class.java).setAction(what)

    private fun flags() = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun allowed(context: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun post(context: Context, id: Int, n: android.app.Notification) {
        runCatching { NotificationManagerCompat.from(context).notify(id, n) }
            .onFailure { Log.w(TAG, "call notification failed: ${it.javaClass.simpleName}") }
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Calls", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Calls from your Portals while Portal Toolkit is open"
            setSound(null, null) // the ringtone plays itself, once, and stops on answer
        })
    }
}

/** Answer / Decline from the incoming-call notification (not exported). */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == DECLINE) PhoneCalls.decline()
    }

    companion object {
        const val DECLINE = "com.portalinstaller.calls.DECLINE"
    }
}
