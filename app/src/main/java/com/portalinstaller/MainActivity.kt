package com.portalinstaller

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.portalinstaller.data.AppContainer
import com.portalinstaller.data.store.ThemeMode
import com.portalinstaller.ui.controlcenter.ControlCenterScreen
import com.portalinstaller.ui.theme.PortalTheme
import com.portalinstaller.update.AppUpdater
import com.portalinstaller.util.AppLanguageManager

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        AppLanguageManager.applySavedLanguage(this)
        super.onCreate(savedInstanceState)
        // Remote control and mirroring remain usable until the user leaves this window.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        com.portalinstaller.ui.controlcenter.RemoteLaunch.accept(intent)
        if (savedInstanceState == null) {
            AppUpdater.onAppOpened(this)
            acceptUpdateIntent(intent)
        }
        val settingsStore = AppContainer.from(this).settingsStore
        setContent {
            val themeMode by settingsStore.themeMode.collectAsStateWithLifecycle(
                initialValue = ThemeMode.SYSTEM
            )
            val useDynamicColor by settingsStore.useDynamicColor.collectAsStateWithLifecycle(
                initialValue = false
            )
            PortalTheme(themeMode = themeMode, dynamicColor = useDynamicColor) {
                ControlCenterScreen()
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        com.portalinstaller.ui.controlcenter.RemoteLaunch.accept(intent)
        acceptUpdateIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        AppUpdater.resumeIfAllowed(this)
    }

    /** The "Update" action on the update notification. */
    private fun acceptUpdateIntent(intent: android.content.Intent?) {
        if (intent?.action != AppUpdater.ACTION_UPDATE) return
        intent.action = null
        AppUpdater.start(this)
    }
}
