package com.portalinstaller.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Citeste metadate dintr-un `content://` URI. Interogarile ContentResolver ating
 * disc/IPC si pot fi lente, deci ambele functii sunt suspend si ruleaza pe
 * [Dispatchers.IO] — nu se apeleaza niciodata pe firul principal.
 */
object ContentUriHelper {
    suspend fun getDisplayName(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        var name: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    name = cursor.getString(nameIndex)
                }
            }
        }
        name
    }

    suspend fun getSize(context: Context, uri: Uri): Long = withContext(Dispatchers.IO) {
        var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }
        size
    }
}
