/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.companion

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream
import org.json.JSONObject

data class CompanionPhoto(val name: String, val path: String, val size: Long, val modified: Long, val raw: JSONObject = JSONObject())
data class CompanionPhotoFolder(val path: String, val count: Int)
data class PhotoTransferFailure(val name: String, val error: CompanionError)
data class PhotoTransferState(
    val total: Int,
    val completed: Int = 0,
    val bytesSent: Long = 0,
    val running: Boolean = true,
    val failures: List<PhotoTransferFailure> = emptyList(),
)

/** Pure import seam; production streams ContentResolver data without retaining full images. */
class PhotoUploadSource(val name: String, val prepare: () -> CompanionUpload) {
    override fun toString() = "PhotoUploadSource(metadata=redacted)"
}
class CompanionUpload(
    val name: String,
    val contentType: String,
    val length: Long,
    val dispose: () -> Unit = {},
    val open: () -> InputStream,
) {
    override fun toString() = "CompanionUpload(length=$length, metadata=redacted)"
}

object CompanionPhotos {
    const val DEFAULT_FOLDER = "/sdcard/Pictures/Immortal"
    const val MAX_IMAGE_BYTES = 20L * 1024 * 1024
    private val mimeTypes = mapOf("jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "webp" to "image/webp", "gif" to "image/gif")
    fun safeName(name: String): String {
        require(name.isNotBlank() && name.length <= 128 && !name.startsWith('.') && !name.contains("..") && name.none { it == '/' || it == '\\' || it.isISOControl() }) { "Use a filename of at most 128 characters, without path separators or hidden-file prefixes." }
        require(name.substringAfterLast('.', "").lowercase() in mimeTypes) { "Use a JPEG, PNG, WebP, or GIF image." }
        return name
    }
    fun folder(path: String): String = exactFolder(path.trim())
    /** Discovered filesystem names are identities; trailing whitespace is significant. */
    fun exactFolder(path: String): String = sharedStoragePath(path.trimEnd('/'))
    fun photoPath(path: String): String {
        safeName(path.substringAfterLast('/'))
        return sharedStoragePath(path)
    }
    private fun sharedStoragePath(path: String): String {
        require((path == "/sdcard" || path.startsWith("/sdcard/") || path == "/storage/emulated/0" || path.startsWith("/storage/emulated/0/")) &&
            path.split('/').none { it == "." || it == ".." } && path.none { it == '\\' || it.isISOControl() }) { "Choose a folder under shared storage." }
        // File paths must retain directory whitespace; only folder input is trimmed.
        return if (path == "/storage/emulated/0" || path.startsWith("/storage/emulated/0/"))
            "/sdcard" + path.removePrefix("/storage/emulated/0") else path
    }
    fun parse(response: JSONObject): List<CompanionPhoto> {
        val files = response.optJSONArray("files") ?: return emptyList()
        return (0 until files.length()).mapNotNull { index ->
            val file = files.optJSONObject(index) ?: return@mapNotNull null
            val path = file.optString("path")
            if (runCatching { photoPath(path) }.isFailure) return@mapNotNull null
            CompanionPhoto(file.optString("name", path.substringAfterLast('/')), path, file.optLong("size"), file.optLong("modified"), JSONObject(file.toString()))
        }
    }

    fun folders(response: JSONObject): List<CompanionPhotoFolder> {
        val folders = response.optJSONArray("folders") ?: return emptyList()
        return (0 until folders.length()).mapNotNull { index ->
            val entry = folders.optJSONObject(index) ?: return@mapNotNull null
            val path = runCatching { exactFolder(entry.getString("path")) }.getOrNull() ?: return@mapNotNull null
            val count = entry.optInt("count")
            if (count <= 0) return@mapNotNull null
            CompanionPhotoFolder(path, count)
        }.distinctBy { it.path }
    }

    fun uriSources(context: Context, uris: List<Uri>): List<PhotoUploadSource> {
        val app = context.applicationContext
        return uris.mapIndexed { index, uri ->
            // Display names and sizes are resolved on IO when prepare() is called.
            PhotoUploadSource("Image ${index + 1}") {
                val resolver = app.contentResolver
                var name: String? = null
                var size: Long? = null
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIndex >= 0) name = cursor.getString(nameIndex)
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex).takeIf { it > 0 }
                    }
                }
                val mime = resolver.getType(uri)
                val extension = mimeTypes.entries.firstOrNull { it.value == mime }?.key ?: "jpg"
                val safe = safeName(name ?: "image-${index + 1}.$extension")
                val contentType = mimeTypes.getValue(safe.substringAfterLast('.').lowercase())
                val knownSize = size
                if (knownSize != null) {
                    require(knownSize <= MAX_IMAGE_BYTES) { "This image exceeds the Portal's 20 MiB upload limit. Choose a smaller image." }
                    CompanionUpload(safe, contentType, knownSize) {
                        resolver.openInputStream(uri) ?: throw java.io.IOException("The selected image cannot be read.")
                    }
                } else {
                    // Providers with unknown sizes are spooled to private disk so the Portal receives Content-Length.
                    val staged = File.createTempFile("companion-image-", ".tmp", app.cacheDir)
                    try {
                        var bytes = 0L
                        (resolver.openInputStream(uri) ?: throw java.io.IOException("The selected image cannot be read.")).use { input ->
                            staged.outputStream().use { output ->
                                val buffer = ByteArray(32 * 1024)
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    if (read == 0) continue
                                    bytes += read
                                    require(bytes <= MAX_IMAGE_BYTES) { "This image exceeds the Portal's 20 MiB upload limit. Choose a smaller image." }
                                    output.write(buffer, 0, read)
                                }
                            }
                        }
                        require(bytes > 0) { "The selected image is empty." }
                        CompanionUpload(safe, contentType, bytes, dispose = { staged.delete(); Unit }, open = { staged.inputStream() })
                    } catch (error: Exception) { staged.delete(); throw error }
                }
            }
        }
    }
}
