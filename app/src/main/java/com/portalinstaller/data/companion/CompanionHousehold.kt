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
    /** The key if this phone already has one, without generating it. */
    fun peek(): ByteArray? = key()
    /** True once a Portal has confirmed this phone's key (adopted from, or provisioned to, a Portal). */
    fun confirmed(): Boolean = true
    /** Replaces this phone's key with the household's existing one and marks it confirmed. */
    fun adopt(key: ByteArray) {}
    fun markConfirmed() {}
}

/** What to do with one Portal's key ([CompanionHousehold.plan]). */
enum class HouseholdPlan { IN_SYNC, PUSH, ADOPT }

/** App-private storage; the key never leaves the phone except to paired Portals. */
class PrefsHouseholdKeySource(context: Context) : HouseholdKeySource {
    private val prefs = context.applicationContext.getSharedPreferences("household", Context.MODE_PRIVATE)

    @Synchronized override fun key(): ByteArray {
        peek()?.let { return it }
        val fresh = ByteArray(SIZE).also(SecureRandom()::nextBytes)
        prefs.edit().putString(KEY, CompanionHousehold.hex(fresh)).commit()
        return fresh
    }

    @Synchronized override fun peek(): ByteArray? =
        prefs.getString(KEY, null)?.let(CompanionHousehold::fromHex)?.takeIf { it.size == SIZE }

    override fun confirmed(): Boolean = prefs.getBoolean(CONFIRMED, false)

    @Synchronized override fun adopt(key: ByteArray) {
        require(key.size == SIZE) { "household key must be $SIZE bytes" }
        prefs.edit().putString(KEY, CompanionHousehold.hex(key)).putBoolean(CONFIRMED, true).commit()
    }

    override fun markConfirmed() { prefs.edit().putBoolean(CONFIRMED, true).apply() }

    private companion object {
        const val KEY = "k"
        const val CONFIRMED = "confirmed"
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

    /**
     * A phone that has never had its key confirmed by a Portal adopts the key the household already
     * uses (so a second phone can't lock the first one out); a confirmed phone provisions its key to
     * Portals that lack it or differ, as before.
     */
    fun plan(remote: JSONObject, local: ByteArray?, confirmed: Boolean): HouseholdPlan {
        val remoteFp = remote.optString("fingerprint").takeIf { remote.optBoolean("configured") && it.isNotBlank() }
        return when {
            local != null && remoteFp == fingerprint(local) -> HouseholdPlan.IN_SYNC
            remoteFp != null && !confirmed -> HouseholdPlan.ADOPT
            else -> HouseholdPlan.PUSH
        }
    }

    /** The key in a `{share: true}` reply, only if it really is the key the Portal advertised. */
    fun adoptedKey(reply: JSONObject, expectedFingerprint: String): ByteArray? =
        fromHex(reply.optString("key"))?.takeIf { it.size == KEY_SIZE && fingerprint(it) == expectedFingerprint }

    /** Brings [session]'s Portal and this phone onto one household key. Returns true when they match. */
    suspend fun provision(client: CompanionClient, session: CompanionSession, source: HouseholdKeySource): Boolean {
        val remote = client.get(session, ROUTE)
        return when (plan(remote, source.peek(), source.confirmed())) {
            HouseholdPlan.IN_SYNC -> true.also { source.markConfirmed() }
            HouseholdPlan.ADOPT -> {
                val reply = client.post(session, ROUTE, JSONObject().put("share", true))
                // An older Portal can't share: don't overwrite the household's key; retry after it updates.
                adoptedKey(reply, remote.optString("fingerprint"))?.let { source.adopt(it); true } ?: false
            }
            HouseholdPlan.PUSH -> {
                val key = source.key()
                val result = client.post(session, ROUTE, JSONObject().put("key", hex(key)))
                (result.optString("fingerprint") == fingerprint(key)).also { if (it) source.markConfirmed() }
            }
        }
    }

    private const val KEY_SIZE = 32

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun fromHex(text: String): ByteArray? {
        if (text.length % 2 != 0 || text.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        return ByteArray(text.length / 2) { i -> text.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
