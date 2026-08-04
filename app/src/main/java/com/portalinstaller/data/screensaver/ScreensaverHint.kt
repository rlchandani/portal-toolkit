package com.portalinstaller.data.screensaver

/** Numarul recomandat de poze unice pentru un slideshow variat pe Portal. */
const val RECOMMENDED_MIN = 20

/** Nivelul indiciului afisat pe tab-ul de screensaver. */
sealed class HintLevel {
    /** Fara indiciu (0 poze - starea goala acopera deja cazul). */
    data object None : HintLevel()

    /** Prea putine poze: sugereaza adaugarea a [needed] poze in plus. */
    data class AddMore(val needed: Int) : HintLevel()

    /** Suficiente poze: fara mesaj de tip "adauga mai multe". */
    data object Ok : HintLevel()
}

/**
 * Firmware-ul Portal repeta seturile mici de poze; singura parghie reala este
 * un set mai mare de poze unice. Aceasta functie pura decide ce indiciu aratam
 * in functie de numarul de poze existente.
 */
fun screensaverHint(count: Int, recommendedMin: Int = RECOMMENDED_MIN): HintLevel = when {
    count <= 0 -> HintLevel.None
    count < recommendedMin -> HintLevel.AddMore(recommendedMin - count)
    else -> HintLevel.Ok
}
