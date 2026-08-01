package com.portalinstaller

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.portalinstaller.data.AppContainer
import com.portalinstaller.data.store.ThemeMode
import com.portalinstaller.ui.MainScreen
import com.portalinstaller.ui.theme.PortalTheme
import com.portalinstaller.util.AppLanguageManager

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        AppLanguageManager.applySavedLanguage(this)
        super.onCreate(savedInstanceState)
        val settingsStore = AppContainer.from(this).settingsStore
        setContent {
            val themeMode by settingsStore.themeMode.collectAsStateWithLifecycle(
                initialValue = ThemeMode.SYSTEM
            )
            val useDynamicColor by settingsStore.useDynamicColor.collectAsStateWithLifecycle(
                initialValue = false
            )
            PortalTheme(themeMode = themeMode, dynamicColor = useDynamicColor) {
                MainScreen()
            }
        }
    }
}
