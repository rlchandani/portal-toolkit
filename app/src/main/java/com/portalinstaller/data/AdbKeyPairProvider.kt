package com.portalinstaller.data

import android.content.Context
import dadb.AdbKeyPair
import java.io.File

object AdbKeyPairProvider {
    fun getOrCreate(context: Context): AdbKeyPair {
        val privateKeyFile = File(context.filesDir, "adbkey")
        val publicKeyFile = File(context.filesDir, "adbkey.pub")
        if (!privateKeyFile.exists() || !publicKeyFile.exists()) {
            AdbKeyPair.generate(privateKeyFile, publicKeyFile)
        }
        return AdbKeyPair.read(privateKeyFile, publicKeyFile)
    }
}
