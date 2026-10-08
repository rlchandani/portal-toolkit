/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.controlcenter

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ManagementPanelDraftTest {
    private fun layout(): JSONObject = JSONObject("""
        {
          "assignments":{"com.example.player":"Music"},
          "homeOrder":["com.example.player","com.example.browser"],
          "gridOrder":["builtin:tools","widget-tile:widget:42","app:com.example.player","widget-tile:custom:weather:one"],
          "gridSlots":["widget-tile:widget:42",null,"app:com.example.player","widget-tile:custom:weather:one"],
          "widgets":[
            {"id":42,"package":"com.example.provider","class":"ClockProvider","spanX":2,"spanY":1,"kind":"appwidget","customId":"","future":{"keep":true}},
            {"id":0,"package":"","class":"","spanX":2,"spanY":2,"kind":"weather","customId":"weather:one"}
          ],
          "future":{"opaque":[1,2,3]}
        }
    """.trimIndent())

    @Test fun resizeKeepsBoundIdentityAndOpaqueFieldsWithoutMutatingInput() {
        val original = layout()
        val resized = LayoutPanelDraft.resizeWidget(original, 0, 4, 3)
        val widget = resized.getJSONArray("widgets").getJSONObject(0)
        assertEquals(42, widget.getInt("id"))
        assertEquals("ClockProvider", widget.getString("class"))
        assertTrue(widget.getJSONObject("future").getBoolean("keep"))
        assertEquals(4, widget.getInt("spanX"))
        assertEquals(3, widget.getInt("spanY"))
        assertTrue(widget.getBoolean("explicitSize"))
        assertEquals(original.getJSONArray("gridSlots").toString(), resized.getJSONArray("gridSlots").toString())
        assertEquals(original.getJSONObject("future").toString(), resized.getJSONObject("future").toString())
        assertEquals(2, original.getJSONArray("widgets").getJSONObject(0).getInt("spanX"))
    }

    @Test fun widgetReorderUpdatesOnlyWidgetPositionsInExplicitOrders() {
        val moved = LayoutPanelDraft.move(layout(), "widgets", 0, 1)
        assertEquals("weather", moved.getJSONArray("widgets").getJSONObject(0).getString("kind"))
        assertEquals(
            """["builtin:tools","widget-tile:custom:weather:one","app:com.example.player","widget-tile:widget:42"]""",
            moved.getJSONArray("gridOrder").toString(),
        )
        assertEquals(
            """["widget-tile:custom:weather:one",null,"app:com.example.player","widget-tile:widget:42"]""",
            moved.getJSONArray("gridSlots").toString(),
        )
        assertEquals("Music", moved.getJSONObject("assignments").getString("com.example.player"))
    }

    @Test fun reorderWithPartialWidgetOrderDoesNotInsertDuplicateOrMissingTiles() {
        val original = layout().put("gridOrder", JSONArray("""["builtin:tools","widget-tile:widget:42"]"""))
        val moved = LayoutPanelDraft.move(original, "widgets", 0, 1)
        assertEquals(original.getJSONArray("gridOrder").toString(), moved.getJSONArray("gridOrder").toString())
    }

    @Test fun deletingWidgetRemovesItsReferencesAndKeepsBlankSlotPositions() {
        val removed = LayoutPanelDraft.removeWidget(layout(), 0)
        assertEquals(1, removed.getJSONArray("widgets").length())
        assertFalse(removed.getJSONArray("gridOrder").toString().contains("widget:42"))
        assertEquals(4, removed.getJSONArray("gridSlots").length())
        assertTrue(removed.getJSONArray("gridSlots").isNull(0))
        assertTrue(removed.getJSONArray("gridSlots").isNull(1))
        assertEquals("app:com.example.player", removed.getJSONArray("gridSlots").getString(2))
    }

    @Test fun newCustomWidgetsHaveUniqueNativeIdsAndNeverCreateProviderBindings() {
        val added = LayoutPanelDraft.addWidget(layout(), "today")
        val widget = added.getJSONArray("widgets").getJSONObject(2)
        assertEquals(0, widget.getInt("id"))
        assertEquals("", widget.getString("package"))
        assertEquals("", widget.getString("class"))
        assertTrue(widget.getString("customId").startsWith("today:"))
        assertTrue(widget.getBoolean("explicitSize"))
        assertEquals("widget-tile:custom:${widget.getString("customId")}", added.getJSONArray("gridSlots").getString(1))
        assertEquals(layout().getJSONObject("future").toString(), added.getJSONObject("future").toString())
        assertNotEquals(widget.getString("customId"),
            LayoutPanelDraft.addWidget(layout(), "today").getJSONArray("widgets").getJSONObject(2).getString("customId"))
        assertEquals(layout().toString(), LayoutPanelDraft.addWidget(layout(), "appwidget").toString())
    }

    @Test fun nativeSceneTemplatesUseFrozenContractAndKeepExistingIdsOnEdit() {
        val evening = ScenePanelDraft.template("Evening")
        val steps = evening.getJSONArray("steps")
        assertEquals("settings", steps.getJSONObject(0).getString("type"))
        assertTrue(steps.getJSONObject(0).getJSONObject("values").getBoolean("enabled"))
        assertEquals("screensaver", steps.getJSONObject(1).getString("action"))
        assertNull(ScenePanelDraft.validation(JSONArray().put(evening)))
        assertNull(ScenePanelDraft.validation(JSONArray().put(ScenePanelDraft.template("Music"))))
        assertNull(ScenePanelDraft.validation(JSONArray().put(ScenePanelDraft.template("Dashboard"))))
    }

    @Test fun sceneValidationUsesIsoDaysAndBoundsTotalWaits() {
        val scene = ScenePanelDraft.template("Dashboard")
            .put("schedule", JSONObject("""{"enabled":true,"hour":22,"minute":15,"days":[1,7]}"""))
        assertNull(ScenePanelDraft.validation(JSONArray().put(scene)))
        scene.getJSONObject("schedule").put("days", JSONArray().put(0))
        assertNotNull(ScenePanelDraft.validation(JSONArray().put(scene)))
        scene.remove("schedule")
        scene.put("steps", JSONArray()
            .put(JSONObject().put("type", "wait").put("ms", 3000))
            .put(JSONObject().put("type", "wait").put("ms", 3000)))
        assertNotNull(ScenePanelDraft.validation(JSONArray().put(scene)))
    }

    @Test fun sourceStepsUseValuesAndRejectCredentialsAndEscapingLocalFolders() {
        val scene = ScenePanelDraft.template("Evening")
        fun source(values: JSONObject): JSONArray = JSONArray().put(scene.put("steps",
            JSONArray().put(JSONObject().put("type", "source").put("values", values))))
        assertNull(ScenePanelDraft.validation(source(JSONObject().put("source", "local").put("folderPath", "/sdcard/Pictures"))))
        assertNotNull(ScenePanelDraft.validation(source(JSONObject().put("source", "local").put("folderPath", "/sdcard/../data"))))
        assertNotNull(ScenePanelDraft.validation(source(JSONObject().put("source", "immich").put("immichKey", "never-export"))))
    }

    @Test fun incompleteNumericEditCannotSaveThePreviousSettingOrWait() {
        val scene = ScenePanelDraft.template("Dashboard").put("steps", JSONArray().put(
            JSONObject().put("type", "settings").put("domain", "screensaver")
                .put("values", JSONObject().put("intervalSec", ""))))
        assertNotNull(ScenePanelDraft.validation(JSONArray().put(scene), setOf("screensaver:intervalSec")))
        scene.getJSONArray("steps").getJSONObject(0).getJSONObject("values").put("intervalSec", 30)
        assertNull(ScenePanelDraft.validation(JSONArray().put(scene), setOf("screensaver:intervalSec")))
        scene.put("steps", JSONArray().put(JSONObject().put("type", "wait").put("ms", "")))
        assertNotNull(ScenePanelDraft.validation(JSONArray().put(scene)))
    }

    @Test fun addingWidgetCannotExceedTheExplicitGridLimit() {
        val original = layout()
        val full = JSONArray((0 until 512).map { "app:com.example.app$it" })
        original.put("gridSlots", full)
        assertEquals(original.toString(), LayoutPanelDraft.addWidget(original, "today").toString())
    }
}
