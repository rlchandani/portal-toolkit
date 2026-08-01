package com.portalinstaller.data

import dadb.Dadb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Aruncată de [ConnectionHolder.require] când nu există o conexiune activă. */
class NotConnectedException : IllegalStateException("Nu există o conexiune ADB activă.")

/**
 * Sursa unică de adevăr pentru conexiunea ADB curentă.
 *
 * Înainte, fiecare copie a `dadb` trăia în PortalViewModel; acum toate
 * ViewModel-urile de funcționalitate împart acest holder (via AppContainer),
 * astfel încât Apps/Backup/Photos văd aceeași conexiune stabilită de Connection.
 * Închiderea conexiunii vechi la înlocuire se face pe IO, în afara firului UI.
 */
class ConnectionHolder {

    private val _dadb = MutableStateFlow<Dadb?>(null)
    val dadb: StateFlow<Dadb?> = _dadb.asStateFlow()

    private val _deviceLabel = MutableStateFlow<String?>(null)
    val deviceLabel: StateFlow<String?> = _deviceLabel.asStateFlow()

    /** Instalează o nouă conexiune, închizând în fundal pe cea anterioară (dacă diferă). */
    fun set(dadb: Dadb?, label: String?) {
        val previous = _dadb.value
        _dadb.value = dadb
        _deviceLabel.value = label
        if (previous != null && previous !== dadb) {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { previous.close() }
            }
        }
    }

    /** Închide și golește conexiunea curentă. */
    fun clear() = set(null, null)

    /** Conexiunea curentă sau [NotConnectedException] dacă lipsește. */
    fun require(): Dadb = _dadb.value ?: throw NotConnectedException()
}
