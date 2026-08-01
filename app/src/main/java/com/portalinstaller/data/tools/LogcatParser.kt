package com.portalinstaller.data.tools

/**
 * Parseaza liniile din `logcat -v brief`, de forma `E/Tag( 1234): mesaj`.
 * Liniile care nu se potrivesc (continuari de stack trace, linii goale) devin
 * [LogLine] cu nivel '?' si tag gol, pastrand mesajul curatat — astfel un
 * stack trace ramane lizibil in loc sa fie aruncat.
 */
object LogcatParser {

    private val BRIEF = Regex("""^([VDIWEF])/(.+?)\(\s*\d+\):\s?(.*)$""")

    fun parseLine(raw: String): LogLine {
        val match = BRIEF.matchEntire(raw)
        return if (match != null) {
            val (level, tag, message) = match.destructured
            LogLine(level = level[0], tag = tag, message = message, raw = raw)
        } else {
            LogLine(level = '?', tag = "", message = raw.trim(), raw = raw)
        }
    }
}
