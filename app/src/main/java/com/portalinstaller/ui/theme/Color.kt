package com.portalinstaller.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Shared visual direction with the approved Portal interface.
internal val LightColors = lightColorScheme(
    primary = Color(0xFF176ABB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7E7F8),
    onPrimaryContainer = Color(0xFF182C39),
    secondary = Color(0xFF536674),
    secondaryContainer = Color(0xFFE5EBF2),
    background = Color(0xFFF2F3F7),
    surface = Color.White,
    onBackground = Color(0xFF182C39),
    onSurface = Color(0xFF182C39),
    surfaceVariant = Color(0xFFE5EBF2),
    outline = Color(0xFF536674),
    outlineVariant = Color(0xFFDCE3EB)
)

internal val DarkColors = darkColorScheme(
    primary = Color.White,
    onPrimary = Color(0xFF14161B),
    primaryContainer = Color(0xFF414141),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFFB8B8B8),
    secondaryContainer = Color(0xFF363636),
    background = Color(0xFF14161B),
    surface = Color(0xFF2B2B2B),
    onBackground = Color.White,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF363636),
    outline = Color(0xFFB8B8B8),
    outlineVariant = Color(0xFF484848),
    error = Color(0xFFFF747D)
)
