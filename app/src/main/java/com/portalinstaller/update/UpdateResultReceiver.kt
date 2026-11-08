/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log

/** Android's installer saying how it went: needs a tap to confirm (the first time), done, or why not. */
class UpdateResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> confirm(context, intent)
            PackageInstaller.STATUS_SUCCESS -> UpdateNotifier.cancelConfirm(context) // the app restarts as the new version
            else -> {
                Log.w(AppUpdater.TAG, "Install failed with status $status")
                AppUpdater.failed(context, reason(status))
            }
        }
    }

    private fun confirm(context: Context, intent: Intent) {
        val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
        if (confirm == null) {
            AppUpdater.failed(context, OtaCopy.INSTALL_FAILED)
            return
        }
        val started = runCatching { context.startActivity(Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        // In the background Android may silently block the dialog; the notification lets you finish it.
        UpdateNotifier.confirm(context, Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        if (!started) Log.w(AppUpdater.TAG, "Install confirmation waits for a tap on the notification")
    }

    internal companion object {
        fun reason(status: Int): String = when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> OtaCopy.CANCELLED
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> OtaCopy.CONFLICT
            PackageInstaller.STATUS_FAILURE_STORAGE -> OtaCopy.STORAGE
            PackageInstaller.STATUS_FAILURE_INVALID -> OtaCopy.UNREADABLE_APK
            else -> OtaCopy.INSTALL_FAILED
        }
    }
}
