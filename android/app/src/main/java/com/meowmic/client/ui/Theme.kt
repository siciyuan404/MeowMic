package com.meowmic.client.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 紫色品牌色
private val BrandPurple = Color(0xFF6F6FFF)
private val BrandPurpleDark = Color(0xFF4B3FE3)
private val BrandPurpleLight = Color(0xFFA9AEFF)

private val DarkColors = darkColorScheme(
    primary = BrandPurpleLight,
    onPrimary = Color(0xFF1A1759),
    primaryContainer = BrandPurpleDark,
    onPrimaryContainer = Color.White,
    secondary = Color(0xFF6054F1),
    background = Color(0xFF111114),
    onBackground = Color(0xFFE5E5E5),
    surface = Color(0xFF1A1A1F),
    onSurface = Color(0xFFE5E5E5),
    surfaceVariant = Color(0xFF262629),
    onSurfaceVariant = Color(0xFFA1A1AA),
    outline = Color(0xFF3A3A40),
    error = Color(0xFFEF4444),
)

private val LightColors = lightColorScheme(
    primary = BrandPurpleDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5EAFF),
    onPrimaryContainer = Color(0xFF1A1759),
    secondary = BrandPurple,
    background = Color(0xFFF7F7F8),
    onBackground = Color(0xFF171717),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF171717),
    surfaceVariant = Color(0xFFEFEFF2),
    onSurfaceVariant = Color(0xFF52525B),
    outline = Color(0xFFD3D4DA),
    error = Color(0xFFDC2626),
)

@Composable
fun MeowMicTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
