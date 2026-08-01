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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HistoryStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: HistoryStore

    @Before
    fun setUp() {
        dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            tempFolder.newFile("history.preferences_pb")
        }
        store = HistoryStore(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `add prepends newest entry first`() = runTest {
        store.add(HistoryEntry(1, "oldest", success = true))
        store.add(HistoryEntry(2, "newest", success = false))

        val entries = store.history.first()
        assertEquals(listOf("newest", "oldest"), entries.map { it.description })
        assertEquals(false, entries.first().success)
    }

    @Test
    fun `history caps at 50 entries keeping the newest`() = runTest {
        for (index in 1..60) {
            store.add(HistoryEntry(index.toLong(), "entry-$index", success = true))
        }

        val entries = store.history.first()
        assertEquals(HistoryStore.MAX_ENTRIES, entries.size)
        // Newest is entry-60; the oldest 10 (entry-1..entry-10) are dropped.
        assertEquals("entry-60", entries.first().description)
        assertEquals("entry-11", entries.last().description)
        assertTrue(entries.none { it.description == "entry-10" })
    }

    @Test
    fun `replaceAll overwrites and still caps at 50`() = runTest {
        store.add(HistoryEntry(1, "will-be-replaced", success = true))
        val replacement = (1..60).map { HistoryEntry(it.toLong(), "r-$it", success = true) }

        store.replaceAll(replacement)

        val entries = store.history.first()
        assertEquals(HistoryStore.MAX_ENTRIES, entries.size)
        assertEquals("r-1", entries.first().description)
        assertTrue(entries.none { it.description == "will-be-replaced" })
    }

    @Test
    fun `parse tolerates malformed json`() {
        assertEquals(emptyList<HistoryEntry>(), HistoryStore.parse("not json"))
        assertEquals(emptyList<HistoryEntry>(), HistoryStore.parse("[{\"description\":\"\"}]"))
    }
}
