package com.portalinstaller.data.screensaver

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One MediaStore image bucket (maps 1:1 to a Google Photos album on the phone). */
data class GooglePhotoAlbum(
    val bucketId: String,
    val displayName: String,
    val photoCount: Int,
)

/** A single image entry ready to feed into the existing upload pipeline. */
data class GooglePhotoEntry(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
)

/**
 * Reads photo albums and their contents from MediaStore — no OAuth, no API keys.
 *
 * Google Photos syncs the user's library to MediaStore. Each album becomes a
 * "bucket" (BUCKET_ID / BUCKET_DISPLAY_NAME). The phone-side content resolver
 * exposes them at content://media/external/images/media with the same content://
 * URIs the existing upload pipeline already handles via openInputStream().
 *
 * Permission required: READ_MEDIA_IMAGES (API 33+) or READ_EXTERNAL_STORAGE
 * (API 26–32). The existing system photo picker implicitly grants READ_MEDIA_IMAGES
 * while the picker session is active; for background sync we need the manifest
 * permission (added to AndroidManifest.xml alongside this file).
 */
object GooglePhotosAlbumReader {

    private val IMAGE_COLLECTION: Uri
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    /** All distinct image buckets on the device, sorted by name. */
    suspend fun listAlbums(context: Context): List<GooglePhotoAlbum> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        )
        val buckets = mutableMapOf<String, Pair<String, Int>>() // id -> (name, count)
        context.contentResolver.query(
            IMAGE_COLLECTION,
            projection,
            null, null,
            "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} COLLATE NOCASE ASC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getString(idCol) ?: continue
                val name = cursor.getString(nameCol)?.ifBlank { null } ?: continue
                val (_, count) = buckets.getOrDefault(id, name to 0)
                buckets[id] = name to (count + 1)
            }
        }
        buckets.map { (id, pair) -> GooglePhotoAlbum(id, pair.first, pair.second) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
    }

    /** All images in a bucket, as URIs ready for [ScreensaverViewModel.uploadPhotos]. */
    suspend fun listPhotos(context: Context, bucketId: String): List<GooglePhotoEntry> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
        )
        val results = mutableListOf<GooglePhotoEntry>()
        context.contentResolver.query(
            IMAGE_COLLECTION,
            projection,
            "${MediaStore.Images.Media.BUCKET_ID} = ?",
            arrayOf(bucketId),
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: "photo_$id.jpg"
                val size = cursor.getLong(sizeCol)
                val uri = Uri.withAppendedPath(IMAGE_COLLECTION, id.toString())
                results.add(GooglePhotoEntry(uri, name, size))
            }
        }
        results
    }

    /** True when a URI comes from the Google Photos content provider (not generic MediaStore). */
    fun isGooglePhotosUri(uri: Uri): Boolean =
        uri.authority?.contains("com.google.android.apps.photos") == true
}
