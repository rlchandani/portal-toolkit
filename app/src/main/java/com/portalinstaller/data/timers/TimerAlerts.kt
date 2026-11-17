/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.timers

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.portalinstaller.MainActivity
import com.portalinstaller.data.companion.CompanionClient
import com.portalinstaller.data.companion.FileCompanionSessionStore
import com.portalinstaller.data.dinner.DinnerRepository
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

private const val TAG = "ToolkitTimers"

/** The production repository (OkHttp, saved pairings, this phone's name, monotonic clock). */
fun timersRepository(context: Context): TimersRepository = TimersRepository(CompanionClient(),
    FileCompanionSessionStore.create(context.applicationContext), DinnerRepository.phoneName(context),
    clock = SystemClock::elapsedRealtime) { Log.w(TAG, it) }

/**
 * Schedules the phone's "timer done" checks ([TimerAlertPlan]) with WorkManager and posts the
 * notification. [sync] runs after every refresh of the Timers page; it only touches WorkManager
 * when a running timer appears, changes (seq) or stops running.
 */
object TimerAlerts {
    private const val CHANNEL = "timers"
    private const val WORK_PREFIX = "timer-done-"
    private const val PREFS = "timer_alerts"
    private const val KEY_NOTIFIED = "notified"
    private const val NOTIFICATION_BASE = 0x7100
    private const val NOTIFICATION_SPAN = 0xFF
    internal const val IN_ID = "id"
    internal const val IN_SEQ = "seq"
    internal const val IN_NAME = "name"
    internal const val IN_ROOM = "room"
    internal const val IN_RECHECKS = "rechecks"

    /** id → seq of the check scheduled in this process. */
    private val planned = HashMap<String, Long>()

    @Synchronized fun sync(context: Context, timers: List<PhoneTimer>) {
        val app = context.applicationContext
        val alarms = TimerAlertPlan.alarms(timers, SystemClock.elapsedRealtime())
        val work = WorkManager.getInstance(app)
        (planned.keys - alarms.map { it.id }.toSet()).forEach { id -> work.cancelUniqueWork(WORK_PREFIX + id); planned.remove(id) }
        alarms.filter { planned[it.id] != it.seq }.forEach { alarm ->
            schedule(app, alarm, rechecks = 0)
            planned[alarm.id] = alarm.seq
        }
        TimerAlertPlan.newlyRinging(timers, notified(app)).forEach { notify(app, it.id, it.seq, it.name, it.room, expected = false) }
    }

    /** A re-check from inside the running check appends after it (REPLACE would cancel that worker). */
    internal fun schedule(context: Context, alarm: TimerAlertPlan.Alarm, rechecks: Int,
                          policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
        val request = OneTimeWorkRequestBuilder<TimerDoneWorker>()
            .setInitialDelay(alarm.delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(IN_ID to alarm.id, IN_SEQ to alarm.seq, IN_NAME to alarm.name, IN_ROOM to alarm.room,
                IN_RECHECKS to rechecks))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_PREFIX + alarm.id, policy, request)
    }

    /** One notification per timer (a later one for the same timer replaces it). */
    @Synchronized fun notify(context: Context, id: String, seq: Long, name: String, room: String, expected: Boolean) {
        val key = TimerAlertPlan.key(id, seq)
        val seen = notifiedList(context)
        if (key in seen) return
        prefs(context).edit().putString(KEY_NOTIFIED, TimerAlertPlan.remember(seen, key).joinToString("\n")).apply()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel(context)
        val notificationId = NOTIFICATION_BASE + (id.hashCode() and NOTIFICATION_SPAN)
        val open = PendingIntent.getActivity(context, notificationId, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = if (expected) "Should be done now · $room" else TimerCopy.doneText(room)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(TimerCopy.doneTitle(name)).setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open).setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(notificationId, notification) }
            .onFailure { Log.w(TAG, "timer notification failed: ${it.javaClass.simpleName}") }
    }

    private fun notified(context: Context): Set<String> = notifiedList(context).toSet()

    private fun notifiedList(context: Context): List<String> =
        prefs(context).getString(KEY_NOTIFIED, null)?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Household timers", NotificationManager.IMPORTANCE_HIGH))
    }
}

/** The scheduled "is it done?" check: asks the Portals once, then notifies, waits or stays quiet. */
class TimerDoneWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(TimerAlerts.IN_ID)?.takeIf(PhoneTimer::isId) ?: return Result.success()
        val seq = inputData.getLong(TimerAlerts.IN_SEQ, 0L)
        val name = inputData.getString(TimerAlerts.IN_NAME) ?: "Timer"
        val room = inputData.getString(TimerAlerts.IN_ROOM) ?: "Portal"
        val rechecks = inputData.getInt(TimerAlerts.IN_RECHECKS, 0)
        return try {
            val repository = timersRepository(applicationContext)
            repository.refresh()
            val state = repository.state.value
            val found = state.timers.firstOrNull { it.id == id }
            val reachable = !state.notOnHomeWifi && state.portals > 0
            when (val check = TimerAlertPlan.check(found, reachable, rechecks, SystemClock.elapsedRealtime())) {
                is TimerAlertPlan.Check.Notify -> {
                    val ringSeq = if (check.expected) seq else found?.seq ?: seq
                    TimerAlerts.notify(applicationContext, id, ringSeq, found?.name ?: name, found?.room ?: room, check.expected)
                }
                is TimerAlertPlan.Check.Later -> TimerAlerts.schedule(applicationContext,
                    TimerAlertPlan.Alarm(id, found?.seq ?: seq, found?.name ?: name, found?.room ?: room, check.delayMs), rechecks + 1,
                    ExistingWorkPolicy.APPEND_OR_REPLACE)
                TimerAlertPlan.Check.Quiet -> Unit
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "timer check failed: ${error.javaClass.simpleName}")
            TimerAlerts.notify(applicationContext, id, seq, name, room, expected = true)
            Result.success()
        }
    }
}
