package com.portalinstaller.data.adb

import dadb.AdbKeyPair
import dadb.AdbStream
import dadb.Dadb
import okio.Sink
import okio.Source
import java.io.Closeable
import java.io.IOException
import java.lang.reflect.Method
import java.net.Socket

/**
 * Punte unică între aplicație și API-urile interne ale dadb.
 *
 * `dadb.AdbConnection` este o clasă Kotlin `internal`, deci nu poate fi numită
 * din codul nostru — de aceea conexiunile se creează prin reflexie și se
 * împachetează într-un [Dadb] tipat. Toate atingerile de API intern trăiesc
 * aici (înainte erau duplicate în PortalDiscoveryManager și UsbAdbBridge) și
 * sunt blindate de [DadbInternalsCanaryTest], care pică la build dacă un bump
 * de dadb schimbă vreo semnătură — înlocuiește vechile comentarii
 * „re-test after every dadb version bump”.
 */
object DadbConnections {

    /** Conectare TCP prin `AdbConnection.Companion.connect(Socket, AdbKeyPair)` (public în dadb). */
    fun connectTcp(socket: Socket, keyPair: AdbKeyPair): Dadb {
        val connection = DadbInternals.connectSocket(socket, keyPair)
        return ConnectionDadb(connection)
    }

    /** Conectare peste stream-uri arbitrare (USB bulk endpoints) prin overload-ul intern Source/Sink. */
    fun connectStreams(source: Source, sink: Sink, keyPair: AdbKeyPair, closeable: Closeable): Dadb {
        val connection = DadbInternals.connectStreams(source, sink, keyPair, closeable)
        return ConnectionDadb(connection)
    }
}

/**
 * Adaptor de la instanța `dadb.AdbConnection` (ținută ca [Any], fiind internal)
 * la interfața publică [Dadb]. shell/push/pull provin din default-urile [Dadb].
 */
internal class ConnectionDadb(private val connection: Any) : Dadb {
    private val openMethod: Method = connection.javaClass.getMethod("open", String::class.java)
    private val supportsFeatureMethod: Method =
        connection.javaClass.getMethod("supportsFeature", String::class.java)
    private val closeMethod: Method = connection.javaClass.getMethod("close")

    override fun open(destination: String): AdbStream =
        openMethod.invoke(connection, destination) as AdbStream

    override fun supportsFeature(feature: String): Boolean =
        supportsFeatureMethod.invoke(connection, feature) as Boolean

    override fun close() {
        closeMethod.invoke(connection)
    }
}

/**
 * Singurul loc care atinge API-uri interne dadb. Menținut minimal; fiecare
 * metodă rezolvată aici are un test canar corespunzător.
 */
internal object DadbInternals {

    private val adbConnectionClass: Class<*> by lazy { Class.forName("dadb.AdbConnection") }
    private val companion: Any by lazy {
        // `Companion` este un câmp static non-null în dadb; dacă lipsește (bump de
        // versiune care schimbă structura), preferăm o eroare clară în locul unui
        // NPE opac mai târziu la primul apel de conexiune.
        adbConnectionClass.getField("Companion").get(null)
            ?: error("dadb.AdbConnection.Companion nu a putut fi rezolvat prin reflexie.")
    }

    fun connectSocket(socket: Socket, keyPair: AdbKeyPair): Any {
        return resolveConnectSocketMethod().invoke(companion, socket, keyPair)
            ?: throw IOException("Nu s-a putut crea conexiunea ADB peste TCP.")
    }

    fun connectStreams(source: Source, sink: Sink, keyPair: AdbKeyPair, closeable: Closeable): Any {
        return resolveConnectStreamsMethod().invoke(null, companion, source, sink, keyPair, closeable, 0, null)
            ?: throw IOException("Nu s-a putut crea conexiunea ADB peste stream-uri.")
    }

    /** `AdbConnection.Companion.connect(Socket, AdbKeyPair)` — public în dadb 1.2.6. */
    fun resolveConnectSocketMethod(): Method =
        companion.javaClass.getMethod("connect", Socket::class.java, AdbKeyPair::class.java)

    /** `AdbConnection.Companion.connect$default(Source, Sink, AdbKeyPair, Closeable, int, Object)` — internal. */
    fun resolveConnectStreamsMethod(): Method =
        companion.javaClass.getDeclaredMethod(
            "connect\$default",
            companion.javaClass,
            Source::class.java,
            Sink::class.java,
            AdbKeyPair::class.java,
            Closeable::class.java,
            Int::class.javaPrimitiveType,
            Any::class.java
        ).apply { isAccessible = true }
}
