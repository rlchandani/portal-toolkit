package com.portalinstaller.data.tools

/**
 * O linie de logcat parsata. [level] este litera nivelului (V/D/I/W/E/F) sau
 * '?' pentru liniile care nu respecta formatul „brief” (ex. continuari de
 * stack trace). [raw] pastreaza linia originala, utila la export.
 */
data class LogLine(
    val level: Char,
    val tag: String,
    val message: String,
    val raw: String
)
