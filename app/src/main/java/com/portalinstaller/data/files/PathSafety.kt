package com.portalinstaller.data.files

/** Garda de siguranta: operatiile in afara /sdcard cer "mod avansat" (implicit oprit). */
object PathSafety {
    const val SDCARD_ROOT = "/sdcard"

    fun isWithinSdcard(path: String): Boolean {
        val normalized = path.trimEnd('/')
        return normalized == SDCARD_ROOT || normalized.startsWith("$SDCARD_ROOT/")
    }

    fun requireAllowed(path: String, advancedMode: Boolean) {
        if (!advancedMode && !isWithinSdcard(path)) {
            throw SecurityException("Operatie interzisa in afara /sdcard fara mod avansat: $path")
        }
    }
}
