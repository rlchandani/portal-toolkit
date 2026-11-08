/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.controlcenter

import android.content.Context
import android.content.Intent
import com.portalinstaller.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Open remote" from the share sheet: opens Calder Pocket on the Remote tab for one paired Portal.
 * Only a device id travels in the (explicit, in-app) intent; the session stays in its store.
 */
object RemoteLaunch {
    private const val EXTRA_DEVICE = "com.portalinstaller.REMOTE_DEVICE"
    private val pendingFlow = MutableStateFlow<String?>(null)

    /** The Portal whose remote should open next, until [consume]d by the control center. */
    val pending: StateFlow<String?> = pendingFlow.asStateFlow()

    fun intent(context: Context, deviceId: String): Intent = Intent(context, MainActivity::class.java)
        .putExtra(EXTRA_DEVICE, deviceId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** Reads (and strips) the request from an intent MainActivity received. */
    fun accept(intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_DEVICE)?.takeIf { it.isNotBlank() && it.length <= 200 } ?: return
        intent.removeExtra(EXTRA_DEVICE)
        pendingFlow.value = id
    }

    fun consume(id: String) { pendingFlow.compareAndSet(id, null) }
}
