/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.companion

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompanionModelsTest {
    @Test fun normalizesLanVpnHttpsAndQrEndpoints() {
        assertEquals("http://192.168.1.8:8723", CompanionEndpoint.normalize(" 192.168.1.8 "))
        assertEquals("http://portal.local:9123", CompanionEndpoint.normalize("http://portal.local:9123/remote/ui"))
        assertEquals("https://portal.example/vpn", CompanionEndpoint.normalize("https://portal.example/vpn/remote/ui?pin=123456"))
        assertEquals("http://[fd00::8]:8723", CompanionEndpoint.normalize("[fd00::8]"))
        val code = PairingCode.parse("https://portal.example/vpn/remote/ui#pin=123456")
        assertEquals("https://portal.example/vpn", code.endpoint)
        assertEquals("123456", code.pin)
        val json = PairingCode.parse("""{"endpoint":"portal.local:8787","pin":"654321","name":"Kitchen"}""")
        assertEquals("Kitchen", json.name)
        assertEquals("654321", json.pin)
    }

    @Test fun rejectsUnsafeEndpointComponents() {
        for (input in listOf("ftp://portal", "http://user:secret@portal", "https://", "portal:0", "portal:70000", "http://portal/a/../b")) {
            assertTrue(input, runCatching { CompanionEndpoint.normalize(input) }.isFailure)
        }
    }

    @Test fun preservesUnknownAdvancedConditionalAndTypedSettings() {
        val parsed = CompanionParser.settings(JSONObject("""{"settings":{"future":17,"domains":[{"id":"screensaver","title":"Photos","futureDomain":true,"controls":[{"key":"shuffle","type":"bool","title":"Shuffle","value":true,"tier":"advanced","visible":{"source":"local"},"future":{"a":2}},{"key":"intervalSec","type":"int","value":45,"min":10,"max":90,"step":5},{"key":"style","type":"enum","value":"new","options":[{"value":"new","tier":"advanced"}]},{"key":"alien","type":"future","value":{"x":1}}]}]}}"""))
        val domain = parsed.single()
        assertTrue(domain.raw.getBoolean("futureDomain"))
        val shuffle = domain.controls[0]
        assertEquals(true, shuffle.value)
        assertTrue(shuffle.advanced)
        assertEquals("local", shuffle.raw.getJSONObject("visible").getString("source"))
        assertEquals(2, shuffle.raw.getJSONObject("future").getInt("a"))
        assertEquals(45, domain.controls[1].value)
        assertEquals("new", domain.controls[2].value)
        assertEquals("future", domain.controls[3].type)
        assertTrue(domain.controls[3].value is JSONObject)
    }

    @Test fun sessionDiagnosticsNeverIncludeToken() {
        val saved = CompanionSession(CompanionDevice("stable", "Kitchen", "http://portal:8787"), "private-session-value")
        assertFalse(saved.toString().contains("private-session-value"))
        assertFalse(CompanionRequest("POST", "http://portal:8787/remote/pair", "private-session-value", "{\"pin\":\"123456\"}").toString().contains("123456"))
    }
}
