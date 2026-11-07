/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.share

import com.portalinstaller.data.companion.CompanionException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException

/**
 * Maps a failed transfer to a named, honest outcome. The Portal publishes a photo only after it
 * received every byte (RemoteCompanionPhotoPolicy.store checks the length before renaming), so a
 * transfer that stopped short is a definite failure, while one that lost the reply after the last
 * byte stays "not confirmed".
 */
object ShareFailures {
    fun upload(error: Throwable, room: String, sentBytes: Long, totalBytes: Long): ItemState {
        val pct = if (totalBytes <= 0) 0 else ((sentBytes * 100) / totalBytes).toInt().coerceIn(0, 99)
        val transportLost = error is CompanionException && error.error.code == "upload_unconfirmed" && error.error.status == null ||
            error is IOException && error !is CompanionException
        if (transportLost) return when {
            sentBytes <= 0 -> ItemState(ItemStatus.FAILED, 0, "Couldn’t reach $room.")
            sentBytes < totalBytes -> ItemState(ItemStatus.FAILED, sentBytes, "The connection to $room dropped at $pct%.")
            else -> ItemState(ItemStatus.UNCONFIRMED, sentBytes)
        }
        if (error is CompanionException && error.error.code == "upload_unconfirmed") return ItemState(ItemStatus.UNCONFIRMED, sentBytes)
        return ItemState(ItemStatus.FAILED, sentBytes, reason(error, room))
    }

    fun message(error: Throwable, room: String): ItemState = when {
        error is ConnectException || error is NoRouteToHostException || error is UnknownHostException ->
            ItemState(ItemStatus.FAILED, reason = "Couldn’t reach $room.")
        error is IOException && error !is CompanionException -> ItemState(ItemStatus.UNCONFIRMED)
        else -> ItemState(ItemStatus.FAILED, reason = reason(error, room))
    }

    fun reason(error: Throwable, room: String): String {
        if (error is IllegalArgumentException) return error.message ?: "This file can’t be shared."
        val e = (error as? CompanionException)?.error ?: return "Couldn’t reach $room."
        return when {
            e.status == 507 || e.code in setOf("storage_full", "insufficient_storage", "no_space") -> "$room is out of storage. Free up space, then retry."
            e.status == 401 || e.code == "unauthorized" -> "$room needs to be paired with this phone again."
            e.code == "sharing_off" -> "Sharing is turned off on $room."
            e.status == 404 -> "$room needs an Immortal update to receive shares."
            e.status == 413 || e.code == "body_too_large" -> "This is too big for $room."
            e.code == "invalid_image" -> "$room couldn’t open this image."
            e.code == "incomplete_upload" -> "The connection to $room dropped before the whole file arrived."
            e.code == "folder_unavailable" -> "$room’s photo folder isn’t available."
            else -> "$room refused it (${e.code})."
        }
    }
}
