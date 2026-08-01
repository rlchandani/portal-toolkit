package com.portalinstaller.data.transfer

/** Progresul unui transfer de octeti. [totalBytes] <= 0 inseamna dimensiune necunoscuta. */
data class TransferProgress(
    val bytesTransferred: Long,
    val totalBytes: Long
) {
    val isDeterminate: Boolean get() = totalBytes > 0L
    val fraction: Float
        get() = if (totalBytes > 0L) (bytesTransferred.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}
