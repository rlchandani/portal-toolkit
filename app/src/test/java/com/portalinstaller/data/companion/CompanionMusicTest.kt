/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompanionMusicTest {
    @Test fun responseRequiresAnActualMusicObject() {
        for (response in listOf(
            JSONObject(), JSONObject().put("music", JSONObject.NULL),
            JSONObject().put("music", "ready"), JSONObject().put("music", JSONArray()),
            state(),
        )) {
            rejects { CompanionMusic.fromResponse(response) }
        }
        assertEquals(42L, CompanionMusic.fromResponse(JSONObject().put("music", state())).sourceRevision)
    }

    @Test fun completeWireSnapshotPreservesMetadataAndNullableValues() {
        val music = parse()
        assertEquals(1, music.schemaVersion)
        assertEquals("ma:leader", music.sourceId)
        assertEquals("ready", music.connection)
        assertEquals("playing", music.playback)
        assertFalse(music.audioOwnedByOther)
        assertEquals("ma:leader", music.controlPlayerId)
        assertEquals("queue-a", music.queueId)
        assertEquals(CompanionMusic.MusicTrack("track-a", "Night Train", "Quartet", "Live",
            180_000L, false), music.track)
        assertEquals(42_500L, music.positionMs)
        assertEquals(CompanionMusic.MusicQueueItem("item-a", "Next Track", "Band",
            null, true, true), music.queue.single())
        assertEquals(CompanionMusic.MusicRoom("ma:join", "Kitchen", true, null, null,
            null, false, true, true, false, true), music.rooms[1])
        assertEquals(CompanionMusic.MusicLyric(10_000L, "First line"), music.lyrics.single())
        assertNull(music.pending)
        assertNull(music.lastCommand)
    }

    @Test fun explicitNullsRepresentUnknownOrAbsentStateWithoutDefaults() {
        val json = state().put("controlPlayerId", JSONObject.NULL).put("queueId", JSONObject.NULL)
            .put("track", JSONObject.NULL).put("positionMs", JSONObject.NULL)
        val music = parse(json)
        assertNull(music.controlPlayerId)
        assertNull(music.queueId)
        assertNull(music.track)
        assertNull(music.positionMs)
        assertNull(music.rooms[1].volume)
        assertNull(music.rooms[1].muted)
        assertNull(music.rooms[1].groupId)
        assertNull(music.queue.single().durationMs)
        assertNull(music.commandBody(CompanionMusicAction.Transport("play")))
        assertNull(music.commandBody(CompanionMusicAction.QueueItem("item-a")))
    }

    @Test fun unconfiguredWireAllowsBlankIdsAndEmptyCollections() {
        val json = state().put("connection", "not_configured").put("playback", "idle")
            .put("sourceId", "").put("controlPlayerId", "").put("queueId", "")
            .put("track", JSONObject.NULL).put("positionMs", JSONObject.NULL)
            .put("queue", JSONArray()).put("rooms", JSONArray()).put("lyrics", JSONArray())
        for (capability in capabilityNames) json.getJSONObject("capabilities").put(capability, false)
        val music = CompanionMusic.fromJson(json)
        assertEquals("", music.sourceId)
        assertEquals("", music.controlPlayerId)
        assertEquals("", music.queueId)
        assertTrue(music.queue.isEmpty())
        assertTrue(music.rooms.isEmpty())
        assertTrue(music.lyrics.isEmpty())
        for (action in actions) assertNull(action.toString(), music.commandBody(action))
    }

    @Test fun absentNullableFieldsAreRejectedInsteadOfTreatedAsExplicitNull() {
        for (field in listOf("controlPlayerId", "queueId", "track", "positionMs", "pending", "lastCommand")) {
            rejects(field) { parse(state().apply { remove(field) }) }
        }
        for ((section, field) in listOf(
            "track" to "durationMs", "queue" to "durationMs",
            "rooms" to "volume", "rooms" to "muted", "rooms" to "groupId",
        )) {
            val json = state()
            row(json, section).remove(field)
            rejects("$section.$field") { parse(json) }
        }
    }

    @Test fun missingRequiredMetadataAndExplicitNullNumbersAreRejected() {
        for (field in listOf("schemaVersion", "sourceRevision", "sourceId", "connection", "playback",
            "audioOwnedByOther", "capabilities", "queue", "rooms", "lyrics")) {
            rejects(field) { parse(state().apply { remove(field) }) }
        }
        for ((section, fields) in listOf(
            "track" to listOf("id", "title", "artist", "album", "isLive"),
            "queue" to listOf("id", "title", "artist", "available", "canSelect"),
            "rooms" to listOf("id", "name", "available", "inCurrentGroup", "canSelect",
                "canJoin", "canRemove", "canSetVolume"),
            "lyrics" to listOf("timeMs", "text"),
            "pending" to listOf("requestId", "command", "startedAtElapsedMs"),
            "lastCommand" to listOf("requestId", "command", "outcome"),
        )) {
            for (field in fields) {
                val json = stateWithResults()
                row(json, section).remove(field)
                rejects("$section.$field") { parse(json) }
            }
        }
        for ((section, field) in listOf("" to "sourceRevision", "lyrics" to "timeMs",
            "pending" to "requestId", "pending" to "startedAtElapsedMs", "lastCommand" to "requestId")) {
            val json = stateWithResults()
            (if (section.isEmpty()) json else row(json, section)).put(field, JSONObject.NULL)
            rejects("$section.$field=null") { parse(json) }
        }
    }

    @Test fun unsupportedSchemasAndUnknownStateEnumsAreRejected() {
        for (version in listOf(0, 2, "1", 1.0, true, JSONObject.NULL, 4_294_967_297L)) {
            rejects("schemaVersion=$version") { parse(state().put("schemaVersion", version)) }
        }
        for ((field, value) in listOf("connection" to "READY", "connection" to "unknown",
            "playback" to "buffering", "playback" to "PLAYING")) {
            rejects("$field=$value") { parse(state().put(field, value)) }
        }
    }

    @Test fun stringsBooleansAndObjectsAreNeverCoerced() {
        val badText = listOf(7, true, JSONObject.NULL, JSONObject(), JSONArray())
        for (field in listOf("sourceId", "connection", "playback")) {
            for (value in badText) rejects(field) { parse(state().put(field, value)) }
        }
        for (field in listOf("controlPlayerId", "queueId")) {
            for (value in listOf(7, true, JSONObject(), JSONArray())) {
                rejects(field) { parse(state().put(field, value)) }
            }
        }
        for (value in listOf("false", 0, JSONObject.NULL)) {
            rejects("audioOwnedByOther") { parse(state().put("audioOwnedByOther", value)) }
        }
        for (section in listOf("track", "pending", "lastCommand", "capabilities")) {
            for (value in listOf("{}", JSONArray(), false)) {
                rejects(section) { parse(state().put(section, value)) }
            }
        }
    }

    @Test fun integerFieldsRejectFractionsNumericStringsAndOverflow() {
        for ((section, field) in listOf(
            "" to "sourceRevision", "" to "positionMs", "track" to "durationMs",
            "queue" to "durationMs", "rooms" to "volume", "lyrics" to "timeMs",
            "pending" to "requestId", "pending" to "startedAtElapsedMs", "lastCommand" to "requestId",
        )) {
            for (value in listOf("12", 12.0, 12.5, true,
                java.math.BigInteger("9223372036854775808"))) {
                val json = stateWithResults()
                (if (section.isEmpty()) json else row(json, section)).put(field, value)
                rejects("$section.$field=$value") { parse(json) }
            }
        }
    }

    @Test fun integerRangesRejectNegativeTimesZeroDurationAndBadVolume() {
        for ((section, field, value) in listOf(
            Triple("", "sourceRevision", -1L), Triple("", "positionMs", -1L),
            Triple("track", "durationMs", 0L), Triple("track", "durationMs", -1L),
            Triple("queue", "durationMs", 0L), Triple("lyrics", "timeMs", -1L),
            Triple("pending", "requestId", 0L), Triple("pending", "startedAtElapsedMs", -1L),
            Triple("lastCommand", "requestId", 0L), Triple("rooms", "volume", -1L),
            Triple("rooms", "volume", 101L), Triple("rooms", "volume", 4_294_967_346L),
        )) {
            val json = stateWithResults()
            (if (section.isEmpty()) json else row(json, section)).put(field, value)
            rejects("$section.$field=$value") { parse(json) }
        }
    }

    @Test fun timingsCannotInventProgressForAbsentUnknownOrLiveTracks() {
        rejects { parse(state().put("positionMs", 180_001L)) }
        rejects { parse(state().put("track", JSONObject.NULL)) }
        val unknown = state()
        unknown.getJSONObject("track").put("durationMs", JSONObject.NULL)
        rejects { parse(unknown) }
        val live = state().put("positionMs", JSONObject.NULL)
        live.getJSONObject("track").put("isLive", true)
        rejects { parse(live) }
        live.getJSONObject("track").put("durationMs", JSONObject.NULL)
        live.put("positionMs", 0)
        rejects { parse(live) }
        live.put("positionMs", JSONObject.NULL)
        assertNull(parse(live).positionMs)
        assertNull(parse(live).track!!.durationMs)
    }

    @Test fun longRevisionAndTimingsArePreservedWithoutIntTruncation() {
        val json = stateWithResults().put("sourceRevision", Long.MAX_VALUE)
        json.getJSONObject("track").put("durationMs", 4_294_967_296L)
        json.put("positionMs", 4_294_967_295L)
        json.getJSONObject("pending").put("requestId", 4_294_967_296L)
            .put("startedAtElapsedMs", 4_294_967_297L)
        json.getJSONObject("lastCommand").put("requestId", Long.MAX_VALUE)
        json.getJSONArray("lyrics").getJSONObject(0).put("timeMs", 4_294_967_298L)
        val music = parse(json)
        assertEquals(Long.MAX_VALUE, music.sourceRevision)
        assertEquals(4_294_967_296L, music.track!!.durationMs)
        assertEquals(4_294_967_295L, music.positionMs)
        assertEquals(4_294_967_296L, music.pending!!.requestId)
        assertEquals(4_294_967_297L, music.pending.startedAtElapsedMs)
        assertEquals(Long.MAX_VALUE, music.lastCommand!!.requestId)
        assertEquals(4_294_967_298L, music.lyrics.single().timeMs)
    }

    @Test fun capabilityFlagsMustAllBePresentActualBooleans() {
        for (field in capabilityNames) {
            for (value in listOf("true", 1, JSONObject.NULL)) {
                val json = state()
                json.getJSONObject("capabilities").put(field, value)
                rejects(field) { parse(json) }
            }
            val json = state()
            json.getJSONObject("capabilities").remove(field)
            rejects(field) { parse(json) }
        }
    }

    @Test fun collectionsRejectWrongContainersNonObjectRowsAndOversizedCounts() {
        for ((section, limit) in listOf("queue" to 500, "rooms" to 64, "lyrics" to 1000)) {
            for (value in listOf(JSONObject.NULL, JSONObject(), "[]")) {
                rejects(section) { parse(state().put(section, value)) }
            }
            for (value in listOf(JSONObject.NULL, "row", JSONArray(), 1)) {
                rejects(section) { parse(state().put(section, JSONArray().put(value))) }
            }
            val bounded = state()
            val template = row(bounded, section)
            bounded.put(section, JSONArray().apply { repeat(limit) { put(copy(template)) } })
            val music = parse(bounded)
            assertEquals(limit, when (section) {
                "queue" -> music.queue.size
                "rooms" -> music.rooms.size
                else -> music.lyrics.size
            })
            bounded.getJSONArray(section).put(copy(template))
            rejects("$section over limit") { parse(bounded) }
        }
    }

    @Test fun malformedRowMetadataRejectsTheEntireSnapshot() {
        for ((section, fields) in listOf(
            "track" to listOf("id", "title", "artist", "album"),
            "queue" to listOf("id", "title", "artist"),
            "rooms" to listOf("id", "name"),
            "lyrics" to listOf("text"),
        )) {
            for (field in fields) {
                for (value in listOf(9, true, JSONObject.NULL, JSONObject(), "x".repeat(4097))) {
                    val json = state()
                    row(json, section).put(field, value)
                    rejects("$section.$field") { parse(json) }
                }
            }
        }
        for ((section, fields) in listOf(
            "track" to listOf("isLive"), "queue" to listOf("available", "canSelect"),
            "rooms" to listOf("available", "inCurrentGroup", "canSelect", "canJoin",
                "canRemove", "canSetVolume", "muted"),
        )) {
            for (field in fields) {
                for (value in listOf("false", 0)) {
                    val json = state()
                    row(json, section).put(field, value)
                    rejects("$section.$field") { parse(json) }
                }
            }
        }
    }

    @Test fun oversizedRootIdsAndResultMetadataAreRejected() {
        for (field in listOf("sourceId", "controlPlayerId", "queueId")) {
            rejects(field) { parse(state().put(field, "x".repeat(4097))) }
        }
        for ((section, field) in listOf("rooms" to "groupId", "pending" to "command",
            "lastCommand" to "command")) {
            val json = stateWithResults()
            row(json, section).put(field, "x".repeat(4097))
            rejects("$section.$field") { parse(json) }
        }
        val valid = state().put("sourceId", "x".repeat(4096))
        valid.getJSONObject("track").put("title", "x".repeat(4096))
        assertEquals(4096, parse(valid).track!!.title.length)
    }

    @Test fun pendingAndCompletedCommandMetadataUseActualWireFields() {
        val music = parse(stateWithResults())
        assertEquals(CompanionMusic.MusicPending(8L, "set_volume", 100L), music.pending)
        assertEquals(CompanionMusic.MusicResult(7L, "select_queue_item", "confirmed"), music.lastCommand)
        for (section in listOf("pending", "lastCommand")) {
            for (value in listOf(JSONObject.NULL, 1, false)) {
                val json = stateWithResults()
                row(json, section).put("command", value)
                rejects(section) { parse(json) }
            }
        }
        for (outcome in listOf("accepted", "CONFIRMED", JSONObject.NULL, true)) {
            val json = stateWithResults()
            json.getJSONObject("lastCommand").put("outcome", outcome)
            rejects("outcome") { parse(json) }
        }
        for (outcome in listOf("confirmed", "failed", "timed_out", "disconnected")) {
            val json = stateWithResults()
            json.getJSONObject("lastCommand").put("outcome", outcome)
            assertEquals(outcome, parse(json).lastCommand!!.outcome)
        }
    }

    @Test fun transportBodiesBindOnlyTheObservedSourceAndTrack() {
        for (command in listOf("play", "pause", "next", "previous")) {
            assertBody(parse().commandBody(CompanionMusicAction.Transport(command)), """{
                "command":"$command","sourceRevision":42,"sourceId":"ma:leader","trackId":"track-a"
            }""")
        }
        for (command in listOf("stop", "seek", "PLAY", "queue-item", "shuffle", "")) {
            assertNull(command, parse().commandBody(CompanionMusicAction.Transport(command)))
        }
    }

    @Test fun eachTransportRequiresItsOwnCapabilityAndCurrentTrack() {
        for (command in listOf("play", "pause", "next", "previous")) {
            val json = state()
            json.getJSONObject("capabilities").put(command, false)
            assertNull(command, parse(json).commandBody(CompanionMusicAction.Transport(command)))
            val noTrack = state().put("track", JSONObject.NULL).put("positionMs", JSONObject.NULL)
            assertNull(command, parse(noTrack).commandBody(CompanionMusicAction.Transport(command)))
        }
        for (playback in listOf("idle", "stopped")) {
            assertNull(parse(state().put("playback", playback))
                .commandBody(CompanionMusicAction.Transport("pause")))
        }
        assertNotNull(parse(state().put("playback", "paused"))
            .commandBody(CompanionMusicAction.Transport("pause")))
    }

    @Test fun allCommandsAreBlockedWhileNotReadyOrPending() {
        for (connection in listOf("not_configured", "connecting", "offline", "auth_required", "incompatible")) {
            val music = parse(state().put("connection", connection))
            for (action in actions) assertNull("$connection $action", music.commandBody(action))
        }
        for (music in listOf(parse(stateWithResults()))) {
            for (action in actions) {
                assertNull(action.toString(), music.commandBody(action))
                assertFalse(music.can(action))
            }
        }
    }

    @Test fun interruptedMetadataAndSafeCommandsKeepTheirObservedBindings() {
        val music = parse(state().put("audioOwnedByOther", true))
        assertEquals(parse().track, music.track)
        assertEquals(parse().queue, music.queue)
        assertEquals(parse().lyrics, music.lyrics)
        assertTrue(music.capabilities.queue)
        assertTrue(music.capabilities.lyrics)
        assertBody(music.commandBody(CompanionMusicAction.Transport("pause")), """{
            "command":"pause","sourceRevision":42,"sourceId":"ma:leader","trackId":"track-a"
        }""")
        assertBody(music.commandBody(CompanionMusicAction.Volume("ma:join", 35)), """{
            "command":"volume","sourceRevision":42,"sourceId":"ma:leader","roomId":"ma:join","percent":35
        }""")
        assertBody(music.commandBody(CompanionMusicAction.SelectPlayer("ma:join")), """{
            "command":"select-player","sourceRevision":42,"sourceId":"ma:leader","roomId":"ma:join"
        }""")
        assertBody(music.commandBody(CompanionMusicAction.ToggleRoom("ma:member")), """{
            "command":"toggle-room","sourceRevision":42,"sourceId":"ma:leader",
            "roomId":"ma:member","leaderId":"ma:leader","wasInGroup":true
        }""")
    }

    @Test fun interruptionRejectsStartingRepositioningAndJoiningEvenWithAdvertisedCapabilities() {
        val music = parse(state().put("audioOwnedByOther", true))
        val blocked = listOf(
            CompanionMusicAction.Transport("play"), CompanionMusicAction.Transport("next"),
            CompanionMusicAction.Transport("previous"), CompanionMusicAction.Seek(1_000),
            CompanionMusicAction.QueueItem("item-a"), CompanionMusicAction.ToggleRoom("ma:join"),
        )
        for (action in blocked) {
            assertNull(action.toString(), music.commandBody(action))
            assertFalse(action.toString(), music.can(action))
        }
    }

    @Test fun interruptedSafeCommandsStillRequireCapabilitiesPendingAndRoomEligibility() {
        val safe = listOf(
            "pause" to CompanionMusicAction.Transport("pause"),
            "volume" to CompanionMusicAction.Volume("ma:join", 35),
            "selectPlayer" to CompanionMusicAction.SelectPlayer("ma:join"),
            "rooms" to CompanionMusicAction.ToggleRoom("ma:member"),
        )
        for ((capability, action) in safe) {
            val unsupported = state().put("audioOwnedByOther", true)
            unsupported.getJSONObject("capabilities").put(capability, false)
            assertNull(capability, parse(unsupported).commandBody(action))
            assertNull(action.toString(), parse(stateWithResults().put("audioOwnedByOther", true))
                .commandBody(action))
            assertNull(action.toString(), parse(state().put("audioOwnedByOther", true).put("sourceId", ""))
                .commandBody(action))
        }
        for ((field, action) in listOf(
            "canSetVolume" to CompanionMusicAction.Volume("ma:join", 35),
            "canSelect" to CompanionMusicAction.SelectPlayer("ma:join"),
        )) {
            val disabled = state().put("audioOwnedByOther", true)
            disabled.getJSONArray("rooms").getJSONObject(1).put(field, false)
            assertNull(field, parse(disabled).commandBody(action))
        }
        val member = state().put("audioOwnedByOther", true)
        member.getJSONArray("rooms").getJSONObject(2).put("canRemove", false)
        assertNull(parse(member).commandBody(CompanionMusicAction.ToggleRoom("ma:member")))
    }

    @Test fun blankSourceIsReadableButCannotAuthorizeAnyCommand() {
        for (source in listOf("", " \t\n")) {
            val music = parse(state().put("sourceId", source))
            assertEquals(source, music.sourceId)
            for (action in actions) assertNull(action.toString(), music.commandBody(action))
        }
    }

    @Test fun seekRequiresPositiveKnownNonLiveDurationAndInclusiveBounds() {
        val music = parse()
        for (position in listOf(0L, 180_000L)) {
            assertBody(music.commandBody(CompanionMusicAction.Seek(position)), """{
                "command":"seek","sourceRevision":42,"sourceId":"ma:leader",
                "trackId":"track-a","positionMs":$position
            }""")
        }
        for (position in listOf(-1L, 180_001L, Long.MAX_VALUE)) {
            assertNull(music.commandBody(CompanionMusicAction.Seek(position)))
        }
        val disabled = state()
        disabled.getJSONObject("capabilities").put("seek", false)
        assertNull(parse(disabled).commandBody(CompanionMusicAction.Seek(1)))
        val unknownDuration = state().put("positionMs", JSONObject.NULL)
        unknownDuration.getJSONObject("track").put("durationMs", JSONObject.NULL)
        assertNull(parse(unknownDuration).commandBody(CompanionMusicAction.Seek(0)))
        val radio = copy(unknownDuration)
        radio.getJSONObject("track").put("isLive", true)
        val live = parse(radio)
        assertNull(live.positionMs)
        for (action in listOf(CompanionMusicAction.Seek(0),
            CompanionMusicAction.Transport("next"), CompanionMusicAction.Transport("previous"))) {
            assertNull(action.toString(), live.commandBody(action))
        }
        assertNotNull(live.commandBody(CompanionMusicAction.Transport("play")))
    }

    @Test fun idleQueueSelectionBindsQueueWithoutRequiringTrackOrPosition() {
        val json = state().put("playback", "idle").put("track", JSONObject.NULL)
            .put("positionMs", JSONObject.NULL)
        assertBody(parse(json).commandBody(CompanionMusicAction.QueueItem("item-a")), """{
            "command":"queue-item","sourceRevision":42,"sourceId":"ma:leader",
            "queueId":"queue-a","queueItemId":"item-a"
        }""")
    }

    @Test fun queueSelectionRequiresQueueCapabilityIdentityAndEligibleObservedItem() {
        val action = CompanionMusicAction.QueueItem("item-a")
        for (queueId in listOf(JSONObject.NULL, "", " ")) {
            assertNull(parse(state().put("queueId", queueId)).commandBody(action))
        }
        for (field in listOf("available", "canSelect")) {
            val json = state()
            row(json, "queue").put(field, false)
            assertNull(field, parse(json).commandBody(action))
        }
        val disabled = state()
        disabled.getJSONObject("capabilities").put("queue", false)
        assertNull(parse(disabled).commandBody(action))
        for (id in listOf("not-observed", "", " ", "x".repeat(4097))) {
            assertNull(parse().commandBody(CompanionMusicAction.QueueItem(id)))
        }
    }

    @Test fun volumeUsesExactRoomBodyAndNeverClampsInvalidPercent() {
        for (percent in listOf(0, 100)) {
            assertBody(parse().commandBody(CompanionMusicAction.Volume("ma:join", percent)), """{
                "command":"volume","sourceRevision":42,"sourceId":"ma:leader",
                "roomId":"ma:join","percent":$percent
            }""")
        }
        for (percent in listOf(-1, 101, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertNull(parse().commandBody(CompanionMusicAction.Volume("ma:join", percent)))
        }
    }

    @Test fun roomAndQueueControlsDoNotDependOnTrackPlaybackOrSeekCapability() {
        val json = state().put("track", JSONObject.NULL).put("positionMs", JSONObject.NULL)
            .put("playback", "idle")
        for (capability in listOf("play", "pause", "next", "previous", "seek")) {
            json.getJSONObject("capabilities").put(capability, false)
        }
        val music = parse(json)
        for (action in listOf(CompanionMusicAction.QueueItem("item-a"),
            CompanionMusicAction.Volume("ma:join", 50), CompanionMusicAction.ToggleRoom("ma:join"),
            CompanionMusicAction.SelectPlayer("ma:join"))) {
            assertNotNull(action.toString(), music.commandBody(action))
            assertFalse(music.commandBody(action)!!.has("trackId"))
        }
    }

    @Test fun roomActionsRequireGlobalAndPerRoomCapabilitiesAndAvailability() {
        for ((action, global, local) in listOf(
            Triple(CompanionMusicAction.Volume("ma:join", 50), "volume", "canSetVolume"),
            Triple(CompanionMusicAction.ToggleRoom("ma:join"), "rooms", "canJoin"),
            Triple(CompanionMusicAction.SelectPlayer("ma:join"), "selectPlayer", "canSelect"),
        )) {
            val disabled = state()
            disabled.getJSONObject("capabilities").put(global, false)
            assertNull(global, parse(disabled).commandBody(action))
            for (field in listOf(local, "available")) {
                val json = state()
                json.getJSONArray("rooms").getJSONObject(1).put(field, false)
                assertNull(field, parse(json).commandBody(action))
            }
        }
        for (action in listOf(CompanionMusicAction.Volume("unknown", 50),
            CompanionMusicAction.ToggleRoom("unknown"), CompanionMusicAction.SelectPlayer("unknown"))) {
            assertNull(action.toString(), parse().commandBody(action))
        }
    }

    @Test fun groupToggleBindsLeaderAndActualMembershipForJoinAndRemove() {
        assertBody(parse().commandBody(CompanionMusicAction.ToggleRoom("ma:join")), """{
            "command":"toggle-room","sourceRevision":42,"sourceId":"ma:leader",
            "roomId":"ma:join","leaderId":"ma:leader","wasInGroup":false
        }""")
        assertBody(parse().commandBody(CompanionMusicAction.ToggleRoom("ma:member")), """{
            "command":"toggle-room","sourceRevision":42,"sourceId":"ma:leader",
            "roomId":"ma:member","leaderId":"ma:leader","wasInGroup":true
        }""")
        val json = state().put("sourceId", "ma:selected")
        assertEquals("ma:leader", parse(json).commandBody(CompanionMusicAction.ToggleRoom("ma:join"))!!
            .getString("leaderId"))
        for (leader in listOf(JSONObject.NULL, "", " ")) {
            assertNull(parse(state().put("controlPlayerId", leader))
                .commandBody(CompanionMusicAction.ToggleRoom("ma:join")))
        }
        assertNull(parse().commandBody(CompanionMusicAction.ToggleRoom("ma:leader")))
    }

    @Test fun groupMembershipChoosesTheMatchingJoinOrRemovePermission() {
        val joining = state()
        joining.getJSONArray("rooms").getJSONObject(1).put("canJoin", false).put("canRemove", true)
        assertNull(parse(joining).commandBody(CompanionMusicAction.ToggleRoom("ma:join")))
        val removing = state()
        removing.getJSONArray("rooms").getJSONObject(2).put("canRemove", false).put("canJoin", true)
        assertNull(parse(removing).commandBody(CompanionMusicAction.ToggleRoom("ma:member")))
    }

    @Test fun groupToggleUsesObservedLeaderEvenWhenLeaderIsOutsideBoundedRoomRows() {
        val json = state()
        // The server bounds published rooms; controlPlayerId remains the authoritative binding.
        json.put("rooms", JSONArray().put(copy(json.getJSONArray("rooms").getJSONObject(1))))
        assertBody(parse(json).commandBody(CompanionMusicAction.ToggleRoom("ma:join")), """{
            "command":"toggle-room","sourceRevision":42,"sourceId":"ma:leader",
            "roomId":"ma:join","leaderId":"ma:leader","wasInGroup":false
        }""")
    }

    @Test fun playerSelectionDoesNotBindAnUnrelatedTrackQueueOrLeader() {
        val json = state().put("track", JSONObject.NULL).put("positionMs", JSONObject.NULL)
            .put("queueId", JSONObject.NULL).put("controlPlayerId", JSONObject.NULL)
        assertBody(parse(json).commandBody(CompanionMusicAction.SelectPlayer("ma:join")), """{
            "command":"select-player","sourceRevision":42,"sourceId":"ma:leader","roomId":"ma:join"
        }""")
    }

    @Test fun commandsKeepTheirCapturedRevisionTrackQueueAndMembership() {
        val original = parse()
        val changedJson = state().put("sourceRevision", 43L).put("sourceId", "ma:other")
            .put("queueId", "queue-b")
        changedJson.getJSONObject("track").put("id", "track-b")
        val changed = parse(changedJson)
        assertBody(original.commandBody(CompanionMusicAction.Transport("next")), """{
            "command":"next","sourceRevision":42,"sourceId":"ma:leader","trackId":"track-a"
        }""")
        assertBody(changed.commandBody(CompanionMusicAction.Transport("next")), """{
            "command":"next","sourceRevision":43,"sourceId":"ma:other","trackId":"track-b"
        }""")
        assertBody(changed.commandBody(CompanionMusicAction.QueueItem("item-a")), """{
            "command":"queue-item","sourceRevision":43,"sourceId":"ma:other",
            "queueId":"queue-b","queueItemId":"item-a"
        }""")
        val body = original.commandBody(CompanionMusicAction.Transport("next"))!!
        body.put("sourceRevision", -1).put("trackId", "mutated")
        assertEquals(42L, original.commandBody(CompanionMusicAction.Transport("next"))!!.getLong("sourceRevision"))
        assertEquals("track-a", original.track!!.id)
    }

    @Test fun bindingsPreserveExactIdsInsteadOfTrimmingOrGuessingThem() {
        val json = state().put("sourceRevision", 0L).put("sourceId", " ma:leader ")
            .put("queueId", " queue-a ")
        json.getJSONObject("track").put("id", " track-a ")
        val music = parse(json)
        assertBody(music.commandBody(CompanionMusicAction.Transport("play")), """{
            "command":"play","sourceRevision":0,"sourceId":" ma:leader ","trackId":" track-a "
        }""")
        assertBody(music.commandBody(CompanionMusicAction.QueueItem("item-a")), """{
            "command":"queue-item","sourceRevision":0,"sourceId":" ma:leader ",
            "queueId":" queue-a ","queueItemId":"item-a"
        }""")
    }

    @Test fun parserCopiesWireRowsSoLaterJsonMutationCannotAuthorizeOtherTargets() {
        val json = state()
        val captured = parse(json)
        json.put("sourceRevision", 99).put("queueId", "queue-b")
        json.getJSONObject("track").put("id", "other-track")
        json.getJSONArray("rooms").getJSONObject(1).put("id", "other-room")
        json.getJSONArray("queue").getJSONObject(0).put("id", "other-item")
        assertNotNull(captured.commandBody(CompanionMusicAction.QueueItem("item-a")))
        assertNull(captured.commandBody(CompanionMusicAction.QueueItem("other-item")))
        assertNotNull(captured.commandBody(CompanionMusicAction.Volume("ma:join", 50)))
        assertNull(captured.commandBody(CompanionMusicAction.Volume("other-room", 50)))
        assertEquals("track-a", captured.track!!.id)
        assertEquals(42L, captured.sourceRevision)
    }

    @Test fun canAgreesWithCommandAdmissionForEveryActionVariant() {
        for (action in actions) {
            assertTrue(action.toString(), parse().can(action))
            assertNotNull(action.toString(), parse().commandBody(action))
        }
        assertFalse(parse().can(CompanionMusicAction.Transport("stop")))
        assertFalse(parse().can(CompanionMusicAction.Seek(-1)))
    }

    private fun parse(json: JSONObject = state()) =
        CompanionMusic.fromResponse(JSONObject().put("music", json))

    private fun rejects(label: String = "", block: () -> Unit) {
        try {
            block()
            fail("Expected malformed music to be rejected: $label")
        } catch (_: IllegalArgumentException) {
            // Invalid wire data is a caller-visible parse failure, never a coerced snapshot.
        }
    }

    private fun assertBody(actual: JSONObject?, expectedJson: String) {
        assertNotNull(actual)
        val expected = JSONObject(expectedJson)
        assertEquals(expected.keys().asSequence().toSet(), actual!!.keys().asSequence().toSet())
        for (key in expected.keys()) {
            val want = expected.get(key)
            val got = actual.get(key)
            if (want is Number) {
                assertTrue("$key must be an integer", got is Int || got is Long)
                assertEquals(key, want.toLong(), (got as Number).toLong())
            } else assertEquals(key, want, got)
        }
    }

    private fun copy(json: JSONObject) = JSONObject(json.toString())

    private fun row(json: JSONObject, section: String): JSONObject = when (section) {
        "queue", "rooms", "lyrics" -> json.getJSONArray(section).getJSONObject(0)
        else -> json.getJSONObject(section)
    }

    private fun stateWithResults() = state()
        .put("pending", JSONObject("""{"requestId":8,"command":"set_volume","startedAtElapsedMs":100}"""))
        .put("lastCommand", JSONObject("""{"requestId":7,"command":"select_queue_item","outcome":"confirmed"}"""))

    private fun state() = JSONObject("""{
        "schemaVersion":1,"sourceRevision":42,"sourceId":"ma:leader","connection":"ready",
        "playback":"playing","audioOwnedByOther":false,"controlPlayerId":"ma:leader",
        "queueId":"queue-a","pending":null,"lastCommand":null,"positionMs":42500,
        "track":{"id":"track-a","title":"Night Train","artist":"Quartet","album":"Live",
            "durationMs":180000,"isLive":false},
        "capabilities":{"play":true,"pause":true,"next":true,"previous":true,"seek":true,
            "queue":true,"lyrics":true,"rooms":true,"volume":true,"selectPlayer":true},
        "queue":[{"id":"item-a","title":"Next Track","artist":"Band","durationMs":null,
            "available":true,"canSelect":true}],
        "rooms":[
            {"id":"ma:leader","name":"Living room","available":true,"volume":50,"muted":false,
                "groupId":null,"inCurrentGroup":true,"canSelect":true,"canJoin":false,
                "canRemove":false,"canSetVolume":true},
            {"id":"ma:join","name":"Kitchen","available":true,"volume":null,"muted":null,
                "groupId":null,"inCurrentGroup":false,"canSelect":true,"canJoin":true,
                "canRemove":false,"canSetVolume":true},
            {"id":"ma:member","name":"Bedroom","available":true,"volume":30,"muted":true,
                "groupId":"ma:leader","inCurrentGroup":true,"canSelect":true,"canJoin":false,
                "canRemove":true,"canSetVolume":true}
        ],
        "lyrics":[{"timeMs":10000,"text":"First line"}]
    }""")

    private val capabilityNames = listOf("play", "pause", "next", "previous", "seek",
        "queue", "lyrics", "rooms", "volume", "selectPlayer")
    private val actions = listOf(
        CompanionMusicAction.Transport("play"), CompanionMusicAction.Transport("pause"),
        CompanionMusicAction.Transport("next"), CompanionMusicAction.Transport("previous"),
        CompanionMusicAction.Seek(1000), CompanionMusicAction.QueueItem("item-a"),
        CompanionMusicAction.Volume("ma:join", 50), CompanionMusicAction.ToggleRoom("ma:join"),
        CompanionMusicAction.SelectPlayer("ma:join"),
    )
}
