/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.apps

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.portalinstaller.R
import com.portalinstaller.data.PortalAppInfo
import com.portalinstaller.viewmodel.ConnectionState
import com.portalinstaller.viewmodel.InstallState
import com.portalinstaller.viewmodel.PortalAppsSort
import com.portalinstaller.viewmodel.ProfileApplyState
import com.portalinstaller.viewmodel.UninstallState
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Advanced Apps UI and callback boundaries, without ViewModels, ADB or real Portal sessions. */
@RunWith(AndroidJUnit4::class)
class PortalAppConfirmationUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun uninstallTapShowsTheSelectedAppAndPortalWithoutImmediatelyUninstalling() {
        val fixture = showApps()
        tapCard("Uninstall")

        assertTrue(fixture.uninstalls.isEmpty())
        assertTrue(fixture.clears.isEmpty())
        dialogText("Fixture Radio").assertExists()
        dialogText("com.example.radio").assertExists()
        dialogText("Portal: Kitchen Portal · 192.0.2.11:5555").assertExists()
        dialogButton("Uninstall").assertExists()
        dialogButton("Cancel").assertExists()

        // Metadata enrichment or a Portal rename must not change the already selected target.
        compose.runOnIdle {
            fixture.app.value = fixture.app.value.copy(label = "Updated app label")
            fixture.portalName.value = "Updated Portal label"
        }
        dialogText("Fixture Radio").assertExists()
        dialogText("Portal: Kitchen Portal · 192.0.2.11:5555").assertExists()
    }

    @Test fun uninstallCancelDoesNothingAndRepeatedConfirmDispatchesTheCapturedTargetOnce() {
        val fixture = showApps()
        tapCard("Uninstall")
        dialogButton("Cancel").performClick()
        dialogButton("Uninstall").assertDoesNotExist()
        assertTrue(fixture.uninstalls.isEmpty())

        tapCard("Uninstall")
        dialogButton("Uninstall").performSemanticsAction(SemanticsActions.OnClick) { confirm ->
            // Two queued confirmations can arrive before the dialog's removal is recomposed.
            confirm()
            confirm()
        }
        assertEquals(listOf("com.example.radio" to 41L), fixture.uninstalls)
        assertTrue(fixture.clears.isEmpty())
        dialogButton("Uninstall").assertDoesNotExist()
    }

    @Test fun switchingPortalDismissesUninstallAndAQueuedOldConfirmCannotRebind() {
        val fixture = showApps()
        tapCard("Uninstall")
        val oldConfirm = dialogButton("Uninstall").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action!!

        compose.runOnIdle {
            fixture.generation.value = 42L
            fixture.portalName.value = "Living room Portal"
            fixture.address.value = "192.0.2.12:5555"
            fixture.app.value = fixture.app.value.copy(packageName = "com.example.notes", label = "Fixture Notes")
        }
        dialogButton("Uninstall").assertDoesNotExist()
        dialogButton("Cancel").assertDoesNotExist()
        compose.runOnIdle { oldConfirm() }
        assertTrue(fixture.uninstalls.isEmpty())

        tapCard("Uninstall")
        dialogText("Fixture Notes").assertExists()
        dialogText("com.example.notes").assertExists()
        dialogText("Portal: Living room Portal · 192.0.2.12:5555").assertExists()
        dialogButton("Uninstall").performClick()
        assertEquals(listOf("com.example.notes" to 42L), fixture.uninstalls)
    }

    @Test fun reconnectingTheSameHostDismissesUninstallWhenOnlyTheGenerationChanges() {
        val fixture = showApps()
        tapCard("Uninstall")
        val oldConfirm = dialogButton("Uninstall").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action!!

        compose.runOnIdle { fixture.generation.value = 42L }
        dialogButton("Uninstall").assertDoesNotExist()
        compose.runOnIdle { oldConfirm() }
        assertTrue(fixture.uninstalls.isEmpty())
        tapCard("Uninstall")
        dialogButton("Uninstall").performClick()
        assertEquals(listOf("com.example.radio" to 42L), fixture.uninstalls)
    }

    @Test fun clearDataCancelDoesNothingAndConfirmSendsTheSelectedAppAndGenerationOnce() {
        val fixture = showApps()
        tapCard("Clear data")
        dialogText("Fixture Radio").assertExists()
        dialogText("com.example.radio").assertExists()
        dialogText("Portal: Kitchen Portal · 192.0.2.11:5555").assertExists()
        assertTrue(fixture.clears.isEmpty())
        dialogButton("Cancel").performClick()
        assertTrue(fixture.clears.isEmpty())

        tapCard("Clear data")
        dialogButton("Clear data").performSemanticsAction(SemanticsActions.OnClick) { confirm ->
            confirm()
            confirm()
        }
        assertEquals(listOf("com.example.radio" to 41L), fixture.clears)
        assertTrue(fixture.uninstalls.isEmpty())
        dialogButton("Clear data").assertDoesNotExist()
    }

    @Test fun clearDataDismissesOnSameHostReconnectAndRejectsTheOldConfirmation() {
        val fixture = showApps()
        tapCard("Clear data")
        val oldConfirm = dialogButton("Clear data").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action!!

        compose.runOnIdle { fixture.generation.value = 42L }
        dialogButton("Clear data").assertDoesNotExist()
        dialogButton("Cancel").assertDoesNotExist()
        compose.runOnIdle { oldConfirm() }
        assertTrue(fixture.clears.isEmpty())

        tapCard("Clear data")
        dialogButton("Clear data").performClick()
        assertEquals(listOf("com.example.radio" to 42L), fixture.clears)
        assertTrue(fixture.uninstalls.isEmpty())
    }

    @Test fun disconnectDismissesBothActionsAndTheirQueuedConfirmationsCannotDispatch() {
        val fixture = showApps()
        for (action in listOf("Uninstall", "Clear data")) {
            tapCard(action)
            val oldConfirm = dialogButton(action).fetchSemanticsNode()
                .config[SemanticsActions.OnClick].action!!
            compose.runOnIdle { fixture.generation.value = null }
            dialogButton(action).assertDoesNotExist()
            dialogButton("Cancel").assertDoesNotExist()
            compose.runOnIdle { oldConfirm() }
            assertTrue(fixture.uninstalls.isEmpty())
            assertTrue(fixture.clears.isEmpty())
            compose.runOnIdle { fixture.generation.value = 43L }
        }
    }

    private fun showApps(): AppsFixture {
        val fixture = AppsFixture()
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(targetContext.resources.configuration).apply { setLocale(Locale.ENGLISH) }
        val english = targetContext.createConfigurationContext(configuration)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides english, LocalConfiguration provides configuration) {
                MaterialTheme {
                    val app = fixture.app.value
                    val connected = fixture.generation.value != null
                    AppsHubTab(
                        isWifiConnected = connected,
                        activePortalName = fixture.portalName.value,
                        activePortalAddress = fixture.address.value,
                        sessionGeneration = fixture.generation.value,
                        apps = emptyList(),
                        connectionState = ConnectionState.Connected,
                        installState = InstallState.Idle,
                        context = english,
                        selectedUri = null,
                        portalPackages = listOf(app.packageName),
                        portalApps = listOf(app),
                        isLoadingPortalPackages = false,
                        uninstallState = UninstallState.Idle,
                        portalAppsSort = PortalAppsSort.NAME,
                        profiles = emptyList(),
                        profileApplyState = ProfileApplyState.Idle,
                        onOpenDevices = {},
                        onPickFile = {},
                        onInstall = {},
                        onInstallCustomApk = {},
                        onRefreshPortalApps = {},
                        onSortPortalApps = {},
                        onUninstall = { pkg, generation -> fixture.uninstalls.add(pkg to generation) },
                        onLaunchApp = {},
                        onForceStopApp = {},
                        onClearAppData = { pkg, generation -> fixture.clears.add(pkg to generation) },
                        onCreateProfile = {},
                        onApplyProfile = {},
                        onCancelApplyProfile = {},
                        onDeleteProfile = {},
                    )
                }
            }
        }
        compose.onNodeWithText(english.getString(R.string.apps_on_portal)).performClick()
        compose.onNodeWithText("com.example.radio").assertExists()
        return fixture
    }

    private fun tapCard(action: String) {
        compose.onNode(hasText(action) and hasClickAction()).performScrollTo().performClick()
    }

    private fun dialogButton(text: String) =
        compose.onNode(hasText(text) and hasClickAction() and hasAnyAncestor(isDialog()))

    private fun dialogText(text: String) =
        compose.onNode(hasText(text) and hasAnyAncestor(isDialog()))

    private class AppsFixture {
        val generation = mutableStateOf<Long?>(41L)
        val portalName = mutableStateOf("Kitchen Portal")
        val address = mutableStateOf("192.0.2.11:5555")
        val app = mutableStateOf(PortalAppInfo("com.example.radio", "radio", "1.0", 1L,
            1024L, null, label = "Fixture Radio"))
        val uninstalls = mutableListOf<Pair<String, Long>>()
        val clears = mutableListOf<Pair<String, Long>>()
    }
}
