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

class SavedPortalStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: SavedPortalStore

    @Before
    fun setUp() {
        dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            tempFolder.newFile("portals.preferences_pb")
        }
        store = SavedPortalStore(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `upsert round-trips and sorts newest connected first`() = runTest {
        store.upsert(SavedPortal("Living room", "10.0.0.5", 5555, "Portal", lastConnectedAt = 100))
        store.upsert(SavedPortal("Kitchen", "10.0.0.6", 5555, "Portal", lastConnectedAt = 200))

        val portals = store.portals.first()
        assertEquals(listOf("Kitchen", "Living room"), portals.map { it.name })
    }

    @Test
    fun `upsert replaces an entry with the same id`() = runTest {
        store.upsert(SavedPortal("Old", "10.0.0.5", 5555, "Portal", lastConnectedAt = 100))
        store.upsert(SavedPortal("New", "10.0.0.5", 5555, "Portal 2", lastConnectedAt = 300))

        val portals = store.portals.first()
        assertEquals(1, portals.size)
        assertEquals("New", portals.first().name)
        assertEquals("Portal 2", portals.first().model)
    }

    @Test
    fun `rename changes only the matching portal`() = runTest {
        store.upsert(SavedPortal("A", "10.0.0.5", 5555, "", lastConnectedAt = 100))
        store.upsert(SavedPortal("B", "10.0.0.6", 5555, "", lastConnectedAt = 200))

        store.rename("10.0.0.5:5555", "Renamed")

        val portals = store.portals.first()
        assertEquals("Renamed", portals.first { it.ip == "10.0.0.5" }.name)
        assertEquals("B", portals.first { it.ip == "10.0.0.6" }.name)
    }

    @Test
    fun `remove deletes by id`() = runTest {
        store.upsert(SavedPortal("A", "10.0.0.5", 5555, "", lastConnectedAt = 100))
        store.upsert(SavedPortal("B", "10.0.0.6", 5555, "", lastConnectedAt = 200))

        store.remove("10.0.0.5:5555")

        val portals = store.portals.first()
        assertEquals(listOf("B"), portals.map { it.name })
    }

    @Test
    fun `parse reuses the legacy json shape`() {
        val legacy = SavedPortalStore.serialize(
            listOf(SavedPortal("Den", "10.0.0.9", 5555, "Portal", lastConnectedAt = 50))
        )
        val parsed = SavedPortalStore.parse(legacy)
        assertEquals(1, parsed.size)
        assertEquals("Den", parsed.first().name)
    }
}
