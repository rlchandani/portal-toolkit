package com.portalinstaller.data.tools

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
        dadb.shell("screencap -p $REMOTE_PATH")
        val localFile = File(workDir, "pt_peek.png")
        dadb.pull(localFile, REMOTE_PATH)
        localFile
    }
}
