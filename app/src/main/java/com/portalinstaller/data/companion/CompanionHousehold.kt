package com.portalinstaller.data.companion

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The household key: one secret this phone generates and provisions to every paired Portal over
 * the authenticated companion channel (`/remote/household`), so Portals can sign messages to
 * each other without ever holding each other's pairing tokens. Contract with the Portal app's
 * HouseholdKey / HouseholdMessage (identical fingerprint algorithm, pinned by known-answer tests).
 */
interface HouseholdKeySource {
    /** The household key, generated on first use and then stable. */
    fun key(): ByteArray
}

/** App-private storage; the key never leaves the phone except to paired Portals. */
class PrefsHouseholdKeySource(context: Context) : HouseholdKeySource {
    private val prefs = context.applicationContext.getSharedPreferences("household", Context.MODE_PRIVATE)

    @Synchronized override fun key(): ByteArray {
        prefs.getString(KEY, null)?.let(CompanionHousehold::fromHex)?.takeIf { it.size == SIZE }?.let { return it }
        val fresh = ByteArray(SIZE).also(SecureRandom()::nextBytes)
        prefs.edit().putString(KEY, CompanionHousehold.hex(fresh)).commit()
        return fresh
    }

    private companion object {
        const val KEY = "k"
        const val SIZE = 32
    }
}

object CompanionHousehold {
    const val ROUTE = "household"

    fun fingerprint(key: ByteArray): String =
        hex(MessageDigest.getInstance("SHA-256").digest("immortal-household-fp".toByteArray() + key)).take(8)

    /** True when the Portal's read-back shows no key or a different one. */
    fun needsProvision(remote: JSONObject, key: ByteArray): Boolean =
        !remote.optBoolean("configured") || remote.optString("fingerprint") != fingerprint(key)

    /** Ensures [session]'s Portal holds our household key. Best effort; returns true when it does. */
    suspend fun provision(client: CompanionClient, session: CompanionSession, key: ByteArray): Boolean {
        val remote = client.get(session, ROUTE)
        if (!needsProvision(remote, key)) return true
        val result = client.post(session, ROUTE, JSONObject().put("key", hex(key)))
        return result.optString("fingerprint") == fingerprint(key)
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun fromHex(text: String): ByteArray? {
        if (text.length % 2 != 0 || text.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        return ByteArray(text.length / 2) { i -> text.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
