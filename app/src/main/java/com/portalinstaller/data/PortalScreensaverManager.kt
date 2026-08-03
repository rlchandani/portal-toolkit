package com.portalinstaller.data

import com.portalinstaller.data.adb.shellQuote
import com.portalinstaller.data.screensaver.ImageFolder
import com.portalinstaller.data.screensaver.ImageFolderScanner
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Descopera folderele cu imagini de pe Portal si masoara spatiul de stocare.
 * Apelurile ADB (`find`, `du`, `df`) traiesc aici; clasificarea pura ramane in
 * [ImageFolderScanner], testabila fara dispozitiv.
 */
class PortalScreensaverManager {

    private val imageExtensions = listOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic")

    /** Cauta directoarele care contin imagini si le claseaza dupa numar. */
    suspend fun findImageFolders(dadb: Dadb): List<ImageFolder> = withContext(Dispatchers.IO) {
        // Enumera fisierele imagine (case-insensitive) pana la 3 niveluri adancime.
        val namePredicate = imageExtensions.joinToString(" -o ") { "-iname ${shellQuote("*.$it")}" }
        val findCmd = "find /sdcard -maxdepth 4 -type f \\( $namePredicate \\) 2>/dev/null"
        val files = dadb.shell(findCmd).allOutput
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

        // Numara imaginile pe director-parinte.
        val countsByDir = files
            .groupingBy { it.substringBeforeLast('/', "/") }
            .eachCount()

        // Lista de directoare candidate (chiar goale) pentru clasificare.
        val dirsOutput = countsByDir.keys.joinToString("\n")
        ImageFolderScanner.rankFolders(dirsOutput, countsByDir)
    }

    /** Dimensiunea totala a unui folder in octeti, via `du -sk` (kibi -> octeti). */
    suspend fun folderBytes(dadb: Dadb, path: String): Long = withContext(Dispatchers.IO) {
        val out = dadb.shell("du -sk ${shellQuote(path)} 2>/dev/null").output.trim()
        val kib = out.substringBefore('\t').substringBefore(' ').toLongOrNull() ?: 0L
        kib * 1024L
    }

    /** Spatiul liber pe /sdcard in octeti, via `df` (coloana „Available”, in blocuri de 1K). */
    suspend fun deviceFreeBytes(dadb: Dadb): Long = withContext(Dispatchers.IO) {
        // `df -k /sdcard` -> a doua linie, coloana 4 = disponibil in KiB.
        val lines = dadb.shell("df -k /sdcard 2>/dev/null").allOutput
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        val dataLine = lines.getOrNull(1) ?: return@withContext 0L
        val cols = dataLine.split(Regex("\\s+"))
        val availKib = cols.getOrNull(3)?.toLongOrNull() ?: 0L
        availKib * 1024L
    }
}
