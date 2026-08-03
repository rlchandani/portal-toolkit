package com.portalinstaller.data

import android.content.Context
import dadb.AdbKeyPair
import java.io.File

object AdbKeyPairProvider {
    // Memoizat + sincronizat: connect() si connectUsb() pot cere cheia concurent
    // cu o pasa de discovery. Fara asta, doua ramuri „lipsa fisier” ar putea
    // genera/scrie aceleasi fisiere simultan -> cheie corupta. Generam exact o
    // data per proces.
    @Volatile
    private var cached: AdbKeyPair? = null

    fun getOrCreate(context: Context): AdbKeyPair {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: run {
                val privateKeyFile = File(context.filesDir, "adbkey")
                val publicKeyFile = File(context.filesDir, "adbkey.pub")
                if (!privateKeyFile.exists() || !publicKeyFile.exists()) {
                    AdbKeyPair.generate(privateKeyFile, publicKeyFile)
                }
                AdbKeyPair.read(privateKeyFile, publicKeyFile).also { cached = it }
            }
        }
    }
}
