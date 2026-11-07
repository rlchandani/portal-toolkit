package com.portalinstaller.data.companion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone provisions one household key to every paired Portal (contract with the Portal app). */
class CompanionHouseholdTest {
    private val key = ByteArray(32) { it.toByte() }

    @Test fun fingerprintMatchesThePortalAlgorithm() {
        // Same input as the Portal's HouseholdKeyTest-equivalent: sha256("immortal-household-fp" + key), 8 hex chars.
        val fp = CompanionHousehold.fingerprint(key)
        assertEquals(8, fp.length)
        assertEquals("9b237533", fp) // known answer, identical on the Portal (HouseholdMessageTest)
        assertEquals(fp, CompanionHousehold.fingerprint(key.copyOf()))
        assertTrue(fp.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test fun provisionsOnlyWhenThePortalLacksOrDiffersFromOurKey() {
        val ours = CompanionHousehold.fingerprint(key)
        assertTrue(CompanionHousehold.needsProvision(JSONObject().put("ok", true).put("configured", false), key))
        assertTrue(CompanionHousehold.needsProvision(JSONObject().put("ok", true).put("configured", true).put("fingerprint", "deadbeef"), key))
        assertFalse(CompanionHousehold.needsProvision(JSONObject().put("ok", true).put("configured", true).put("fingerprint", ours), key))
    }

    @Test fun keyHexIsLowercaseAndLossless() {
        val hex = CompanionHousehold.hex(key)
        assertEquals(64, hex.length)
        assertEquals("000102030405", hex.take(12))
    }
}
