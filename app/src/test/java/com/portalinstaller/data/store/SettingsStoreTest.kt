package com.portalinstaller.data.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: SettingsStore

    @Before
    fun setUp() {
        dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            tempFolder.newFile("settings.preferences_pb")
        }
        store = SettingsStore(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `defaults to system theme and no dynamic color`() = runTest {
        assertEquals(ThemeMode.SYSTEM, store.themeMode.first())
        assertEquals(false, store.useDynamicColor.first())
    }

    @Test
    fun `theme mode round-trips`() = runTest {
        store.setThemeMode(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, store.themeMode.first())

        store.setThemeMode(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, store.themeMode.first())
    }

    @Test
    fun `dynamic color round-trips`() = runTest {
        store.setUseDynamicColor(true)
        assertEquals(true, store.useDynamicColor.first())

        store.setUseDynamicColor(false)
        assertEquals(false, store.useDynamicColor.first())
    }

    @Test
    fun `unknown persisted theme falls back to system`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromName("GARBAGE"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromName(null))
        assertEquals(ThemeMode.DARK, ThemeMode.fromName("DARK"))
    }
}
