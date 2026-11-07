/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.share

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.portalinstaller.data.companion.CompanionPhotos
import com.portalinstaller.data.companion.CompanionUpload
import java.io.File
import java.io.IOException

/**
 * Shared content URIs stop being readable once the share sheet closes, so every shared photo is
 * copied into private storage first. The copy is what gets sent now, later (away from home) and on
 * "Retry"; it is deleted with the job.
 */
object ShareStaging {
    private val extensions = mapOf("image/jpeg" to "jpg", "image/png" to "png", "image/webp" to "webp", "image/gif" to "gif")
    private val contentTypes = mapOf("jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "webp" to "image/webp", "gif" to "image/gif")

    /** A Portal-acceptable file name: the original when valid, otherwise `image-N.ext`. */
    fun safeName(display: String?, index: Int, mime: String?): String {
        val fallbackExt = extensions[mime?.lowercase()] ?: "jpg"
        val candidate = display?.trim().orEmpty()
        val valid = runCatching { CompanionPhotos.safeName(candidate) }.isSuccess
        if (valid) return candidate
        val ext = candidate.substringAfterLast('.', "").lowercase().takeIf { it in contentTypes } ?: fallbackExt
        val stem = candidate.substringBeforeLast('.').filter { it.isLetterOrDigit() || it in " _-" }.trim().take(100)
        val named = if (stem.isNotEmpty() && candidate.substringAfterLast('.', "").lowercase() == ext) "$stem.$ext" else "image-${index + 1}.$ext"
        return runCatching { CompanionPhotos.safeName(named) }.getOrDefault("image-${index + 1}.$fallbackExt")
    }

    fun contentType(name: String): String = contentTypes[name.substringAfterLast('.', "").lowercase()] ?: "image/jpeg"

    /** Copies [uris] into [store] for [jobId]. Runs on an IO thread. Fails fast on oversize files. */
    fun stage(context: Context, uris: List<Uri>, store: ShareJobStore, jobId: String): List<ShareItem> {
        val resolver = context.applicationContext.contentResolver
        val used = mutableSetOf<String>()
        return uris.mapIndexed { index, uri ->
            var display: String? = null
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { display = c.getString(it) }
            }
            var name = safeName(display, index, resolver.getType(uri))
            if (!used.add(name)) name = safeName("${name.substringBeforeLast('.')}-${index + 1}.${name.substringAfterLast('.')}", index, null).also { used += it }
            val itemId = "p${index + 1}"
            val target = store.itemFile(jobId, itemId).apply { parentFile?.mkdirs() }
            var bytes = 0L
            try {
                (resolver.openInputStream(uri) ?: throw IOException("$name can’t be read.")).use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            bytes += read
                            require(bytes <= CompanionPhotos.MAX_IMAGE_BYTES) { "$name is larger than the Portal’s 20 MiB limit." }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                require(bytes > 0) { "$name is empty." }
            } catch (error: Exception) { target.delete(); throw error }
            ShareItem(itemId, name, bytes)
        }
    }

    /** Opens the staged copy for one upload attempt. Disposing does not delete it (Retry needs it). */
    fun upload(store: ShareJobStore, job: ShareJob, item: ShareItem): CompanionUpload {
        val file: File = store.itemFile(job.id, item.id)
        if (!file.isFile) throw IOException("${item.name} is no longer on this phone. Share it again.")
        return CompanionUpload(item.name, contentType(item.name), file.length()) { file.inputStream() }
    }
}
