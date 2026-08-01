package com.portalinstaller.data

import android.content.Context
import com.portalinstaller.PortalApplication
import com.portalinstaller.data.store.AppProfileStore
import com.portalinstaller.data.store.HistoryStore
import com.portalinstaller.data.store.PortalDataStores
import com.portalinstaller.data.store.SavedPortalStore
import com.portalinstaller.data.store.SettingsStore
import com.portalinstaller.data.store.ShellHistoryStore

/**
 * Container simplu de dependențe, fără framework de DI. Deține instanțele
 * partajate (managere, holder-ul de conexiune, store-urile DataStore) folosite
 * de toate ViewModel-urile de funcționalitate. O singură instanță trăiește pe
 * [PortalApplication] și este obținută prin [from].
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val connectionManager = PortalConnectionManager()
    val discoveryManager = PortalDiscoveryManager(connectionManager)
    val backupManager = PortalBackupManager()

    val connectionHolder = ConnectionHolder()
    val connectionMonitor = ConnectionMonitor()

    val savedPortalStore = SavedPortalStore(PortalDataStores.savedPortals(appContext))
    val appProfileStore = AppProfileStore(PortalDataStores.appProfiles(appContext))
    val historyStore = HistoryStore(PortalDataStores.history(appContext))
    val settingsStore = SettingsStore(PortalDataStores.settings(appContext))
    val shellHistoryStore = ShellHistoryStore(PortalDataStores.shellHistory(appContext))

    companion object {
        fun from(context: Context): AppContainer =
            (context.applicationContext as PortalApplication).container
    }
}
