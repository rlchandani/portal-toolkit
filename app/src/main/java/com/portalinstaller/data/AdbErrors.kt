package com.portalinstaller.data

import android.content.Context
import com.portalinstaller.R

/** Categorii de erori ADB, mapate la resurse de string in [AdbErrors.formatReason]. */
enum class AdbReasonKey {
    DEVICE_OFFLINE,
    CONNECTION_CLOSED,
    TIMED_OUT,
    PERMISSION_DENIED,
    NO_SPACE_LEFT,
    INSTALL_VERSION_DOWNGRADE,
    INSTALL_UPDATE_INCOMPATIBLE,
    INSTALL_INVALID_APK,
    INSTALL_OLDER_SDK,
    INSTALL_PARSE_FAILED,
    SECURITY_EXCEPTION,
    FALLBACK
}

/**
 * Clasifica un mesaj ADB brut fara a atinge resursele Android (testabil pe JVM).
 * Ordinea conditiilor reproduce fidel vechiul `formatAdbReason` privat.
 */
internal fun classifyAdbReason(rawMessage: String?): AdbReasonKey {
    val message = rawMessage?.trim().orEmpty()
    if (message.isBlank()) return AdbReasonKey.FALLBACK

    val normalized = message.lowercase()
    return when {
        normalized.contains("device offline") -> AdbReasonKey.DEVICE_OFFLINE
        normalized.contains("broken pipe") || normalized.contains("connection reset") || normalized.contains("closed") ->
            AdbReasonKey.CONNECTION_CLOSED
        normalized.contains("timeout") || normalized.contains("timed out") -> AdbReasonKey.TIMED_OUT
        normalized.contains("permission denied") -> AdbReasonKey.PERMISSION_DENIED
        normalized.contains("no space left") -> AdbReasonKey.NO_SPACE_LEFT
        normalized.contains("install_failed_version_downgrade") -> AdbReasonKey.INSTALL_VERSION_DOWNGRADE
        normalized.contains("install_failed_update_incompatible") -> AdbReasonKey.INSTALL_UPDATE_INCOMPATIBLE
        normalized.contains("install_failed_invalid_apk") -> AdbReasonKey.INSTALL_INVALID_APK
        normalized.contains("install_failed_older_sdk") -> AdbReasonKey.INSTALL_OLDER_SDK
        normalized.contains("install_parse_failed") -> AdbReasonKey.INSTALL_PARSE_FAILED
        normalized.contains("securityexception") -> AdbReasonKey.SECURITY_EXCEPTION
        else -> AdbReasonKey.FALLBACK
    }
}

/** Formatter partajat de erori ADB, folosit de managerele Phase 3 si de [PortalConnectionManager]. */
object AdbErrors {
    fun formatReason(context: Context, rawMessage: String?, fallback: String): String {
        val message = rawMessage?.trim().orEmpty()
        return when (classifyAdbReason(rawMessage)) {
            AdbReasonKey.DEVICE_OFFLINE -> context.getString(R.string.error_device_offline)
            AdbReasonKey.CONNECTION_CLOSED -> context.getString(R.string.error_connection_closed)
            AdbReasonKey.TIMED_OUT -> context.getString(R.string.error_operation_timed_out)
            AdbReasonKey.PERMISSION_DENIED -> context.getString(R.string.error_permission_denied)
            AdbReasonKey.NO_SPACE_LEFT -> context.getString(R.string.error_no_space_left)
            AdbReasonKey.INSTALL_VERSION_DOWNGRADE -> context.getString(R.string.error_install_version_downgrade)
            AdbReasonKey.INSTALL_UPDATE_INCOMPATIBLE -> context.getString(R.string.error_install_update_incompatible)
            AdbReasonKey.INSTALL_INVALID_APK -> context.getString(R.string.error_install_invalid_apk)
            AdbReasonKey.INSTALL_OLDER_SDK -> context.getString(R.string.error_install_older_sdk)
            AdbReasonKey.INSTALL_PARSE_FAILED -> context.getString(R.string.error_install_parse_failed)
            AdbReasonKey.SECURITY_EXCEPTION -> context.getString(R.string.error_security_exception)
            AdbReasonKey.FALLBACK -> message.ifBlank { fallback }
        }
    }
}
