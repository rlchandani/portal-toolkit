package com.portalinstaller

import android.app.Application
import com.portalinstaller.data.AppContainer

/**
 * Application subclass care deține [AppContainer]-ul partajat pe toată durata
 * de viață a procesului. Înregistrată în AndroidManifest ca `android:name`.
 */
class PortalApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
