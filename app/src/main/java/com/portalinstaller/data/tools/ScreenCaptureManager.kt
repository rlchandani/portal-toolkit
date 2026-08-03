package com.portalinstaller.data.tools

import com.portalinstaller.data.adb.shellQuote
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Face o captura de ecran pe Portal cu `screencap -p`, o trage local prin ADB
 * pull si intoarce fisierul PNG. Toata munca de I/O ruleaza pe [Dispatchers.IO].
 */
object ScreenCaptureManager {

    private const val REMOTE_PATH = "/sdcard/.pt_peek.png"

    /**
     * @param workDir directorul local (ex. cacheDir) unde se salveaza PNG-ul tras.
     * @return fisierul PNG local.
     */
    suspend fun capture(dadb: Dadb, workDir: File): File = withContext(Dispatchers.IO) {
        try {
            // Verificam exit code: la o suprafata securizata/DRM screencap esueaza
            // si NU rescrie fisierul, deci un pull „reusit” ar intoarce captura
            // veche fara sa semnaleze eroarea. require() opreste asta.
            val resp = dadb.shell("screencap -p $REMOTE_PATH")
            require(resp.exitCode == 0) { resp.allOutput.ifBlank { "screencap a esuat" } }
            val localFile = File(workDir, "pt_peek.png")
            dadb.pull(localFile, REMOTE_PATH)
            localFile
        } finally {
            // Nu lasam fisierul temporar pe Portal indiferent de rezultat.
            runCatching { dadb.shell("rm -f ${shellQuote(REMOTE_PATH)}") }
        }
    }
}
