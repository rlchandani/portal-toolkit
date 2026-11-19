/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.house

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal

/**
 * The phone's own confirm for asking actions: the system biometric prompt (fingerprint or face,
 * or the screen lock where the phone allows it) on Android 9+, through the framework API so no
 * extra library ships. [onUnavailable] means "no biometrics here" (none enrolled, no hardware,
 * locked out, Android 8): the caller shows its confirm dialog instead. Cancelling is a no.
 */
internal object HouseBiometric {
    fun available(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> context.getSystemService(BiometricManager::class.java)
            ?.canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> @Suppress("DEPRECATION")
            (context.getSystemService(BiometricManager::class.java)?.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS)
        else -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
    }

    fun prompt(context: Context, title: String, subtitle: String, onOk: () -> Unit, onCancel: () -> Unit, onUnavailable: () -> Unit): CancellationSignal? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || activity(context) == null) { onUnavailable(); return null }
        val executor = context.mainExecutor
        val builder = BiometricPrompt.Builder(context).setTitle(title).setSubtitle(subtitle)
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> builder.setAllowedAuthenticators(AUTHENTICATORS).setConfirmationRequired(false)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> @Suppress("DEPRECATION") builder.setDeviceCredentialAllowed(true).setConfirmationRequired(false)
            else -> builder.setNegativeButton("Cancel", executor) { _, _ -> onCancel() }
        }
        val signal = CancellationSignal()
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
            override fun onAuthenticationError(code: Int, message: CharSequence) {
                if (code in CANCELLED) onCancel() else onUnavailable()
            }
        }
        runCatching { builder.build().authenticate(signal, executor, callback) }.onFailure { onUnavailable() }
        return signal
    }

    private fun activity(context: Context): Activity? {
        var c: Context? = context
        while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
        return null
    }

    private val AUTHENTICATORS: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL else 0

    private val CANCELLED = setOf(BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED, BiometricPrompt.BIOMETRIC_ERROR_CANCELED,
        BiometricPrompt.BIOMETRIC_ERROR_TIMEOUT, BIOMETRIC_ERROR_NEGATIVE_BUTTON)

    /** `BIOMETRIC_ERROR_NEGATIVE_BUTTON` (API 28 constant 13). */
    private const val BIOMETRIC_ERROR_NEGATIVE_BUTTON = 13
}
