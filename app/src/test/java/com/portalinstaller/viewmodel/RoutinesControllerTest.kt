/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.viewmodel

import com.portalinstaller.data.companion.CompanionError
import com.portalinstaller.data.companion.CompanionException
import com.portalinstaller.data.routines.RoutineDraft
import com.portalinstaller.data.routines.RoutineTemplates
import com.portalinstaller.data.routines.RoutineWire
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class RoutinesControllerTest {
    private val morning = RoutineTemplates.create("morning", sequenceOf("r1", "c0", "c1", "c2", "c3", "c4").iterator()::next)

    private fun snap(rev: Long, vararg routines: com.portalinstaller.data.routines.Routine) = JSONObject().put("ok", true).put("rev", rev)
        .put("routines", JSONArray().apply { routines.forEach { put(RoutineWire.routineJson(it)) } })
        .put("kids", JSONArray()).put("today", JSONArray()).put("day", 20766).put("minute", 420)

    @Test fun saveSendsExpectedRevisionAndCountsOnlyAfterTheAck() = runTest {
        val sent = mutableListOf<JSONObject>()
        val ack = CompletableDeferred<JSONObject?>()
        val c = RoutinesController(backgroundScope, read = { snap(3) }, send = { _, body -> sent += body; ack.await() }, room = { "Family Area" })
        c.updateSession("a:1", true)
        c.refresh(); runCurrent()
        var saved: Boolean? = null
        c.save(RoutineDraft.new(morning)) { saved = it }
        runCurrent()
        assertEquals("upsert", sent.single().getString("action"))
        assertEquals(3L, sent.single().getLong("expectedRev"))
        assertTrue(c.state.value.sending)
        assertNull(saved)
        ack.complete(snap(4, morning)); runCurrent()
        assertEquals(true, saved)
        assertEquals(4L, c.state.value.snapshot?.rev)
        assertEquals("Saved. Starts on Family Area today at 7:15 AM.", c.state.value.snack?.text)
        assertFalse(c.state.value.sending)
    }

    @Test fun noAnswerKeepsTheDraftAndExplains() = runTest {
        val c = RoutinesController(backgroundScope, read = { snap(3) }, send = { _, _ -> CompletableDeferred<JSONObject?>().await() }, room = { "Family Area" })
        c.updateSession("a:1", true); c.refresh(); runCurrent()
        var saved: Boolean? = null
        c.save(RoutineDraft.new(morning)) { saved = it }
        advanceTimeBy(RoutinesController.ACK_TIMEOUT_MS + 1); runCurrent()
        assertEquals(false, saved)
        assertEquals("Not sent to Family Area", c.state.value.failure?.title)
        assertTrue(c.state.value.failure!!.body.endsWith("Your changes are still here."))
    }

    @Test fun storageFullNamesTheFix() = runTest {
        val c = RoutinesController(backgroundScope, read = { snap(3) },
            send = { _, _ -> throw CompanionException(CompanionError("storage_full", "x", 507)) }, room = { "Family Area" })
        c.updateSession("a:1", true); c.refresh(); runCurrent()
        c.save(RoutineDraft.new(morning)) {}; runCurrent()
        assertEquals("Family Area couldn’t save it", c.state.value.failure?.title)
        assertEquals("Its storage is full. Free up space in Portal Settings › Storage, then try again.", c.state.value.failure?.body)
    }

    @Test fun staleRevisionReloadsAndAsksToReview() = runTest {
        var rev = 3L
        val c = RoutinesController(backgroundScope, read = { snap(rev) },
            send = { _, _ -> rev = 5; throw CompanionException(CompanionError("stale_revision", "x", 409)) }, room = { "Family Area" })
        c.updateSession("a:1", true); c.refresh(); runCurrent()
        c.save(RoutineDraft.new(morning)) {}; runCurrent()
        assertEquals(5L, c.state.value.snapshot?.rev)
        assertEquals("Changed on Family Area", c.state.value.failure?.title)
    }

    @Test fun offlinePhoneIsReportedAsSuch() = runTest {
        val c = RoutinesController(backgroundScope, read = { snap(3) }, send = { _, _ -> throw IOException("no route") }, room = { "Family Area" })
        c.updateSession("a:1", false)
        c.save(RoutineDraft.new(morning)) {}; runCurrent()
        assertEquals("Your phone is offline", c.state.value.failure?.title)
    }

    @Test fun previewBusyAndCloseFailureCopy() = runTest {
        val c = RoutinesController(backgroundScope, read = { snap(3, morning) }, send = { _, body ->
            when (body.getString("action")) {
                "preview" -> throw CompanionException(CompanionError("busy", "x", 409))
                else -> throw IOException("down")
            }
        }, room = { "Family Area" })
        c.updateSession("a:1", true); c.refresh(); runCurrent()
        c.preview(morning); runCurrent()
        assertEquals("Family Area is running a routine. Try again after it ends.", c.state.value.snack?.text)
        c.close("r1"); advanceTimeBy(RoutinesController.ACK_TIMEOUT_MS + 1); runCurrent()
        assertEquals("Not sent to Family Area. Close it on the Portal: press and hold the close button.", c.state.value.snack?.text)
    }

    @Test fun deleteOffersUndoThatRestores() = runTest {
        val sent = mutableListOf<JSONObject>()
        var current = snap(3, morning)
        val c = RoutinesController(backgroundScope, read = { current }, send = { _, body ->
            sent += body
            current = if (body.getString("action") == "delete") snap(4) else snap(5, morning); current
        }, room = { "Family Area" })
        c.updateSession("a:1", true); c.refresh(); runCurrent()
        c.delete("r1") {}; runCurrent()
        assertEquals("Deleted School morning", c.state.value.snack?.text)
        assertNotNull(c.state.value.snack?.undo)
        c.undo(); runCurrent()
        assertEquals("upsert", sent.last().getString("action"))
        assertEquals(4L, sent.last().getLong("expectedRev"))
        assertEquals("Restored School morning.", c.state.value.snack?.text)
    }

    @Test fun anOldSessionCannotPublish() = runTest {
        val slow = CompletableDeferred<JSONObject?>()
        val c = RoutinesController(backgroundScope, read = { key -> if (key == "a:1") slow.await() else snap(9) }, send = { _, _ -> null }, room = { "X" })
        c.updateSession("a:1", true); c.refresh(); runCurrent()
        c.updateSession("b:2", true); c.refresh(); runCurrent()
        slow.complete(snap(1)); runCurrent()
        assertEquals(9L, c.state.value.snapshot?.rev)
    }
}
