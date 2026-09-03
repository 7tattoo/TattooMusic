package com.spotify.music.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ---- brand palette (ink / tattoo red) ----
val Ink = Color(0xFF141218)
val InkDeep = Color(0xFF0C0A10)
val InkPanel = Color(0xFF1E1A24)
val InkLine = Color(0xFF2C2735)
val TattooRed = Color(0xFFFF3B5C)
val TattooRedSoft = Color(0xFFFF6B85)
val AccentAmber = Color(0xFFFFB74D)
val AccentViolet = Color(0xFFB39DFF)

val TextPrimaryDark = Color(0xFFF2EEF7)
val TextSecondaryDark = Color(0xFFB4ADBE)
val TextPrimaryLight = Color(0xFF1A1820)
val TextSecondaryLight = Color(0xFF6B6474)
val SurfaceLight = Color(0xFFF7F6FA)
val SurfaceLineLight = Color(0xFFE9E6EF)

private val DarkColors = darkColorScheme(
    primary = TattooRed,
    onPrimary = Color.White,
    secondary = AccentViolet,
    onSecondary = Color.White,
    tertiary = AccentAmber,
    background = InkDeep,
    onBackground = TextPrimaryDark,
    surface = Ink,
    onSurface = TextPrimaryDark,
    surfaceVariant = InkPanel,
    onSurfaceVariant = TextSecondaryDark,
    outline = InkLine,
    error = TattooRedSoft
)

private val LightColors = lightColorScheme(
    primary = TattooRed,
    onPrimary = Color.White,
    secondary = AccentViolet,
    onSecondary = Color.White,
    tertiary = AccentAmber,
    background = SurfaceLight,
    onBackground = TextPrimaryLight,
    surface = Color.White,
    onSurface = TextPrimaryLight,
    surfaceVariant = SurfaceLight,
    onSurfaceVariant = TextSecondaryLight,
    outline = SurfaceLineLight,
    error = TattooRed
)

@Composable
fun TattooTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val scheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = scheme,
        typography = TattooType,
        content = content
    )
}