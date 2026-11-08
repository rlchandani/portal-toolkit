/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.update

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException

/** The daily background check (only when there's a network). */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        AppUpdater.check(applicationContext, quiet = true)
        Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.w(AppUpdater.TAG, "Update check failed: ${error.message}")
        Result.retry()
    }
}

/** Update: download, verify and hand to the installer in a foreground worker, so leaving the app doesn't stop it. */
class UpdateInstallWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo =
        UpdateNotifier.progress(applicationContext, "Updating ${OtaCopy.APP}…")

    override suspend fun doWork(): Result {
        try {
            setForeground(getForegroundInfo())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IllegalStateException) {
            // Android may refuse a foreground service from the background; the expedited job still runs.
            Log.w(AppUpdater.TAG, "Updating without a foreground notification: ${error.message}")
        }
        UpdateNotifier.cancelAvailable(applicationContext)
        AppUpdater.install(applicationContext)
        return Result.success()
    }
}
