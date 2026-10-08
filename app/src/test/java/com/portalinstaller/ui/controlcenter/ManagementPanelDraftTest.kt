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

    @Test fun automaticOrdersStayAutomaticWhenAddingAndRemovingANativeWidget() {
        val original = layout().put("gridOrder", JSONArray()).put("gridSlots", JSONArray())
        val before = original.toString()
        val added = LayoutPanelDraft.addWidget(original, "today")
        assertEquals(3, added.getJSONArray("widgets").length())
        assertEquals(0, added.getJSONArray("gridOrder").length())
        assertEquals(0, added.getJSONArray("gridSlots").length())
        val removed = LayoutPanelDraft.removeWidget(added, 2)
        assertEquals(before, removed.toString())
        assertEquals(before, original.toString())
    }

    @Test fun fullGridWithOneHoleCanAddWithoutDroppingTheLastOccupiedPosition() {
        val original = layout()
        val slots = JSONArray((0 until 512).map { "app:com.example.app$it" })
        slots.put(310, JSONObject.NULL)
        original.put("gridSlots", slots)
        val before = original.toString()
        val added = LayoutPanelDraft.addWidget(original, "calendar")
        assertEquals(3, added.getJSONArray("widgets").length())
        val widget = added.getJSONArray("widgets").getJSONObject(2)
        assertEquals(512, added.getJSONArray("gridSlots").length())
        assertEquals("widget-tile:custom:${widget.getString("customId")}",
            added.getJSONArray("gridSlots").getString(310))
        assertEquals("app:com.example.app511", added.getJSONArray("gridSlots").getString(511))
        assertEquals(before, original.toString())
    }

    @Test fun fullExplicitOrderCannotAppendEvenWhenAnotherOrderHasAnEmptySlot() {
        val original = layout().put("gridOrder", JSONArray((0 until 512).map { "app:com.example.app$it" }))
        val before = original.toString()
        assertEquals(before, LayoutPanelDraft.addWidget(original, "today").toString())
        assertEquals(before, original.toString())
    }

    @Test fun widgetCountLimitRejectsAnAdditionButAllowsRemovalThenAddition() {
        val original = layout()
        val widgets = original.getJSONArray("widgets")
        for (index in 2 until 64) widgets.put(JSONObject()
            .put("kind", "today").put("customId", "today:$index").put("id", 0)
            .put("package", "").put("class", "").put("spanX", 2).put("spanY", 2))
        val before = original.toString()
        assertEquals(before, LayoutPanelDraft.addWidget(original, "today").toString())
        val removed = LayoutPanelDraft.removeWidget(original, 63)
        val added = LayoutPanelDraft.addWidget(removed, "today")
        assertEquals(64, added.getJSONArray("widgets").length())
        assertEquals(42, added.getJSONArray("widgets").getJSONObject(0).getInt("id"))
        assertEquals(before, original.toString())
    }

    @Test fun movingAGridHolePreservesItsPositionAndDoesNotRewriteWidgetsOrAppOrder() {
        val original = layout()
        val before = original.toString()
        val moved = LayoutPanelDraft.move(original, "gridSlots", 1, 2)
        assertEquals(
            """["widget-tile:widget:42","app:com.example.player",null,"widget-tile:custom:weather:one"]""",
            moved.getJSONArray("gridSlots").toString(),
        )
        for (field in listOf("widgets", "gridOrder", "homeOrder", "assignments", "future"))
            assertEquals(original.get(field).toString(), moved.get(field).toString())
        assertEquals(before, original.toString())
    }

    @Test fun invalidResizeOrReorderCannotPartiallyAlterACompleteLayout() {
        val original = layout()
        val before = original.toString()
        for ((width, height) in listOf(0 to 2, 5 to 2, 2 to 0, 2 to 5))
            assertEquals(before, LayoutPanelDraft.resizeWidget(original, 0, width, height).toString())
        assertEquals(before, LayoutPanelDraft.resizeWidget(original, 99, 4, 4).toString())
        for ((from, to) in listOf(-1 to 0, 0 to -1, 0 to 99, 99 to 0))
            assertEquals(before, LayoutPanelDraft.move(original, "widgets", from, to).toString())
        assertEquals(before, LayoutPanelDraft.move(original, "assignments", 0, 1).toString())
        assertEquals(before, original.toString())
    }

    @Test fun sceneIdsMustBeWireStringsRatherThanCoercingNumbersOrBooleans() {
        fun validation(id: Any): String? = ScenePanelDraft.validation(JSONArray().put(
            JSONObject("""{"id":"valid-id","name":"Dashboard","steps":[{"type":"key","action":"home"}]}""")
                .put("id", id)))
        assertNull(validation("123"))
        for (id in listOf(123, -1, true, false, JSONObject.NULL))
            assertNotNull("Scene ID $id must not be coerced into a valid string ID.", validation(id))
    }

    @Test fun sceneWaitLimitsRejectWrongTypesAndAllowExactlyFiveSecondsTotal() {
        val scene = JSONObject(
            """{"id":"wait-id","name":"Wait fixture","steps":[{"type":"wait","ms":3000},{"type":"wait","ms":2000}]}""")
        assertNull(ScenePanelDraft.validation(JSONArray().put(scene)))
        for (value in listOf(-1, 5001, 1.5, "500", JSONObject.NULL)) {
            scene.getJSONArray("steps").getJSONObject(1).put("ms", value)
            assertNotNull("Invalid wait $value must block saving.", ScenePanelDraft.validation(JSONArray().put(scene)))
        }
        scene.getJSONArray("steps").getJSONObject(1).put("ms", 2001)
        assertNotNull(ScenePanelDraft.validation(JSONArray().put(scene)))
    }

    @Test fun sceneSchedulesRejectDuplicateDaysAndNonIntegerTimeFields() {
        val scene = JSONObject(
            """{"id":"schedule-id","name":"Schedule fixture","steps":[{"type":"key","action":"home"}],
                "schedule":{"enabled":true,"hour":0,"minute":0,"days":[1,7]}}""")
        assertNull(ScenePanelDraft.validation(JSONArray().put(scene)))
        val schedules = listOf(
            """{"enabled":true,"hour":0,"minute":0,"days":[1,1]}""",
            """{"enabled":true,"hour":0,"minute":0,"days":[]}""",
            """{"enabled":true,"hour":24,"minute":0,"days":[1]}""",
            """{"enabled":true,"hour":0,"minute":60,"days":[1]}""",
            """{"enabled":true,"hour":"6","minute":0,"days":[1]}""",
            """{"enabled":true,"hour":6.5,"minute":0,"days":[1]}""",
            """{"enabled":"true","hour":0,"minute":0,"days":[1]}""",
            """{"enabled":true,"hour":0,"minute":0,"days":["1"]}""",
        )
        for (schedule in schedules) {
            scene.put("schedule", JSONObject(schedule))
            assertNotNull("Malformed schedule $schedule must block saving.",
                ScenePanelDraft.validation(JSONArray().put(scene)))
        }
    }

    @Test fun sceneActionAndCollectionLimitsAcceptTheBoundaryAndRejectTheNextEntry() {
        val scenes = JSONArray()
        for (index in 0 until 20) scenes.put(JSONObject()
            .put("id", "scene-$index").put("name", "Scene $index")
            .put("steps", JSONArray((0 until 16).map { JSONObject("""{"type":"key","action":"home"}""") })))
        assertNull(ScenePanelDraft.validation(scenes))
        scenes.getJSONObject(0).getJSONArray("steps").put(JSONObject("""{"type":"key","action":"home"}"""))
        assertNotNull(ScenePanelDraft.validation(scenes))
        scenes.getJSONObject(0).getJSONArray("steps").remove(16)
        scenes.put(JSONObject("""{"id":"scene-20","name":"One too many","steps":[{"type":"key","action":"home"}]}"""))
        assertNotNull(ScenePanelDraft.validation(scenes))
    }
}
