/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.controlcenter

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Pure editor fixture; no transport, pairing, or Portal mutations. */
@RunWith(AndroidJUnit4::class)
class ScenesRevisionUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun remoteAdditionWhileEditingCannotReplaceTheDraftOrItsOpeningRevision() {
        val opening = JSONArray("""[{"id":"evening","name":"Evening","steps":[{"type":"key","action":"home"}]}]""")
        var received by mutableStateOf(opening)
        var revision by mutableStateOf<String?>("a".repeat(64))
        val saved = mutableListOf<Pair<JSONArray, String?>>()
        compose.setContent { MaterialTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ScenesPanel(received, false, onSave = { error("Expected the revision-aware callback") }, onRun = {},
                    revision = revision, onSaveWithRevision = { scenes, token -> saved += scenes to token })
            }
        } }
        compose.onNodeWithText("Scene name").performScrollTo().performTextReplacement("Edited on phone")
        compose.runOnIdle {
            received = JSONArray(opening.toString()).put(JSONObject("""{"id":"scheduled","name":"Browser schedule","steps":[{"type":"key","action":"home"}],"schedule":{"enabled":true,"hour":20,"minute":0,"days":[1]}}"""))
            revision = "b".repeat(64)
        }
        compose.waitForIdle()
        compose.onNodeWithText("Save scenes").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("a".repeat(64), saved.single().second)
            assertEquals(1, saved.single().first.length())
            assertEquals("Edited on phone", saved.single().first.getJSONObject(0).getString("name"))
        }
    }
}
