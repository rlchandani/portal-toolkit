package com.portalinstaller.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.portalinstaller.R
import com.portalinstaller.data.DeviceStatus

internal fun formatFileSize(bytes: Long): String {
    val locale = java.util.Locale.getDefault()
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(locale, "%.1f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format(locale, "%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format(locale, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

@Composable
internal fun formatStorage(deviceStatus: DeviceStatus): String {
    val free = deviceStatus.storageFreeBytes
    val total = deviceStatus.storageTotalBytes
    return if (free != null && total != null) {
        stringResource(
            R.string.storage_free_of_total,
            formatFileSize(free),
            formatFileSize(total)
        )
    } else {
        stringResource(R.string.unavailable)
    }
}

@Composable
internal fun formatRam(ramFreeBytes: Long?): String {
    return ramFreeBytes?.let {
        stringResource(R.string.ram_free_amount, formatFileSize(it))
    } ?: stringResource(R.string.unavailable)
}
