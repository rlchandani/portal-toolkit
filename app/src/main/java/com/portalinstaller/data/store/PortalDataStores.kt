package com.portalinstaller.data.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * Deține instanțele DataStore ale aplicației și migrarea unică din vechiul
 * SharedPreferences. Fiecare DataStore transferă la prima citire șirul JSON
 * din prefs-ul vechi, sub aceeași cheie, astfel încât datele utilizatorilor
 * existenți supraviețuiesc actualizării fără nicio conversie de format.
 */
object PortalDataStores {

    private val Context.savedPortalsDataStore by preferencesDataStore(
        name = "saved_portals",
        produceMigrations = { context ->
            listOf(
                SharedPreferencesMigration(
                    context = context,
                    sharedPreferencesName = "portal_installer_devices",
                    keysToMigrate = setOf("saved_portals")
                )
            )
        }
    )

    private val Context.appProfilesDataStore by preferencesDataStore(
        name = "app_profiles",
        produceMigrations = { context ->
            listOf(
                SharedPreferencesMigration(
                    context = context,
                    sharedPreferencesName = "portal_installer_profiles",
                    keysToMigrate = setOf("app_profiles")
                )
            )
        }
    )

    private val Context.historyDataStore by preferencesDataStore(
        name = "operation_history",
        produceMigrations = { context ->
            listOf(
                // Vechea listă de istoric era în prefs-ul partajat cu last_ip/last_port;
                // migrăm doar cheia „operation_history”, restul rămâne neatins.
                SharedPreferencesMigration(
                    context = context,
                    sharedPreferencesName = "portal_installer_prefs",
                    keysToMigrate = setOf("operation_history")
                )
            )
        }
    )

    fun savedPortals(context: Context): DataStore<Preferences> =
        context.applicationContext.savedPortalsDataStore

    fun appProfiles(context: Context): DataStore<Preferences> =
        context.applicationContext.appProfilesDataStore

    fun history(context: Context): DataStore<Preferences> =
        context.applicationContext.historyDataStore
}
