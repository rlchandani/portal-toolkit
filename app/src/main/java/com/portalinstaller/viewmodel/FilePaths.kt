package com.portalinstaller.viewmodel

/** Utilitare pure pentru navigarea cailor remote. */
object FilePaths {
    /** Calea parinte. Nu urca peste /sdcard (ramane la /sdcard). Radacina ramane "/". */
    fun parentOf(path: String): String {
        val trimmed = path.trimEnd('/')
        if (trimmed.isEmpty() || trimmed == "/") return "/"
        if (trimmed == "/sdcard") return "/sdcard"
        val parent = trimmed.substringBeforeLast('/', missingDelimiterValue = "/")
        return parent.ifEmpty { "/" }
    }
}
