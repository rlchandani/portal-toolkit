package com.portalinstaller.data.tools

import com.portalinstaller.data.adb.shellQuote

/**
 * Taste hardware ale Portal-ului si codurile lor KeyEvent. POWER/VOLUME_UP/
 * VOLUME_DOWN sunt definite intentionat dar nu sunt expuse in ControlBar:
 * POWER poate stinge ecranul Portal-ului in timpul oglindirii.
 */
enum class PortalKey(val code: Int) {
    BACK(4),
    HOME(3),
    RECENTS(187),
    POWER(26),
    VOLUME_UP(24),
    VOLUME_DOWN(25)
}

/** Aruncata cand screenrecord nu porneste (suprafata securizata / DRM / neacceptat). */
class MirrorUnsupportedException(message: String) : Exception(message)

/**
 * Logica pura pentru controlul Portal-ului: mapeaza coordonate din spatiul
 * view in spatiul dispozitivului (tinand cont de benzile de letterbox de la
 * ContentScale.Fit) si construieste comenzile `input ...`. Fara ADB, fara
 * Android — complet testabila.
 */
object InputInjector {

    /**
     * Mapeaza un punct din view (px) in coordonate de dispozitiv (px). Suprafata
     * foloseste Fit, deci exista benzi negre pe o axa; cartografiem doar in
     * dreptunghiul de continut si taiem (clamp) la marginile dispozitivului.
     */
    fun mapPoint(
        viewX: Float,
        viewY: Float,
        viewW: Int,
        viewH: Int,
        devW: Int,
        devH: Int
    ): Pair<Int, Int> {
        if (viewW <= 0 || viewH <= 0 || devW <= 0 || devH <= 0) return 0 to 0
        // Scala Fit: cel mai mic factor care incape continutul in view.
        val scale = minOf(viewW.toFloat() / devW, viewH.toFloat() / devH)
        val contentW = devW * scale
        val contentH = devH * scale
        val barX = (viewW - contentW) / 2f
        val barY = (viewH - contentH) / 2f
        val devX = ((viewX - barX) / scale).toInt().coerceIn(0, devW - 1)
        val devY = ((viewY - barY) / scale).toInt().coerceIn(0, devH - 1)
        return devX to devY
    }

    fun tapCommand(devX: Int, devY: Int): String = "input tap $devX $devY"

    fun swipeCommand(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): String =
        "input swipe $x1 $y1 $x2 $y2 $durationMs"

    fun longPressCommand(devX: Int, devY: Int, durationMs: Int = 600): String =
        swipeCommand(devX, devY, devX, devY, durationMs)

    fun keyeventCommand(key: PortalKey): String = "input keyevent ${key.code}"

    /**
     * `input text` trateaza spatiul special: il inlocuim cu %s, apoi citam
     * intregul argument cu shellQuote ca sa fie sigur in shell.
     */
    fun textCommand(raw: String): String {
        val withSpaces = raw.replace(" ", "%s")
        return "input text ${shellQuote(withSpaces)}"
    }
}
