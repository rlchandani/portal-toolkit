/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.controlcenter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.portalinstaller.data.images.companionImageSampleSize

internal const val PHOTO_GRID_IMAGE_EDGE = 512
internal const val PHOTO_PREVIEW_IMAGE_EDGE = 1024
internal const val APP_ICON_IMAGE_EDGE = 256

/** Call from the existing IO loaders. Compressed transport/cache limits are independent. */
internal fun decodeCompanionDisplayBitmap(bytes: ByteArray, maxEdge: Int): Bitmap? {
    if (bytes.isEmpty() || maxEdge <= 0) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val sample = companionImageSampleSize(bounds.outWidth, bounds.outHeight, maxEdge) ?: return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
    // Validate actual decoder output before transferring ownership to a displayed ImageBitmap.
    if (bitmap.width > maxEdge || bitmap.height > maxEdge) {
        bitmap.recycle()
        return null
    }
    return bitmap
}
