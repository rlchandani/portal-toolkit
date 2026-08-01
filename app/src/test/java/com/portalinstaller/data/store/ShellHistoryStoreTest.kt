package com.portalinstaller.data.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
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

class ShellHistoryStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: ShellHistoryStore

    @Before
    fun setUp() {
        dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            tempFolder.newFile("shell.preferences_pb")
        }
        store = ShellHistoryStore(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `newest command is first`() = runTest {
        store.add("a")
        store.add("b")
        assertEquals(listOf("b", "a"), store.commands.first())
    }

    @Test
    fun `blank commands are ignored`() = runTest {
        store.add("   ")
        assertEquals(emptyList<String>(), store.commands.first())
    }

    @Test
    fun `consecutive duplicate is not re-added`() = runTest {
        store.add("ls")
        store.add("ls")
        assertEquals(listOf("ls"), store.commands.first())
    }

    @Test
    fun `history caps at MAX keeping newest`() = runTest {
        for (i in 1..120) store.add("cmd-$i")
        val all = store.commands.first()
        assertEquals(ShellHistoryStore.MAX, all.size)
        assertEquals("cmd-120", all.first())
    }
}
