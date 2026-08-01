package com.portalinstaller.data.screensaver

import java.io.File
import java.security.MessageDigest

/**
 * Cache pe disc pentru miniaturile imaginilor de pe Portal. Cheia este un hash
 * stabil peste (cale, data modificarii, dimensiune), astfel incat o imagine
 * modificata pe dispozitiv sa invalideze automat miniatura veche.
 *
 * Stocarea foloseste [android.content.Context.getCacheDir]; o politica LRU
 * simpla plafoneaza dimensiunea totala la [MAX_BYTES] stergand mai intai
 * fisierele cel mai putin recent accesate.
 */
object ThumbnailCache {

    private const val DIR_NAME = "screensaver_thumbs"
    const val MAX_BYTES = 100L * 1024 * 1024 // ~100 MB

    /** Hash stabil, redat hexazecimal, folosit ca nume de fisier al miniaturii. */
    fun keyFor(path: String, mtime: String, sizeBytes: Long): String {
        val raw = "$path|$mtime|$sizeBytes"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun fileFor(cacheDir: File, key: String): File {
        val dir = File(cacheDir, DIR_NAME).apply { mkdirs() }
        return File(dir, "$key.jpg")
    }

    /** Sterge fisierele cele mai vechi pana cand totalul scade sub [MAX_BYTES]. */
    fun trim(cacheDir: File) {
        val dir = File(cacheDir, DIR_NAME)
        val files = dir.listFiles()?.toList().orEmpty()
        var total = files.sumOf { it.length() }
        if (total <= MAX_BYTES) return
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= MAX_BYTES) return
            total -= file.length()
            file.delete()
        }
    }
}
