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
import java.util.concurrent.atomic.AtomicInteger

class AppProfileStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val idCounter = AtomicInteger(0)
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: AppProfileStore

    @Before
    fun setUp() {
        dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            tempFolder.newFile("profiles.preferences_pb")
        }
        store = AppProfileStore(
            dataStore = dataStore,
            now = { 42L },
            newId = { "id-${idCounter.incrementAndGet()}" }
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `saveProfile round-trips sorted by name`() = runTest {
        store.saveProfile("Zebra", setOf("com.b"))
        store.saveProfile("Alpha", setOf("com.a"))

        val profiles = store.profiles.first()
        assertEquals(listOf("Alpha", "Zebra"), profiles.map { it.name })
    }

    @Test
    fun `saveProfile with same name (case-insensitive) updates in place`() = runTest {
        store.saveProfile("Kids", setOf("com.a"))
        store.saveProfile("kids", setOf("com.a", "com.b"))

        val profiles = store.profiles.first()
        assertEquals(1, profiles.size)
        assertEquals(setOf("com.a", "com.b"), profiles.first().packageNames)
        assertEquals("id-1", profiles.first().id)
    }

    @Test
    fun `remove deletes by id`() = runTest {
        store.saveProfile("Keep", setOf("com.a"))
        store.saveProfile("Drop", setOf("com.b"))
        val dropId = store.profiles.first().first { it.name == "Drop" }.id

        store.remove(dropId)

        assertEquals(listOf("Keep"), store.profiles.first().map { it.name })
    }

    @Test
    fun `parse reuses the legacy json shape`() {
        val legacy = AppProfileStore.serialize(
            listOf(AppProfile("id-x", "Guests", setOf("com.a", "com.b"), updatedAt = 10))
        )
        val parsed = AppProfileStore.parse(legacy)
        assertEquals(1, parsed.size)
        assertEquals(setOf("com.a", "com.b"), parsed.first().packageNames)
    }
}
