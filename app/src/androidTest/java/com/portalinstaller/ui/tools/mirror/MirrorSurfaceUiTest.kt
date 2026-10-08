/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.tools.mirror

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.portalinstaller.data.tools.MirrorGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real surface layout and gestures, with no decoder, session, or device connection. */
@RunWith(AndroidJUnit4::class)
class MirrorSurfaceUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun foldedLayoutCentersSurfaceAndIgnoresLetterboxTouches() {
        val fixture = showSurface()
        assertSurface(216, 144)

        compose.onNodeWithTag(BOX).performTouchInput { click(Offset(108f, 20f)) }
        compose.runOnIdle { assertNull(fixture.tap) }

        compose.onNodeWithTag(SURFACE).performTouchInput { click(Offset(108f, 36f)) }
        compose.runOnIdle {
            val tap = requireNotNull(fixture.tap)
            assertEquals(IntSize(216, 144), tap.second)
            assertEquals(1080 to 360, fixture.geometry.value!!.mapSurfacePoint(
                tap.first.x, tap.first.y, tap.second.width, tap.second.height
            ))
        }
    }

    @Test
    fun rotatingAvailableBoxRemeasuresSurfaceAndGestureCoordinates() {
        val fixture = showSurface()
        compose.runOnIdle { fixture.available.value = IntSize(485, 216) }
        assertSurface(324, 216)

        compose.onNodeWithTag(SURFACE).performTouchInput { click(Offset(162f, 54f)) }
        compose.runOnIdle {
            val tap = requireNotNull(fixture.tap)
            assertEquals(IntSize(324, 216), tap.second)
            assertEquals(1080 to 360, fixture.geometry.value!!.mapSurfacePoint(
                tap.first.x, tap.first.y, tap.second.width, tap.second.height
            ))
        }
    }

    @Test
    fun unknownAndReconnectedDimensionsRemeasureExistingSurface() {
        val fixture = showSurface()
        compose.runOnIdle { fixture.geometry.value = null }
        assertSurface(216, 216)
        compose.runOnIdle { fixture.geometry.value = MirrorGeometry(1440, 2160) }
        assertSurface(216, 324)

        compose.onNodeWithTag(SURFACE).performTouchInput { click(Offset(108f, 81f)) }
        compose.runOnIdle {
            val tap = requireNotNull(fixture.tap)
            assertEquals(IntSize(216, 324), tap.second)
            assertEquals(720 to 540, fixture.geometry.value!!.mapSurfacePoint(
                tap.first.x, tap.first.y, tap.second.width, tap.second.height
            ))
        }
    }

    @Test
    fun longPressAndSwipeReportTheSameMeasuredSurfaceBounds() {
        val fixture = showSurface()
        compose.onNodeWithTag(SURFACE).performTouchInput { longClick(Offset(108f, 36f)) }
        compose.runOnIdle { assertEquals(IntSize(216, 144), fixture.longPressSize) }
        compose.onNodeWithTag(SURFACE).performTouchInput {
            swipe(Offset(54f, 36f), Offset(162f, 108f), durationMillis = 200)
        }
        compose.runOnIdle { assertEquals(IntSize(216, 144), fixture.dragSize) }
    }

    private fun showSurface(): Fixture {
        val fixture = Fixture()
        compose.setContent {
            val available = fixture.available.value
            Layout(content = {
                Box(Modifier.fillMaxSize().testTag(BOX), contentAlignment = Alignment.Center) {
                    MirrorSurface(
                        deviceGeometry = fixture.geometry.value,
                        onSurface = {},
                        onSurfaceDestroyed = {},
                        onTap = { point, size -> fixture.tap = point to size },
                        onDrag = { _, _, size -> fixture.dragSize = size },
                        onLongPress = { _, size -> fixture.longPressSize = size },
                        modifier = Modifier.testTag(SURFACE)
                    )
                }
            }) { measurables, _ ->
                val child = measurables.single().measure(Constraints.fixed(available.width, available.height))
                layout(available.width, available.height) { child.place(0, 0) }
            }
        }
        compose.waitUntil(5_000) {
            runCatching {
                compose.onAllNodes(hasTestTag(SURFACE)).fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
        return fixture
    }

    private fun assertSurface(width: Int, height: Int) {
        compose.waitForIdle()
        val surface = compose.onNodeWithTag(SURFACE).fetchSemanticsNode().boundsInRoot
        val box = compose.onNodeWithTag(BOX).fetchSemanticsNode().boundsInRoot
        assertEquals(width.toFloat(), surface.width, 0.5f)
        assertEquals(height.toFloat(), surface.height, 0.5f)
        assertEquals(box.center.x, surface.center.x, 0.5f)
        assertEquals(box.center.y, surface.center.y, 0.5f)
    }

    private class Fixture {
        // Scale the folded Pixel's available bounds to fit inside any normal test window.
        val available = mutableStateOf(IntSize(216, 485))
        val geometry = mutableStateOf<MirrorGeometry?>(MirrorGeometry(2160, 1440))
        var tap: Pair<Offset, IntSize>? = null
        var longPressSize: IntSize? = null
        var dragSize: IntSize? = null
    }

    private companion object {
        const val BOX = "mirror-box"
        const val SURFACE = "mirror-surface"
    }
}
