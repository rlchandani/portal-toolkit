/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.controlcenter

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise saved activity state without real device configuration or credentials. */
@RunWith(AndroidJUnit4::class)
class DraftRestorationUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sourceFolderDraftSurvivesActivityRestoration() {
        val restoration = source(JSONObject("""{"source":"folder","folderPath":"/sdcard/Pictures"}"""))
        val field = hasSetTextAction() and hasText("Folder on Portal")
        compose.onNode(field).performTextReplacement("/sdcard/Pictures/Unsaved")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(field).assert(editable("/sdcard/Pictures/Unsaved"))
    }

    @Test fun sourceEndpointSurvivesButPasswordIsNotSaved() {
        val restoration = source(JSONObject(
            """{"source":"dav","davUrl":"https://example.invalid","davUser":"test-user"}"""))
        val endpoint = hasSetTextAction() and hasText("Folder address")
        val password = hasSetTextAction() and hasText("Password")
        compose.onNode(endpoint).performTextReplacement("https://draft.example.invalid/photos")
        compose.onNode(password).performTextReplacement("synthetic-test-password")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(endpoint).assert(editable("https://draft.example.invalid/photos"))
        compose.onNode(password).assert(editable(""))
    }

    @Test fun davUserInfoDraftCanBeAppliedButDoesNotRestore() {
        var applied: JSONObject? = null
        val restoration = source(JSONObject(
            """{"source":"dav","davUrl":"https://dav.example.invalid/photos","davUser":"Original"}""")) {
            applied = it
        }
        val endpoint = hasSetTextAction() and hasText("Folder address")
        val username = hasSetTextAction() and hasText("Username")
        val draft = "https://synthetic-user:synthetic-password@dav.example.invalid/photos"
        compose.onNode(endpoint).performTextReplacement(draft)
        compose.onNode(username).performTextReplacement("Unsaved username")
        compose.onNodeWithText("Activate source").assertIsEnabled().performClick()
        assertEquals(draft, applied?.getString("davUrl"))
        compose.onNode(endpoint).assert(editable(draft))

        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(endpoint).assert(editable(""))
        compose.onNode(username).assert(editable("Unsaved username"))
    }

    @Test fun immichQueryDraftCanBeAppliedButDoesNotRestore() {
        var applied: JSONObject? = null
        val restoration = source(JSONObject(
            """{"source":"immich","immichUrl":"https://immich.example.invalid"}""")) { applied = it }
        val endpoint = hasSetTextAction() and hasText("Server address")
        val key = hasSetTextAction() and hasText("API key")
        val album = hasSetTextAction() and hasText("Album name (optional)")
        val draft = "https://immich.example.invalid?api_key=synthetic-key"
        compose.onNode(endpoint).performTextReplacement(draft)
        compose.onNode(key).performTextReplacement("synthetic-api-key")
        compose.onNode(album).performTextReplacement("Unsaved album")
        compose.onNodeWithText("Activate source").assertIsEnabled().performClick()
        assertEquals(draft, applied?.getString("immichUrl"))
        assertEquals("synthetic-api-key", applied?.getString("immichKey"))

        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(endpoint).assert(editable(""))
        compose.onNode(key).assert(editable(""))
        compose.onNode(album).assert(editable("Unsaved album"))
    }

    @Test fun albumFragmentDraftCanBeAppliedButDoesNotRestore() {
        assertSourceUrlIsMemoryOnly("album", "albumUrl", "Album address",
            "https://album.example.invalid/photos#synthetic-token")
    }

    @Test fun signedWebImageDraftCanBeAppliedButDoesNotRestore() {
        assertSourceUrlIsMemoryOnly("web", "webUrl", "Image address",
            "https://image.example.invalid/photo.jpg?X-Amz-Signature=synthetic-signature")
    }

    @Test fun ambiguousSourceUrlDraftStaysEditableButDoesNotRestore() {
        assertSourceUrlIsMemoryOnly("web", "webUrl", "Image address",
            "https://image.example.invalid\\@other.example.invalid/photo.jpg")
    }

    @Test fun unselectedSourceUrlsRestoreOnlyWhenSafe() {
        val restoration = source(JSONObject(
            """{"source":"folder","folderPath":"/sdcard/Pictures","webUrl":"https://image.example.invalid/photo.jpg?token=synthetic-token","albumUrl":"https://album.example.invalid/photos"}"""))
        val folder = hasSetTextAction() and hasText("Folder on Portal")
        compose.onNode(folder).performTextReplacement("/sdcard/Pictures/Unsaved")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(folder).assert(editable("/sdcard/Pictures/Unsaved"))

        compose.onNodeWithText("Photos on this Portal").performClick()
        compose.onNodeWithText("Web image").performClick()
        compose.onNode(hasSetTextAction() and hasText("Image address")).assert(editable(""))
        compose.onNodeWithText("Web image").performClick()
        compose.onNodeWithText("Remote album").performClick()
        compose.onNode(hasSetTextAction() and hasText("Album address"))
            .assert(editable("https://album.example.invalid/photos"))
    }

    @Test fun stringSettingDraftSurvivesActivityRestoration() {
        val restoration = setting("""{"key":"name","title":"Name","type":"string","value":"Original"}""")
        compose.onNode(hasSetTextAction()).performTextReplacement("Unsaved name")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(hasSetTextAction()).assert(editable("Unsaved name"))
    }

    @Test fun publicUrlSettingDraftSurvivesActivityRestoration() {
        val restoration = setting(
            """{"key":"endpoint","title":"Endpoint","type":"string","entry":"url","value":"https://original.example.invalid?token=synthetic-token"}""")
        compose.onNode(hasSetTextAction())
            .assert(editable("https://original.example.invalid?token=synthetic-token"))
        val draft = "http://portal.local:2283/photos"
        compose.onNode(hasSetTextAction()).performTextReplacement(draft)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(hasSetTextAction()).assert(editable(draft))
    }

    @Test fun signedUrlSettingCanBeAppliedButDoesNotRestore() {
        assertUrlSettingIsMemoryOnly(
            "https://calendar.example.invalid/feed.ics?token=synthetic-token")
    }

    @Test fun userInfoUrlSettingCanBeAppliedButDoesNotRestore() {
        assertUrlSettingIsMemoryOnly(
            "https://synthetic-user:synthetic-password@dashboard.example.invalid")
    }

    @Test fun fragmentUrlSettingCanBeAppliedButDoesNotRestore() {
        assertUrlSettingIsMemoryOnly(
            "https://dashboard.example.invalid/#synthetic-token")
    }

    @Test fun invalidUrlSettingStaysEditableButDoesNotRestore() {
        assertUrlSettingIsMemoryOnly("https:///missing-host")
    }

    @Test fun numericSettingDraftSurvivesActivityRestoration() {
        val restoration = setting(
            """{"key":"interval","title":"Interval","type":"int","value":10,"min":1,"max":100,"asText":true}""")
        compose.onNode(hasSetTextAction()).performTextReplacement("42")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(hasSetTextAction()).assert(editable("42"))
    }

    @Test fun secretSettingDraftIsNotSaved() {
        val restoration = setting(
            """{"key":"password","title":"Password","type":"string","secret":true,"hasValue":true}""")
        compose.onNode(hasSetTextAction()).performTextReplacement("synthetic-test-password")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(hasSetTextAction()).assert(editable(""))
    }

    @Test fun existingAndroidSharedStorageFolderCanBeApplied() {
        var applied: JSONObject? = null
        val path = "/storage/emulated/0/Pictures"
        source(JSONObject().put("source", "folder").put("folderPath", path)) { applied = it }
        compose.onNodeWithText("Use this folder").assertIsEnabled().performClick()
        assertEquals(path, applied?.getString("folderPath"))
    }

    private fun source(
        config: JSONObject,
        onSave: (JSONObject) -> Unit = {},
    ): StateRestorationTester = StateRestorationTester(compose).also { restoration ->
        restoration.setContent {
            MaterialTheme {
                Column(Modifier.testTag("draft-fixture")) {
                    PhotoSourcesPanel(config, working = false, onSave = onSave, onPreview = {})
                }
            }
        }
        waitForFixture()
    }

    private fun assertSourceUrlIsMemoryOnly(sourceId: String, key: String, title: String, draft: String) {
        var applied: JSONObject? = null
        val restoration = source(JSONObject().put("source", sourceId)
            .put(key, "https://original.example.invalid")) { applied = it }
        val endpoint = hasSetTextAction() and hasText(title)
        compose.onNode(endpoint).performTextReplacement(draft)
        compose.onNodeWithText("Activate source").assertIsEnabled().performClick()
        assertEquals(draft, applied?.getString(key))
        compose.onNode(endpoint).assert(editable(draft))

        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(endpoint).assert(editable(""))
    }

    private fun assertUrlSettingIsMemoryOnly(draft: String) {
        var applied: JSONObject? = null
        val restoration = setting(
            """{"key":"endpoint","title":"Endpoint","type":"string","entry":"url","value":"https://original.example.invalid"}""") {
            _, body -> applied = body
        }
        compose.onNode(hasSetTextAction()).performTextReplacement(draft)
        compose.onNodeWithText("Save changes").assertIsEnabled().performClick()
        assertEquals(draft, applied?.getString("endpoint"))
        compose.onNode(hasSetTextAction()).assert(editable(draft))

        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(hasSetTextAction()).assert(editable("https://original.example.invalid"))
    }

    private fun setting(
        control: String,
        onApply: (String, JSONObject) -> Unit = { _, _ -> },
    ): StateRestorationTester {
        val schema = JSONObject(
            """{"domains":[{"id":"test","title":"Test","controls":[$control]}]}""")
        return StateRestorationTester(compose).also { restoration ->
            restoration.setContent {
                MaterialTheme {
                    Column(Modifier.testTag("draft-fixture")) {
                        RegistrySettingsPanel(schema, working = false, query = "", advanced = true,
                            onApply = onApply)
                    }
                }
            }
            waitForFixture()
        }
    }

    private fun waitForFixture() {
        compose.waitUntil(5_000) {
            runCatching {
                compose.onAllNodes(hasTestTag("draft-fixture")).fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
    }

    private fun editable(value: String) =
        SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(value))
}
