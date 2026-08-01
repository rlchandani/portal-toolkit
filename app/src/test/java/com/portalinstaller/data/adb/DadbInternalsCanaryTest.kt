package com.portalinstaller.data.adb

import okio.Sink
import okio.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.lang.reflect.Modifier
import java.net.Socket

/**
 * Blindează cele două puncte de contact cu dadb pe care se bazează aplicația:
 *
 *  1. TCP: `AdbConnection.Companion.connect(Socket, AdbKeyPair)` trebuie să rămână PUBLIC.
 *  2. USB: `connect$default(Source, Sink, AdbKeyPair, Closeable, int, Object)` este intern
 *     și îl invocăm reflexiv — acest test verifică faptul că semnătura încă există.
 *
 * `dadb.AdbConnection` este Kotlin `internal`, deci nu poate fi numit direct din
 * codul nostru; îl rezolvăm prin `Class.forName`, la fel ca [DadbInternals].
 * Dacă un bump de dadb schimbă oricare semnătură, acest test pică la build,
 * nu în producție la runtime — înlocuiește vechiul comentariu
 * „re-test after every dadb version bump”.
 */
class DadbInternalsCanaryTest {

    private val adbConnectionClass: Class<*> = Class.forName("dadb.AdbConnection")
    private val adbKeyPairClass: Class<*> = Class.forName("dadb.AdbKeyPair")
    private val companionClass: Class<*> = adbConnectionClass.getField("Companion").type

    @Test
    fun `public TCP connect(Socket, AdbKeyPair) still exists and is public`() {
        val method = companionClass.getMethod("connect", Socket::class.java, adbKeyPairClass)
        assertEquals(adbConnectionClass, method.returnType)
        assertTrue(
            "connect(Socket, AdbKeyPair) trebuie să rămână public",
            Modifier.isPublic(method.modifiers)
        )
    }

    @Test
    fun `internal streams connect default still resolves`() {
        val method = DadbInternals.resolveConnectStreamsMethod()
        assertNotNull(method)
        assertEquals(adbConnectionClass, method.returnType)
        // Ordinea parametrilor pe care se bazează DadbInternals.connectStreams.
        val params = method.parameterTypes
        assertEquals(7, params.size)
        assertEquals(Source::class.java, params[1])
        assertEquals(Sink::class.java, params[2])
        assertEquals(adbKeyPairClass, params[3])
        assertEquals(Closeable::class.java, params[4])
    }
}
