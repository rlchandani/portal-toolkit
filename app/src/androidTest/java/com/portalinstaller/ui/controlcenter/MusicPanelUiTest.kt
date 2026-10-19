/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.controlcenter

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.portalinstaller.data.companion.CompanionMusic
import com.portalinstaller.data.companion.CompanionMusicAction
import com.portalinstaller.data.companion.commandBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Pure JSON snapshots and recorded callbacks; no pairing, Portal, controller or network. */
@RunWith(AndroidJUnit4::class)
class MusicPanelUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun unsupportedControlsAreAbsentInsteadOfAuthorizingFallbackCommands() {
        val json = state()
        json.getJSONObject("capabilities").apply {
            keys().asSequence().toList().forEach { put(it, false) }
        }
        val fixture = showPanel(Fixture(parse(json)))

        compose.onNodeWithTag("music-seek").assertDoesNotExist()
        compose.onNodeWithTag("music-progress").performScrollTo().assertIsDisplayed()
        for (description in listOf("Play", "Pause", "Previous", "Next")) {
            compose.onNodeWithContentDescription(description).assertDoesNotExist()
        }
        for (label in listOf("Queue", "Speakers", "Lyrics")) {
            compose.onNodeWithText(label).assertDoesNotExist()
        }
        compose.runOnIdle { assertTrue(fixture.sent.isEmpty()) }
    }

    @Test fun transportUsesCurrentSnapshotBindingsAndNeverResumesOnRecomposition() {
        val fixture = showPanel()
        for (command in listOf("previous", "pause", "next")) {
            val label = command.replaceFirstChar { it.uppercaseChar() }
            compose.onNodeWithContentDescription(label).performScrollTo().performClick()
        }
        compose.runOnIdle {
            assertEquals(listOf("previous", "pause", "next"),
                fixture.sent.map { it.second.let { action -> (action as CompanionMusicAction.Transport).command } })
            fixture.sent.forEach { (snapshot, action) ->
                assertSame(fixture.music, snapshot)
                val body = requireNotNull(snapshot.commandBody(action))
                assertEquals(42L, body.getLong("sourceRevision"))
                assertEquals("ma:leader", body.getString("sourceId"))
                assertEquals("track-a", body.getString("trackId"))
            }
            fixture.music = parse(state().put("sourceRevision", 43L).put("playback", "paused"))
        }
        compose.onNodeWithContentDescription("Pause").assertDoesNotExist()
        compose.runOnIdle { assertEquals("Polling must not resume music", 3, fixture.sent.size) }
        compose.onNodeWithContentDescription("Play").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("play", fixture.body().getString("command"))
            assertEquals(43L, fixture.body().getLong("sourceRevision"))
            assertSame(fixture.music, fixture.sent.last().first)
        }
    }

    @Test fun queueRowsAreWholeButtonsAndBindQueueIdentityWithoutRequiringATrack() {
        val json = state().put("playback", "idle").put("track", JSONObject.NULL)
            .put("positionMs", JSONObject.NULL)
        val fixture = showPanel(Fixture(parse(json)))
        open("Queue")
        val row = compose.onNodeWithTag("music-queue-item-a")
        row.assertIsDisplayed().assertIsEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        val bounds = row.fetchSemanticsNode().boundsInRoot
        assertTrue("The queue selection target must span its row", bounds.width > bounds.height * 2)
        row.performClick()

        compose.onNodeWithTag("music-sheet").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, fixture.sent.size)
            val body = fixture.body()
            assertEquals("queue-item", body.getString("command"))
            assertEquals("queue-a", body.getString("queueId"))
            assertEquals("item-a", body.getString("queueItemId"))
            assertEquals(42L, body.getLong("sourceRevision"))
            assertEquals("ma:leader", body.getString("sourceId"))
            assertFalse("Queue actions must not bind an unrelated track", body.has("trackId"))
        }
    }

    @Test fun unavailableOrUnselectableQueueRowsCannotSendAnAction() {
        val json = state()
        val queue = json.getJSONArray("queue")
        queue.getJSONObject(0).put("available", false)
        queue.put(JSONObject(queue.getJSONObject(0).toString()).put("id", "locked")
            .put("title", "Locked track").put("available", true).put("canSelect", false))
        val fixture = showPanel(Fixture(parse(json)))
        open("Queue")
        for (id in listOf("item-a", "locked")) {
            compose.onNodeWithTag("music-queue-$id").assertIsNotEnabled().performTouchInput { click() }
        }
        compose.runOnIdle { assertTrue(fixture.sent.isEmpty()) }
    }

    @Test fun seekSendsOnceOnReleaseAndRetainsItsDraftDuringPositionPolling() {
        val fixture = showPanel()
        val slider = compose.onNodeWithTag("music-seek").performScrollTo().assertIsEnabled()
        startDrag(slider)
        val draft = progress(slider)
        assertTrue("The gesture must move the thumb", draft > .3f)
        compose.runOnIdle {
            assertTrue("Pointer moves must not dispatch", fixture.sent.isEmpty())
            fixture.music = parse(state().put("positionMs", 5_000L))
        }
        assertEquals("A poll must not overwrite an active draft", draft, progress(slider), .001f)
        slider.performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(1, fixture.sent.size)
            val body = fixture.body()
            assertEquals("seek", body.getString("command"))
            assertEquals((draft * DURATION).roundToLong(), body.getLong("positionMs"))
            assertEquals(42L, body.getLong("sourceRevision"))
            assertEquals("track-a", body.getString("trackId"))
        }
    }

    @Test fun cancelledSeekAndStaleStateNeverSendOrAutomaticallyResume() {
        val fixture = showPanel()
        val slider = compose.onNodeWithTag("music-seek").performScrollTo()
        startDrag(slider)
        slider.performTouchInput { cancel() }
        compose.runOnIdle { assertTrue(fixture.sent.isEmpty()) }
        startDrag(slider)
        compose.runOnIdle { fixture.fresh = false }
        slider.assertIsNotEnabled().performTouchInput { up() }
        compose.onNodeWithText("Music status is out of date. Refresh to update it.")
            .performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(fixture.sent.isEmpty())
            fixture.fresh = true
        }
        compose.runOnIdle { assertTrue("Fresh status must not replay a draft", fixture.sent.isEmpty()) }
    }

    @Test fun deviceSwitchDiscardsAnUnreleasedSeekInsteadOfSendingItToTheNewPortal() {
        val fixture = showPanel()
        val slider = compose.onNodeWithTag("music-seek").performScrollTo()
        startDrag(slider)
        assertTrue(progress(slider) > .3f)
        compose.runOnIdle {
            fixture.deviceKey = "fixture:b"
            fixture.music = parse(state().put("positionMs", 90_000L))
        }
        compose.onNodeWithTag("music-seek").performTouchInput { up() }
        assertEquals(.5f, progress(compose.onNodeWithTag("music-seek")), .001f)
        compose.runOnIdle {
            assertTrue("A new Portal must never receive the old drag", fixture.sent.isEmpty())
        }
    }

    @Test fun sourceReplacementDiscardsSeekDraftEvenWhenTrackIdIsUnchanged() {
        val fixture = showPanel()
        for (changeSourceId in listOf(false, true)) {
            compose.runOnIdle { fixture.music = parse(state()) }
            val slider = compose.onNodeWithTag("music-seek").performScrollTo()
            startDrag(slider)
            assertTrue(progress(slider) > .3f)
            compose.runOnIdle {
                val replacement = state().put("sourceRevision", 43L).put("positionMs", 90_000L)
                if (changeSourceId) replacement.put("sourceId", "ma:other-source")
                fixture.music = parse(replacement)
                assertEquals("track-a", fixture.music!!.track!!.id)
            }
            compose.onNodeWithTag("music-seek").performTouchInput { up() }
            assertEquals("The replacement source must show its own position",
                .5f, progress(compose.onNodeWithTag("music-seek")), .001f)
            compose.runOnIdle {
                assertTrue("An old drag must not bind to the replacement source", fixture.sent.isEmpty())
            }
        }
    }

    @Test fun choosingAPlayerAndJoiningAGroupAreSeparateExplicitActions() {
        val fixture = showPanel()
        open("Speakers")
        roomAction("ma:kitchen", "Choose player").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, fixture.sent.size)
            assertEquals("select-player", fixture.body().getString("command"))
            assertEquals("ma:kitchen", fixture.body().getString("roomId"))
            assertFalse(fixture.body().has("leaderId"))
            assertFalse(fixture.body().has("trackId"))
        }
        open("Speakers")
        roomAction("ma:kitchen", "Join group").performScrollTo().performClick()
        compose.onNodeWithText("Join Kitchen?").assertIsDisplayed()
        compose.runOnIdle { assertEquals("Opening confirmation must not join", 1, fixture.sent.size) }
        compose.onNodeWithText("Join speaker").performClick()
        compose.runOnIdle {
            assertEquals(2, fixture.sent.size)
            assertEquals("toggle-room", fixture.body().getString("command"))
            assertEquals("ma:kitchen", fixture.body().getString("roomId"))
            assertEquals("ma:leader", fixture.body().getString("leaderId"))
            assertFalse(fixture.body().getBoolean("wasInGroup"))
        }
    }

    @Test fun groupConfirmationCannotReverseIntentAfterMembershipChanges() {
        val fixture = showPanel()
        open("Speakers")
        roomAction("ma:kitchen", "Join group").performScrollTo().performClick()
        compose.runOnIdle {
            val json = state()
            json.getJSONArray("rooms").getJSONObject(1)
                .put("inCurrentGroup", true).put("canJoin", false).put("canRemove", true)
                .put("groupId", "ma:leader")
            fixture.music = parse(json)
        }
        compose.onNodeWithText("Join speaker").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertTrue(fixture.sent.isEmpty()) }
        compose.onNodeWithText("Cancel").performClick()
        roomAction("ma:kitchen", "Remove from group").performScrollTo().performClick()
        compose.onNodeWithText("Remove Kitchen?").assertIsDisplayed()
        compose.onNodeWithText("Remove speaker").performClick()
        compose.runOnIdle {
            assertEquals(1, fixture.sent.size)
            assertTrue(fixture.body().getBoolean("wasInGroup"))
            assertEquals(42L, fixture.body().getLong("sourceRevision"))
        }
    }

    @Test fun groupConfirmationRetainsTheSnapshotOriginallyReviewedAcrossPolling() {
        val fixture = showPanel()
        val observed = requireNotNull(fixture.music)
        open("Speakers")
        roomAction("ma:kitchen", "Join group").performScrollTo().performClick()
        compose.runOnIdle {
            val poll = state().put("positionMs", 50_000L)
            poll.getJSONArray("rooms").getJSONObject(1).put("volume", 25)
            fixture.music = parse(poll)
        }
        compose.onNodeWithText("Join speaker").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, fixture.sent.size)
            assertSame("Confirmation must retain the reviewed observation", observed, fixture.sent.single().first)
            assertEquals("ma:leader", fixture.body().getString("leaderId"))
            assertFalse(fixture.body().getBoolean("wasInGroup"))
        }
    }

    @Test fun sameMembershipSourceOrLeaderReplacementClosesGroupConfirmation() {
        val fixture = showPanel()
        val replacements = listOf(
            state().put("sourceRevision", 43L),
            state().put("sourceRevision", 43L).put("sourceId", "ma:other-source"),
            state().put("controlPlayerId", "ma:other-leader"),
        )
        for (replacement in replacements) {
            compose.runOnIdle { fixture.music = parse(state()) }
            open("Speakers")
            roomAction("ma:kitchen", "Join group").performScrollTo().performClick()
            compose.onNodeWithText("Join Kitchen?").assertIsDisplayed()
            compose.runOnIdle {
                fixture.music = parse(replacement)
                assertFalse(fixture.music!!.rooms.first { it.id == "ma:kitchen" }.inCurrentGroup)
            }
            compose.onNodeWithText("Join Kitchen?").assertDoesNotExist()
            compose.onNodeWithText("Join speaker").assertDoesNotExist()
            compose.runOnIdle {
                assertTrue("A changed source or leader must not receive the old confirmation", fixture.sent.isEmpty())
            }
        }
    }

    @Test fun volumeIsPerRoomAndSendsOnceOnReleaseAfterPolling() {
        val fixture = showPanel()
        open("Speakers")
        val slider = compose.onNodeWithTag("music-volume-ma:kitchen")
            .performScrollTo().assertIsEnabled()
        startDrag(slider)
        val draft = progress(slider)
        compose.runOnIdle {
            assertTrue(fixture.sent.isEmpty())
            val json = state()
            json.getJSONArray("rooms").getJSONObject(1).put("volume", 10)
            fixture.music = parse(json)
        }
        assertEquals(draft, progress(slider), .01f)
        slider.performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(1, fixture.sent.size)
            assertEquals("volume", fixture.body().getString("command"))
            assertEquals("ma:kitchen", fixture.body().getString("roomId"))
            assertEquals(draft.roundToInt(), fixture.body().getInt("percent"))
            assertEquals(42L, fixture.body().getLong("sourceRevision"))
        }
    }

    @Test fun unsupportedGroupsAndVolumesAreHiddenAndOfflineRoomsAreDisabled() {
        val json = state()
        json.getJSONObject("capabilities").put("rooms", false).put("volume", false).put("lyrics", false)
        json.getJSONArray("rooms").getJSONObject(1).put("available", false)
        val fixture = showPanel(Fixture(parse(json)))
        compose.onNodeWithText("Lyrics").assertDoesNotExist()
        open("Speakers")
        roomAction("ma:kitchen", "Choose player").performScrollTo()
            .assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithText("Join group").assertDoesNotExist()
        compose.onNodeWithText("Remove from group").assertDoesNotExist()
        for (id in listOf("ma:leader", "ma:kitchen")) {
            compose.onNodeWithTag("music-volume-$id").assertDoesNotExist()
        }
        compose.runOnIdle { assertTrue(fixture.sent.isEmpty()) }
    }

    @Test fun aRoomGoingOfflineDuringAVolumeDragPreventsCommit() {
        val fixture = showPanel()
        open("Speakers")
        val slider = compose.onNodeWithTag("music-volume-ma:kitchen").performScrollTo()
        startDrag(slider)
        compose.runOnIdle {
            val json = state()
            json.getJSONArray("rooms").getJSONObject(1).put("available", false)
            fixture.music = parse(json)
            assertTrue(fixture.sent.isEmpty())
        }
        slider.assertIsNotEnabled().performTouchInput { up() }
        compose.runOnIdle { assertTrue(fixture.sent.isEmpty()) }
    }

    @Test fun deviceKeySwitchClosesEverySheetAndGroupConfirmation() {
        val fixture = showPanel()
        for (label in listOf("Queue", "Lyrics", "Speakers")) {
            open(label)
            compose.onNodeWithTag("music-sheet").assertIsDisplayed()
            if (label == "Speakers") {
                roomAction("ma:kitchen", "Join group").performScrollTo().performClick()
                compose.onNodeWithText("Join Kitchen?").assertIsDisplayed()
            }
            compose.runOnIdle { fixture.deviceKey = "fixture:${fixture.deviceKey}:next" }
            compose.onNodeWithTag("music-sheet").assertDoesNotExist()
            compose.onNodeWithText("Join Kitchen?").assertDoesNotExist()
        }
        open("Speakers")
        compose.onNodeWithTag("music-volume-ma:kitchen").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
                androidx.compose.ui.semantics.ProgressBarRangeInfo(30f, 0f..100f, 0)))
        compose.runOnIdle { assertTrue(fixture.sent.isEmpty()) }
    }

    @Test fun loadingOfflineUnconfiguredAndIdleStatesDoNotInventASong() {
        val fixture = showPanel(Fixture(null))
        compose.onNodeWithText("Loading music…").assertIsDisplayed()
        compose.runOnIdle { fixture.connected = false }
        compose.onNodeWithText("Music is offline").assertIsDisplayed()
        compose.runOnIdle {
            fixture.connected = true
            fixture.music = parse(state().put("connection", "not_configured"))
        }
        compose.onNodeWithText("Music isn’t configured").assertIsDisplayed()
        compose.onNodeWithText("Night Train").assertDoesNotExist()
        compose.runOnIdle {
            fixture.music = parse(state().put("track", JSONObject.NULL)
                .put("positionMs", JSONObject.NULL).put("playback", "idle"))
        }
        compose.onNodeWithText("Nothing playing").assertIsDisplayed()
        compose.onNodeWithTag("music-seek").assertDoesNotExist()
        compose.runOnIdle { assertTrue(fixture.sent.isEmpty()) }
    }

    @Test fun pendingAndUncertainResultsExplainStateWithoutReposting() {
        val json = state().put("pending", JSONObject()
            .put("requestId", 8L).put("command", "pause").put("startedAtElapsedMs", 100L))
        val fixture = showPanel(Fixture(parse(json)))
        compose.onNodeWithText("Waiting for Portal confirmation…").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Pause").performScrollTo().assertIsNotEnabled().performClick()
        compose.runOnIdle {
            fixture.music = parse(state().put("lastCommand", JSONObject()
                .put("requestId", 8L).put("command", "pause").put("outcome", "timed_out")))
        }
        compose.onNodeWithText("Couldn’t confirm the change. Refresh before trying again.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Refresh music").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, fixture.refreshes)
            assertTrue(fixture.sent.isEmpty())
            fixture.music = parse(state().put("lastCommand", JSONObject()
                .put("requestId", 8L).put("command", "pause").put("outcome", "failed")))
        }
        compose.onNodeWithText("The music change failed. Refresh to check the player.")
            .performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertTrue(fixture.sent.isEmpty()) }
    }

    @Test fun emptySheetsGiveSpecificMessagesAndLyricsDoNotSendCommands() {
        val json = state().put("queue", JSONArray()).put("rooms", JSONArray()).put("lyrics", JSONArray())
        val fixture = showPanel(Fixture(parse(json)))
        for ((label, empty) in listOf(
            "Queue" to "Your queue is empty.",
            "Speakers" to "No speakers available.",
            "Lyrics" to "No lyrics for this track.",
        )) {
            open(label)
            compose.onNodeWithText(empty).assertIsDisplayed()
            compose.onNodeWithContentDescription("Close ${label.lowercase()}").performClick()
        }
        compose.runOnIdle { assertTrue(fixture.sent.isEmpty()) }
    }

    private fun showPanel(fixture: Fixture = Fixture(parse(state()))): Fixture {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(320.dp).fillMaxHeight().testTag("music-test-viewport")
                    .verticalScroll(rememberScrollState())) {
                    MusicPanel(fixture.music, fixture.deviceKey, fixture.connected, fixture.fresh,
                        fixture.sending, fixture.feedback,
                        onAction = { snapshot, action -> fixture.sent.add(snapshot to action) },
                        onRefresh = { fixture.refreshes++ })
                }
            }
        }
        compose.waitForIdle()
        return fixture
    }

    private fun open(label: String) {
        compose.onNodeWithText(label).performScrollTo().performClick()
    }

    private fun roomAction(roomId: String, label: String) = compose.onNode(
        hasText(label) and hasAnyAncestor(hasTestTag("music-room-$roomId")))

    private fun startDrag(slider: SemanticsNodeInteraction) {
        slider.performTouchInput {
            down(Offset(width * .25f, centerY))
            moveTo(Offset(width * .45f, centerY))
            moveTo(Offset(width * .65f, centerY))
        }
    }

    @Test fun speakersStayOpenWhileAChangeIsPendingWithoutAllowingAnotherWrite() {
        val fixture = showPanel(Fixture(parse(state())))
        open("Speakers")
        compose.runOnIdle {
            fixture.fresh = false
            fixture.sending = true
            fixture.feedback = "Sending change…"
        }
        compose.onNodeWithTag("music-room-list").assertIsDisplayed()
        compose.onNodeWithTag("music-volume-ma:leader").assertIsNotEnabled()
        compose.onNodeWithTag("music-sheet-feedback").assertIsDisplayed()
        compose.runOnIdle {
            fixture.sending = false
            fixture.fresh = true
            fixture.music = parse(state().put("pending", JSONObject()
                .put("requestId", 8L).put("command", "set_volume").put("startedAtElapsedMs", 100L)))
        }
        compose.onNodeWithTag("music-room-list").assertIsDisplayed()
        compose.onNodeWithTag("music-volume-ma:leader").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(fixture.sent.isEmpty()) }
    }

    private fun progress(slider: SemanticsNodeInteraction) =
        slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current

    private class Fixture(initial: CompanionMusic?) {
        var music by mutableStateOf(initial)
        var deviceKey by mutableStateOf("fixture:a")
        var connected by mutableStateOf(true)
        var fresh by mutableStateOf(true)
        var sending by mutableStateOf(false)
        var feedback by mutableStateOf<String?>(null)
        var refreshes = 0
        val sent = mutableListOf<Pair<CompanionMusic, CompanionMusicAction>>()
        fun body() = requireNotNull(sent.last().first.commandBody(sent.last().second))
    }

    private companion object {
        const val DURATION = 180_000L
        fun parse(json: JSONObject) = CompanionMusic.fromResponse(JSONObject().put("music", json))
        fun state() = JSONObject("""{
            "schemaVersion":1,"sourceRevision":42,"sourceId":"ma:leader","connection":"ready",
            "playback":"playing","audioOwnedByOther":false,"controlPlayerId":"ma:leader",
            "queueId":"queue-a","positionMs":42500,"pending":null,"lastCommand":null,
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
                {"id":"ma:kitchen","name":"Kitchen","available":true,"volume":30,"muted":false,
                    "groupId":null,"inCurrentGroup":false,"canSelect":true,"canJoin":true,
                    "canRemove":false,"canSetVolume":true}
            ],
            "lyrics":[{"timeMs":10000,"text":"First line"},{"timeMs":50000,"text":"Second line"}]
        }""")
    }
}
