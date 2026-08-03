package com.portalinstaller.data.adb

/**
 * Escapare POSIX pentru un singur argument de shell. Inchide valoarea in
 * ghilimele simple si trateaza ghilimeaua simpla interioara prin secventa
 * clasica '\'' (inchide, escapeaza literal, redeschide). Este singura aparare
 * impotriva injectiei de comenzi si a cailor cu spatii, asa ca exista o
 * singura implementare canonica folosita de toti managerii care ating shell-ul.
 */
internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
