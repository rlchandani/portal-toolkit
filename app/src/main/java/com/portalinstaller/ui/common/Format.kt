package com.portalinstaller.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.portalinstaller.R
import com.portalinstaller.data.DeviceStatus

/**
 * Formatează secunde de uptime într-o etichetă compactă cu cele mai
 * semnificative două unități (ex. "14d 6h", "3h 25m", "7m", "45s").
 */
internal fun formatUptime(seconds: Long): String {
    val safe = if (seconds < 0) 0 else seconds
    val days = safe / 86_400
    val hours = (safe % 86_400) / 3_600
    val minutes = (safe % 3_600) / 60
    val secs = safe % 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "${secs}s"
    }
}

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
