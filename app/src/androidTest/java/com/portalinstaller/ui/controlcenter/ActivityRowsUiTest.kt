/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.controlcenter

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class ActivityRowsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun shortResultCardsFillTheAvailableRowWidth() {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxWidth().testTag("available-row")) {
                    GroupCard(Modifier.testTag("short-result")) {
                        Text("scene")
                        Text("succeeded")
                    }
                }
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("short-result"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        val available = compose.onNodeWithTag("available-row").fetchSemanticsNode().boundsInRoot
        val card = compose.onNodeWithTag("short-result").fetchSemanticsNode().boundsInRoot
        assertEquals("A short result must fill the row", available.width, card.width, 1f)
        assertEquals(available.left, card.left, 1f)
        assertEquals(available.right, card.right, 1f)
    }

    @Test fun activityRowsHaveEqualWidthsAndSeparateVerticalGaps() {
        showActivity(JSONArray().put(result("first", "First result")).put(result("second", "Second result")))
        val available = compose.onNodeWithTag("activity-list").fetchSemanticsNode().boundsInRoot
        val first = compose.onNodeWithText("First result").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithText("Second result").fetchSemanticsNode().boundsInRoot
        assertEquals(available.width, first.width, 1f)
        assertEquals(first.width, second.width, 1f)
        assertEquals(first.left, second.left, 1f)
        val expectedGap = 14 * compose.activity.resources.displayMetrics.density
        assertEquals("Independent lazy rows retain their spacing", expectedGap, second.top - first.bottom, 1f)
    }

    @Test fun offscreenActivityHistoryIsLazyAndRemainsScrollable() {
        val history = JSONArray()
        repeat(40) { history.put(result("operation-$it", "Result $it")) }
        showActivity(history)
        compose.onNodeWithText("Result 39").assertDoesNotExist()
        compose.onNodeWithTag("activity-list").performScrollToNode(hasText("Result 39"))
        compose.onNodeWithText("Result 39").fetchSemanticsNode()
    }

    private fun showActivity(history: JSONArray) {
        compose.setContent {
            MaterialTheme {
                LazyColumn(Modifier.fillMaxWidth().height(480.dp).testTag("activity-list"),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    activityRows(history, false, {}, {})
                }
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("activity-list")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    private fun result(id: String, label: String) = JSONObject()
        .put("id", id).put("label", label).put("state", "succeeded")
        .put("backendMayContinue", false).put("retrySupported", false)
        .put("cancelSupported", false).put("results", JSONArray())
}
