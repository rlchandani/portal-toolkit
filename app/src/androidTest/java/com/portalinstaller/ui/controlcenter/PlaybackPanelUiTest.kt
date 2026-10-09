/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.controlcenter

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.portalinstaller.data.companion.CompanionPlayback
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Real playback UI with synthetic metadata/artwork and callbacks, without Portal transport. */
@RunWith(AndroidJUnit4::class)
class PlaybackPanelUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun oldPortalShowsReadOnlyProgressAndKeepsTransportAndRelativeVolume() {
        // Deliberately omit the capabilities and volume object absent on older Portals.
        val legacy = CompanionPlayback.fromJson(JSONObject()
            .put("active", true).put("playing", false).put("title", TRACK_TITLE)
            .put("durationMs", DURATION).put("positionMs", 30_000))
        val fixture = showPanel(PlaybackFixture(legacy))

        seek().assertDoesNotExist()
        volume().assertDoesNotExist()
        compose.onNode(readOnlyProgress).assertIsDisplayed()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetProgress))
        compose.onNodeWithText("0:30").assertIsDisplayed()
        compose.onNodeWithText("2:00").assertIsDisplayed()
        for (label in listOf("Previous", "Play", "Next", "Volume −", "Volume +")) {
            action(label).performScrollTo().assertIsEnabled().performClick()
        }
        compose.runOnIdle { fixture.playback = legacy.copy(playing = true) }
        action("Play").assertDoesNotExist()
        action("Pause").performScrollTo().assertIsEnabled().performClick()

        compose.runOnIdle {
            assertEquals(listOf(
                "previous" to null, "playpause" to null, "next" to null, "playpause" to null,
            ), fixture.media)
            assertEquals(listOf("down", "up"), fixture.relativeVolume)
            assertTrue(fixture.absoluteVolume.isEmpty())
            assertTrue(fixture.artworkRequests.isEmpty())
        }
    }

    @Test fun seekKeepsItsDraftAcrossPollingAndEmitsOnceOnlyOnDragRelease() {
        val fixture = showPanel()
        val slider = seek().performScrollTo().assertIsEnabled()

        startDrag(slider)
        val draft = progress(slider)
        assertTrue("The drag must actually move the playback position", draft > .25f)
        compose.runOnIdle {
            assertTrue("Pointer moves must not send seek commands", fixture.media.isEmpty())
            fixture.playback = fixture.playback.copy(positionMs = 5_000)
        }
        assertEquals("A position poll must not replace an in-progress draft",
            draft, progress(slider), .0001f)
        moveDragToRight(slider)
        compose.runOnIdle {
            assertTrue("Even the final pointer move must wait for release", fixture.media.isEmpty())
        }
        slider.performTouchInput { up() }

        compose.runOnIdle {
            assertEquals(listOf("seek" to DURATION), fixture.media)
            fixture.playback = fixture.playback.copy(positionMs = DURATION)
        }
        assertEquals(1f, progress(slider), .0001f)
        compose.runOnIdle {
            assertEquals("Polling after release must not send a second seek",
                listOf("seek" to DURATION), fixture.media)
            assertTrue(fixture.relativeVolume.isEmpty())
            assertTrue(fixture.absoluteVolume.isEmpty())
        }
    }

    @Test fun absoluteVolumeKeepsItsDraftAcrossPollingAndEmitsOnceOnlyOnDragRelease() {
        val fixture = showPanel()
        val slider = volume().performScrollTo().assertIsEnabled()

        startDrag(slider)
        val draft = progress(slider)
        assertTrue("The drag must actually change volume", draft > 2f)
        compose.runOnIdle {
            assertTrue("Pointer moves must not send volume commands", fixture.absoluteVolume.isEmpty())
            fixture.playback = fixture.playback.copy(
                volume = fixture.playback.volume!!.copy(level = 1))
        }
        assertEquals("A volume poll must not replace an in-progress draft",
            draft, progress(slider), .0001f)
        moveDragToRight(slider)
        compose.runOnIdle {
            assertTrue("Even the final pointer move must wait for release",
                fixture.absoluteVolume.isEmpty())
        }
        slider.performTouchInput { up() }

        compose.runOnIdle {
            assertEquals(listOf(10), fixture.absoluteVolume)
            fixture.playback = fixture.playback.copy(
                volume = fixture.playback.volume!!.copy(level = 10))
        }
        assertEquals(10f, progress(slider), .0001f)
        compose.runOnIdle {
            assertEquals("Polling after release must not send a second volume command",
                listOf(10), fixture.absoluteVolume)
            assertTrue(fixture.media.isEmpty())
            assertTrue(fixture.relativeVolume.isEmpty())
        }
    }

    @Test fun busyAndDisconnectedPanelsCannotEmitControlCallbacks() {
        val fixture = showPanel(PlaybackFixture().apply { busy = true })

        for ((connected, busy) in listOf(true to true, false to false)) {
            compose.runOnIdle { fixture.connected = connected; fixture.busy = busy }
            for (label in listOf("Previous", "Pause", "Next", "Volume −", "Volume +")) {
                action(label).performScrollTo().assertIsNotEnabled()
                    .performTouchInput { click() }
            }
            for (slider in listOf(seek(), volume())) {
                slider.performScrollTo().assertIsNotEnabled().performTouchInput {
                    down(Offset(width * .2f, centerY))
                    moveTo(Offset(width * .6f, centerY))
                    moveTo(Offset(width - 1f, centerY))
                    up()
                }
            }
            compose.runOnIdle { assertNoCommands(fixture) }
        }

        compose.runOnIdle { fixture.connected = true }
        seek().assertIsEnabled()
        volume().assertIsEnabled()
        action("Next").performScrollTo().assertIsEnabled().performClick()
        action("Volume +").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf("next" to null), fixture.media)
            assertEquals(listOf("up"), fixture.relativeVolume)
            assertTrue(fixture.absoluteVolume.isEmpty())
        }
    }

    @Test fun disablingDuringADragDiscardsPendingSeekAndVolumeCommands() {
        val fixture = showPanel()
        val seekSlider = seek().performScrollTo().assertIsEnabled()
        startDrag(seekSlider)
        compose.runOnIdle {
            assertNoCommands(fixture)
            fixture.busy = true
        }
        seekSlider.assertIsNotEnabled().performTouchInput { up() }
        compose.runOnIdle {
            assertNoCommands(fixture)
            fixture.busy = false
        }

        val volumeSlider = volume().performScrollTo().assertIsEnabled()
        startDrag(volumeSlider)
        compose.runOnIdle {
            assertNoCommands(fixture)
            fixture.connected = false
        }
        volumeSlider.assertIsNotEnabled().performTouchInput { up() }
        compose.runOnIdle {
            assertNoCommands(fixture)
            fixture.connected = true
        }
        seek().assertIsEnabled()
        volume().assertIsEnabled()
        assertEquals("Discarded seek drafts must return to the reported position",
            .25f, progress(seek()), .0001f)
        assertEquals("Reconnect must discard a volume draft that was never committed",
            2f, progress(volume()), .0001f)
        compose.runOnIdle { assertNoCommands(fixture) }
    }

    @Test fun cancellingEnabledDragsDoesNotCommitAndAccessibilityStillWorksAfterwards() {
        val fixture = showPanel()
        val seekSlider = seek().performScrollTo()
        startDrag(seekSlider)
        seekSlider.performTouchInput { cancel() }
        compose.runOnIdle { assertNoCommands(fixture) }
        assertEquals(.25f, progress(seek()), .0001f)

        val volumeSlider = volume().performScrollTo()
        startDrag(volumeSlider)
        volumeSlider.performTouchInput { cancel() }
        compose.runOnIdle { assertNoCommands(fixture) }
        assertEquals(2f, progress(volume()), .0001f)

        seek().performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { action ->
            assertTrue(action(.5f))
        }
        volume().performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { action ->
            assertTrue(action(7f))
        }
        compose.runOnIdle {
            assertEquals(listOf("seek" to 60_000L), fixture.media)
            assertEquals(listOf(7), fixture.absoluteVolume)
        }
    }

    @Test fun nativeTrackTapsStillCommitOnceAfterRelease() {
        val fixture = showPanel()
        // Material extends horizontal semantics 10dp past each pointer track edge.
        // Tap inside the track, and keep down/up separate to verify release confirmation.
        val seekSlider = seek().performScrollTo()
        seekSlider.performTouchInput { down(Offset(width * .75f, centerY)) }
        compose.runOnIdle { assertNoCommands(fixture) }
        seekSlider.performTouchInput { up() }
        val sought = (progress(seek()) * DURATION).roundToLong()
        val volumeSlider = volume().performScrollTo()
        volumeSlider.performTouchInput { down(Offset(width * .75f, centerY)) }
        compose.runOnIdle { assertTrue(fixture.absoluteVolume.isEmpty()) }
        volumeSlider.performTouchInput { up() }
        val level = progress(volume()).roundToInt()
        compose.runOnIdle {
            assertTrue("The native seek track must respond to the tap", sought in 70_000..100_000)
            assertTrue("The native volume track must respond to the tap", level in 6..9)
            assertEquals(listOf("seek" to sought), fixture.media)
            assertEquals(listOf(level), fixture.absoluteVolume)
        }
    }

    @Test fun inactivePlaybackDisablesTransportButKeepsCapablePortalVolume() {
        val inactive = CompanionPlayback.fromJson(JSONObject()
            .put("active", false).put("playing", true).put("canSeek", true)
            .put("durationMs", DURATION).put("positionMs", 30_000)
            .put("volume", JSONObject().put("level", 2).put("max", 10)
                .put("muted", false).put("canSet", true)))
        val fixture = showPanel(PlaybackFixture(inactive))

        compose.onNodeWithText("Nothing playing").assertIsDisplayed()
        compose.onNodeWithText("Start audio on your Portal to enable playback.").assertIsDisplayed()
        seek().assertDoesNotExist()
        compose.onNode(readOnlyProgress).assertDoesNotExist()
        for (label in listOf("Previous", "Play", "Next")) {
            action(label).performScrollTo().assertIsNotEnabled().performTouchInput { click() }
        }
        volume().performScrollTo().assertIsEnabled()
        for (label in listOf("Volume −", "Volume +")) {
            action(label).performScrollTo().assertIsEnabled().performClick()
        }
        compose.runOnIdle {
            assertTrue("Inactive playback must not send media commands", fixture.media.isEmpty())
            assertEquals(listOf("down", "up"), fixture.relativeVolume)
            assertTrue(fixture.absoluteVolume.isEmpty())
        }
    }

    @Test fun outputControlledVolumeHasNoSliderAndRejectsRelativeVolume() {
        val playback = capablePlayback().let {
            it.copy(volume = it.volume!!.copy(canSet = false))
        }
        val fixture = showPanel(PlaybackFixture(playback))

        volume().assertDoesNotExist()
        compose.onNodeWithText("Volume is controlled by the output device.")
            .performScrollTo().assertIsDisplayed()
        for (label in listOf("Volume −", "Volume +")) {
            action(label).performScrollTo().assertIsNotEnabled().performTouchInput { click() }
        }
        seek().performScrollTo().assertIsEnabled()
        action("Pause").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf("playpause" to null), fixture.media)
            assertTrue(fixture.relativeVolume.isEmpty())
            assertTrue(fixture.absoluteVolume.isEmpty())
        }
    }

    @Test fun artworkDoesNotReloadForPositionPollsButReloadsForVersionDeviceAndReconnect() {
        val fixture = showPanel(PlaybackFixture(capablePlayback("generated-art-1")))
        val first = listOf("synthetic-portal-a" to "generated-art-1")
        compose.runOnIdle { assertEquals(first, fixture.artworkRequests) }

        // Await each distinct snapshot's composition rather than relying on elapsed time.
        for (position in listOf(31_000L, 32_000L, 33_000L, 34_000L)) {
            compose.runOnIdle { fixture.playback = fixture.playback.copy(positionMs = position) }
            compose.runOnIdle { assertEquals(first, fixture.artworkRequests) }
        }
        compose.runOnIdle { fixture.busy = true }
        compose.runOnIdle {
            assertEquals(first, fixture.artworkRequests)
            fixture.busy = false
            fixture.playback = capablePlayback("generated-art-2")
        }
        val newVersion = first + ("synthetic-portal-a" to "generated-art-2")
        compose.runOnIdle {
            assertEquals(newVersion, fixture.artworkRequests)
            fixture.deviceKey = "synthetic-portal-b"
        }
        val newDevice = newVersion + ("synthetic-portal-b" to "generated-art-2")
        compose.runOnIdle {
            assertEquals(newDevice, fixture.artworkRequests)
            fixture.connected = false
        }
        compose.runOnIdle { fixture.playback = capablePlayback("generated-art-3") }
        compose.runOnIdle {
            assertEquals("Disconnected panels must not request artwork",
                newDevice, fixture.artworkRequests)
            fixture.connected = true
        }
        compose.runOnIdle {
            assertEquals(newDevice + ("synthetic-portal-b" to "generated-art-3"),
                fixture.artworkRequests)
            assertNoCommands(fixture)
        }
    }

    @Test fun narrowPanelWithDoubleFontScaleKeepsBoundsAndAccessibleControls() {
        val playback = capablePlayback().copy(
            title = "A generated track with a deliberately very long title for a narrow phone",
            artist = "A generated artist name that needs truncation",
            album = "A generated album name that needs truncation",
        )
        val fixture = showPanel(PlaybackFixture(playback), fontScale = 2f)
        val density = compose.activity.resources.displayMetrics.density
        val viewport = bounds(compose.onNodeWithTag(VIEWPORT))
        assertEquals(280 * density, viewport.width, 1f)

        for ((text, maxLines) in listOf(playback.title to 2, playback.artist to 1, playback.album to 1)) {
            val node = compose.onNodeWithText(text, useUnmergedTree = true)
                .performScrollTo().assertIsDisplayed()
            assertInside("Playback metadata", bounds(node), bounds(compose.onNodeWithTag(CARD)))
            assertTextFits(node, maxLines)
        }
        for (labels in listOf(listOf("Previous", "Pause", "Next"), listOf("Volume −", "Volume +"))) {
            action(labels.first()).performScrollTo()
            val buttons = labels.map { label ->
                val node = action(label).assertIsDisplayed().assertIsEnabled()
                    .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
                val target = bounds(node)
                assertTrue("$label needs at least a 48dp touch target",
                    target.width >= 48 * density - 1 && target.height >= 48 * density - 1)
                assertInside("$label touch target", target, bounds(compose.onNodeWithTag(CARD)))
                assertTrue("$label must fit the narrow viewport horizontally",
                    target.left >= viewport.left - 1 && target.right <= viewport.right + 1)
                target
            }
            buttons.zipWithNext().forEach { (left, right) ->
                assertTrue("Adjacent controls must not overlap", left.right <= right.left)
                assertEquals("Controls in a group must stay on the same row", left.top, right.top, 1f)
            }
        }
        for (label in listOf("Volume −", "Volume +")) {
            val node = compose.onNodeWithText(label, useUnmergedTree = true)
            assertInside("$label text", bounds(node), bounds(action(label)))
            assertTextFits(node)
        }

        for ((slider, description) in listOf(
            seek() to "Playback position", volume() to "Portal media volume",
        )) {
            slider.performScrollTo().assertIsDisplayed().assertIsEnabled()
                .assert(hasContentDescription(description))
                .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            // ScrollTo considers the visual track. Leave room for Material's larger hit area
            // when scrolling positions the track against the visible viewport's bottom edge.
            val visibleViewport = compose.onNodeWithTag(VIEWPORT).fetchSemanticsNode().boundsInRoot
            val visibleTrack = slider.fetchSemanticsNode().boundsInRoot
            val extraScroll = visibleTrack.bottom + 12 * density - visibleViewport.bottom
            if (extraScroll > 0) compose.onNodeWithTag(VIEWPORT)
                .performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy -> scrollBy(0f, extraScroll) }
            val target = bounds(slider)
            assertInside(description, target, bounds(compose.onNodeWithTag(CARD)))
            // touchBoundsInRoot expands only OnClick semantics, whereas sliders use
            // SetProgress. Verify the actual pointer hit area above/below the 44dp thumb.
            val touchTarget = Rect(target.left, target.center.y - 24 * density,
                target.right, target.center.y + 24 * density)
            assertInside("$description hit area", touchTarget, bounds(compose.onNodeWithTag(CARD)))
            assertInside("$description visible hit area", touchTarget, visibleViewport)
            assertTrue("$description must fit the narrow viewport horizontally",
                target.left >= viewport.left - 1 && target.right <= viewport.right + 1)
            for ((fraction, y) in listOf(.65f to touchTarget.top + 1, .8f to touchTarget.bottom - 1)) {
                val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
                val before = if (description == "Playback position") fixture.media.size
                    else fixture.absoluteVolume.size
                compose.onRoot().performTouchInput {
                    click(Offset(target.left + target.width * fraction - root.left, y - root.top))
                }
                compose.runOnIdle {
                    val after = if (description == "Playback position") fixture.media.size
                        else fixture.absoluteVolume.size
                    assertEquals("$description must accept exactly one tap at its 48dp hit edge",
                        before + 1, after)
                }
            }
        }
        compose.runOnIdle { fixture.media.clear(); fixture.absoluteVolume.clear() }
        seek().performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
            assertTrue("Accessibility must be able to seek", setProgress(.5f))
        }
        volume().performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
            assertTrue("Accessibility must be able to set volume", setProgress(7f))
        }
        compose.runOnIdle {
            assertEquals(listOf("seek" to 60_000L), fixture.media)
            assertEquals(listOf(7), fixture.absoluteVolume)
            assertTrue(fixture.relativeVolume.isEmpty())
        }
    }

    private fun showPanel(
        fixture: PlaybackFixture = PlaybackFixture(),
        fontScale: Float = 1f,
    ): PlaybackFixture {
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
            ) {
                MaterialTheme {
                    Column(Modifier.width(280.dp).fillMaxHeight().testTag(VIEWPORT)
                        .verticalScroll(rememberScrollState())) {
                        PlaybackPanel(
                            playback = fixture.playback,
                            deviceKey = fixture.deviceKey,
                            connected = fixture.connected,
                            busy = fixture.busy,
                            onMedia = fixture.onMedia,
                            onRelativeVolume = fixture.onRelativeVolume,
                            onSetVolume = fixture.onSetVolume,
                            loadArtwork = fixture.loadArtwork,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        return fixture
    }

    private fun seek() = compose.onNodeWithTag(SEEK)
    private fun volume() = compose.onNodeWithTag(VOLUME)
    private fun action(label: String) = compose.onNode(
        (hasText(label) or hasContentDescription(label)) and
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button) and
            hasAnyAncestor(hasTestTag(CARD)))

    /** Separate input batches let assertions observe real pointer moves before the up event. */
    private fun startDrag(slider: SemanticsNodeInteraction) {
        slider.performTouchInput {
            down(Offset(width * .25f, centerY))
            moveTo(Offset(width * .45f, centerY))
            moveTo(Offset(width * .65f, centerY))
        }
    }

    private fun moveDragToRight(slider: SemanticsNodeInteraction) {
        slider.performTouchInput {
            moveTo(Offset(width * .8f, centerY))
            // Past the track's thumb inset, but still within the node: exactly the maximum.
            moveTo(Offset(width - 1f, centerY))
        }
    }

    private fun progress(slider: SemanticsNodeInteraction) =
        slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current

    private fun assertNoCommands(fixture: PlaybackFixture) {
        assertTrue("Unexpected media callback: ${fixture.media}", fixture.media.isEmpty())
        assertTrue("Unexpected relative volume callback: ${fixture.relativeVolume}",
            fixture.relativeVolume.isEmpty())
        assertTrue("Unexpected absolute volume callback: ${fixture.absoluteVolume}",
            fixture.absoluteVolume.isEmpty())
    }

    private fun assertTextFits(node: SemanticsNodeInteraction, maxLines: Int = Int.MAX_VALUE) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { getLayout ->
            assertTrue("Playback text needs a measurable layout", getLayout(layouts))
        }
        val layout = layouts.single()
        assertTrue("Playback metadata must respect its line limit", layout.lineCount in 1..maxLines)
        assertTrue("Playback glyphs must fit the text height",
            layout.getLineBottom(layout.lineCount - 1) <= layout.size.height + 1)
        assertTrue("Playback text must not overflow horizontally", !layout.didOverflowWidth)
    }

    /** Unclipped layout bounds expose overflow that clipped semantics rectangles would hide. */
    private fun bounds(node: SemanticsNodeInteraction): Rect {
        val measured = node.fetchSemanticsNode()
        return Rect(
            measured.positionInRoot.x,
            measured.positionInRoot.y,
            measured.positionInRoot.x + measured.size.width,
            measured.positionInRoot.y + measured.size.height,
        )
    }

    private fun assertInside(description: String, child: Rect, parent: Rect) {
        assertTrue("$description exceeds its bounds: child=$child, parent=$parent",
            child.left >= parent.left - 1 && child.right <= parent.right + 1 &&
                child.top >= parent.top - 1 && child.bottom <= parent.bottom + 1)
    }

    private class PlaybackFixture(initial: CompanionPlayback = capablePlayback()) {
        var playback by mutableStateOf(initial)
        var deviceKey by mutableStateOf("synthetic-portal-a")
        var connected by mutableStateOf(true)
        var busy by mutableStateOf(false)
        val media = mutableListOf<Pair<String, Long?>>()
        val relativeVolume = mutableListOf<String>()
        val absoluteVolume = mutableListOf<Int>()
        val artworkRequests = mutableListOf<Pair<String, String>>()
        private val artwork = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(0xff176abb.toInt()) }.asImageBitmap()

        // Stable callback identities ensure polling, rather than a new lambda, drives recomposition.
        val onMedia: (String, Long?) -> Unit = { action, position -> media.add(action to position) }
        val onRelativeVolume: (String) -> Unit = { relativeVolume.add(it) }
        val onSetVolume: (Int) -> Unit = { absoluteVolume.add(it) }
        val loadArtwork: suspend (String) -> ImageBitmap? = { version ->
            artworkRequests.add(deviceKey to version)
            artwork
        }
    }

    private companion object {
        const val VIEWPORT = "playback-test-viewport"
        const val CARD = "playback-card"
        const val SEEK = "playback-seek"
        const val VOLUME = "playback-volume"
        const val TRACK_TITLE = "Generated test track"
        const val DURATION = 120_000L
        val readOnlyProgress = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo) and
            !hasTestTag(SEEK) and !hasTestTag(VOLUME)

        fun capablePlayback(artVersion: String? = null) = CompanionPlayback.fromJson(JSONObject()
            .put("active", true).put("playing", true).put("source", "synthetic.test")
            .put("title", TRACK_TITLE).put("artist", "Generated artist").put("album", "Generated album")
            .put("durationMs", DURATION).put("positionMs", 30_000).put("canSeek", true)
            .put("hasArt", artVersion != null).put("artVersion", artVersion ?: JSONObject.NULL)
            .put("volume", JSONObject().put("level", 2).put("max", 10)
                .put("muted", false).put("canSet", true)))
    }
}
