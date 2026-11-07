/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.list

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HouseholdListParserTest {
    private fun item(id: String = "h213e23d0419e211", text: String = "Milk") = JSONObject()
        .put("id", id).put("text", text).put("done", false).put("order", 3.0)
        .put("addedBy", "Rohit’s Pixel").put("addedAt", 1_000L).put("updatedAt", 2_000L)
        .put("doneBy", JSONObject.NULL).put("doneAt", JSONObject.NULL)
        .put("renamedBy", JSONObject.NULL).put("renamedAt", JSONObject.NULL).put("note", JSONObject.NULL)

    private fun body(items: JSONArray = JSONArray().put(item())) = JSONObject().put("ok", true)
        .put("revision", "0123456789ab").put("portal", "Family Area").put("items", items)
        .put("usedBefore", JSONArray().put("Bread").put("Coffee"))
        .put("sync", JSONObject().put("state", "ok").put("peers", JSONArray()
            .put(JSONObject().put("name", "Living Area").put("synced", true))))

    @Test fun parsesAWellFormedState() {
        val snap = HouseholdListParser.snapshot(body())!!
        assertEquals("0123456789ab", snap.revision)
        assertEquals("Family Area", snap.portal)
        assertEquals(listOf("Bread", "Coffee"), snap.usedBefore)
        val milk = snap.items.single()
        assertEquals("Milk", milk.text)
        assertEquals("Rohit’s Pixel", milk.addedBy)
        assertNull(milk.doneAt)
        assertEquals(PortalSyncState.OK, snap.syncState)
        assertEquals(listOf(PeerStatus("Living Area", true)), snap.peers)
    }

    @Test fun rejectsAResponseWithoutAValidRevisionOrItems() {
        assertNull(HouseholdListParser.snapshot(body().put("revision", "NOT HEX")))
        assertNull(HouseholdListParser.snapshot(body().put("items", "nope")))
        assertNull(HouseholdListParser.snapshot(JSONObject().put("ok", true)))
    }

    @Test fun skipsMalformedItemsAndCapsLengths() {
        val items = JSONArray().put(item())
            .put(item(id = "bad id!", text = "Eggs"))
            .put(item(id = "h1", text = ""))
            .put(item(id = "h2", text = "x".repeat(500)))
            .put(item(id = "h3", text = "Tea").put("order", "NaN"))
            .put(item(id = "h4", text = "Jam").put("addedBy", "n".repeat(90)))
            .put(item(id = "h213e23d0419e211", text = "Duplicate id"))
        val snap = HouseholdListParser.snapshot(body(items))!!
        assertEquals(listOf("Milk", "Jam"), snap.items.map { it.text })
        assertEquals(40, snap.items.last().addedBy.length)
    }

    @Test fun parsesNotesOnlyOfKnownKinds() {
        val kept = item().put("note", JSONObject().put("kind", "kept").put("text", "Kept: X put it back.").put("alt", JSONObject.NULL))
        val edited = item(id = "h9", text = "Plantains").put("note", JSONObject().put("kind", "edited")
            .put("text", "Edited in two places.").put("alt", "Bananas 6").put("altBy", "Family Area"))
        val weird = item(id = "h8", text = "Rice").put("note", JSONObject().put("kind", "explode").put("text", "?"))
        val snap = HouseholdListParser.snapshot(body(JSONArray().put(kept).put(edited).put(weird)))!!
        assertEquals(NoteKind.KEPT, snap.items[0].note!!.kind)
        assertEquals("Bananas 6", snap.items[1].note!!.alt)
        assertNull(snap.items[2].note)
    }

    @Test fun unknownSyncStateFallsBackToOk() {
        val snap = HouseholdListParser.snapshot(body().put("sync", JSONObject().put("state", "wat")))!!
        assertEquals(PortalSyncState.OK, snap.syncState)
        assertTrue(snap.peers.isEmpty())
    }

    @Test fun parsesResultsAndStale() {
        val json = body().put("stale", true).put("results", JSONArray()
            .put(JSONObject().put("aid", "a1").put("status", "applied").put("id", "h1"))
            .put(JSONObject().put("aid", "a2").put("status", "duplicate").put("id", JSONObject.NULL))
            .put(JSONObject().put("aid", "a3").put("status", "boom")))
        val results = HouseholdListParser.results(json)
        assertEquals(setOf("a1", "a2", "a3"), results.keys)
        assertEquals(ActionStatus.DUPLICATE, results["a2"])
        assertEquals(ActionStatus.INVALID, results["a3"])
        assertTrue(HouseholdListParser.stale(json))
        assertFalse(HouseholdListParser.stale(body()))
    }
}
