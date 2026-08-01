package com.portalinstaller.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Paleta aplicației: teal pe fundal cald.
internal val LightColors = lightColorScheme(
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

internal val DarkColors = darkColorScheme(
    primary = Color(0xFF4FD8D4),
    onPrimary = Color(0xFF00302F),
    primaryContainer = Color(0xFF00504E),
    onPrimaryContainer = Color(0xFFB2DFDB),
    secondary = Color(0xFFB1CCC9),
    secondaryContainer = Color(0xFF324B49),
    background = Color(0xFF131615),
    surface = Color(0xFF1E2321),
    onBackground = Color(0xFFE3E1DE),
    onSurface = Color(0xFFE3E1DE),
    surfaceVariant = Color(0xFF2E3532),
    outline = Color(0xFF889390)
)
