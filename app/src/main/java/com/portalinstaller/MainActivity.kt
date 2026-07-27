package com.portalinstaller

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.portalinstaller.ui.MainScreen
import com.portalinstaller.util.AppLanguageManager

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        AppLanguageManager.applySavedLanguage(this)
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF006D6B),
                    onPrimary = Color.White,
                    primaryContainer = Color(0xFFD5EFEC),
                    onPrimaryContainer = Color(0xFF00201F),
                    secondary = Color(0xFF4A6361),
                    secondaryContainer = Color(0xFFCDE8E5),
                    background = Color(0xFFFFF9F5),
                    surface = Color(0xFFFFF9F5),
                    surfaceVariant = Color(0xFFECEDEA),
                    outline = Color(0xFF6F7977)
                )
            ) {
                MainScreen()
            }
        }
    }
}
