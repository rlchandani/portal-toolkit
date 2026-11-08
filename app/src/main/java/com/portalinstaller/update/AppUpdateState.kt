/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.update

/** Where Portal Toolkit's own update is. Replaced, never mutated. */
sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Checking : AppUpdateState
    data class UpToDate(val checkedAtMs: Long) : AppUpdateState
    data class Available(val manifest: OtaManifest) : AppUpdateState
    data class Downloading(val manifest: OtaManifest, val done: Long) : AppUpdateState
    data class Verifying(val manifest: OtaManifest) : AppUpdateState
    data class Installing(val manifest: OtaManifest) : AppUpdateState
    /** Waiting for "Install unknown apps" to be allowed; coming back with it on carries on. */
    data class NeedsPermission(val manifest: OtaManifest) : AppUpdateState
    /** [manifest] is null when the check itself failed. */
    data class Failed(val manifest: OtaManifest?, val reason: String) : AppUpdateState
}

enum class RowAction { CHECK, UPDATE, NONE }

/** What the Settings row shows; [progress] is 0..1 for a download, null otherwise. */
data class AppUpdateRowText(
    val title: String,
    val status: String,
    val action: RowAction,
    val actionLabel: String,
    val busy: Boolean,
    val progress: Float?,
    val isError: Boolean,
)

/** Pure copy for the Settings row ("Portal Toolkit 1.8.9 · Up to date · Check for updates") and the Home banner. */
object AppUpdateRow {
    private const val CHECK = "Check for updates"
    private const val UPDATE = "Update"
    private const val RETRY = "Try again"
    private const val PERCENT = 100

    fun present(installedVersion: String, state: AppUpdateState): AppUpdateRowText {
        val title = "${OtaCopy.APP} $installedVersion"
        fun row(status: String, action: RowAction, label: String = "", busy: Boolean = false,
                progress: Float? = null, error: Boolean = false) =
            AppUpdateRowText(title, status, action, label, busy, progress, error)
        return when (state) {
            AppUpdateState.Idle -> row("", RowAction.CHECK, CHECK)
            AppUpdateState.Checking -> row("Checking for updates…", RowAction.NONE, busy = true)
            is AppUpdateState.UpToDate -> row("Up to date", RowAction.CHECK, CHECK)
            is AppUpdateState.Available -> row(listOf("${state.manifest.versionName} available", state.manifest.notes)
                .filter { it.isNotBlank() }.joinToString(" · "), RowAction.UPDATE, UPDATE)
            is AppUpdateState.Downloading -> {
                val fraction = (state.done.toFloat() / state.manifest.size).coerceIn(0f, 1f)
                row("Downloading ${state.manifest.versionName} · ${(fraction * PERCENT).toInt()}%", RowAction.NONE,
                    busy = true, progress = fraction)
            }
            is AppUpdateState.Verifying -> row("Checking the download…", RowAction.NONE, busy = true)
            is AppUpdateState.Installing -> row(OtaCopy.installing(state.manifest.versionName), RowAction.NONE, busy = true)
            is AppUpdateState.NeedsPermission -> row(OtaCopy.ALLOW_SOURCE, RowAction.UPDATE, UPDATE)
            is AppUpdateState.Failed -> row(state.reason, if (state.manifest != null) RowAction.UPDATE else RowAction.CHECK,
                RETRY, error = true)
        }
    }

    /** The Home banner's line, only while an update is ready to install. */
    fun banner(state: AppUpdateState): String? = when (state) {
        is AppUpdateState.Available -> "${OtaCopy.available(state.manifest.versionName)}"
        is AppUpdateState.NeedsPermission -> "${OtaCopy.available(state.manifest.versionName)}"
        else -> null
    }
}
