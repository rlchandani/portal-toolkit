package com.portalinstaller

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import com.portalinstaller.ui.MainScreen
import com.portalinstaller.ui.theme.PortalTheme
import com.portalinstaller.util.AppLanguageManager

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        AppLanguageManager.applySavedLanguage(this)
        super.onCreate(savedInstanceState)
        setContent {
            PortalTheme {
                MainScreen()
            }
        }
    }
}
