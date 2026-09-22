package com.wil.aibipilot.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// --- Paleta base (constantes publicas) ---
val AccentBlue = Color(0xFF3AA0FF)
val AccentBlueLight = Color(0xFF1B6FC2)
val StatusGreen = Color(0xFF19E3B1)
val StatusGreenLight = Color(0xFF0E8F6B)
val ErrorRed = Color(0xFFE34B3A)

// --- Oscuro ---
private val BgDark = Color(0xFF0E1621)
private val SurfaceDark = Color(0xFF152435)
private val SurfaceVariantDark = Color(0xFF1B2E42)
private val BorderDark = Color(0xFF2A3D55)
private val OnPrimaryDark = Color(0xFF08111C)
private val OnSecondaryDark = Color(0xFF06281E)
private val WarningYellow = Color(0xFFE3C419)
private val TextPrimaryDark = Color(0xFFF2F6FA)
private val TextSecondaryDark = Color(0xFF8B9BB4)

// --- Claro ---
private val BgLight = Color(0xFFF4F7FB)
private val SurfaceLight = Color(0xFFFFFFFF)
private val SurfaceVariantLight = Color(0xFFE8EEF5)
private val BorderLight = Color(0xFFD3DEE9)
private val OnPrimaryLight = Color(0xFFFFFFFF)
private val OnSecondaryLight = Color(0xFFFFFFFF)
private val WarningDark = Color(0xFF8F6D00)
private val TextPrimaryLight = Color(0xFF16202C)
private val TextSecondaryLight = Color(0xFF5A6B82)

// Warning / TextPrimary / TextSecondary no tienen rol M3: se exponen como
// Color publicos resueltos segun el tema activo (DESIGN.md §1.1).
private data class AibiColors(
    val warning: Color,
    val textPrimary: Color,
    val textSecondary: Color,
)

private val DarkAibiColors = AibiColors(WarningYellow, TextPrimaryDark, TextSecondaryDark)
private val LightAibiColors = AibiColors(WarningDark, TextPrimaryLight, TextSecondaryLight)

private val LocalAibiColors = staticCompositionLocalOf { DarkAibiColors }

val Warning: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalAibiColors.current.warning

val TextPrimary: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalAibiColors.current.textPrimary

val TextSecondary: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalAibiColors.current.textSecondary

private val dark = darkColorScheme(
    primary = AccentBlue,
    onPrimary = OnPrimaryDark,
    secondary = StatusGreen,
    onSecondary = OnSecondaryDark,
    error = ErrorRed,
    onError = Color.White,
    background = BgDark,
    onBackground = TextPrimaryDark,
    surface = SurfaceDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TextSecondaryDark,
    outline = BorderDark,
)

private val light = lightColorScheme(
    primary = AccentBlueLight,
    onPrimary = OnPrimaryLight,
    secondary = StatusGreenLight,
    onSecondary = OnSecondaryLight,
    error = ErrorRed,
    onError = Color.White,
    background = BgLight,
    onBackground = TextPrimaryLight,
    surface = SurfaceLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = TextSecondaryLight,
    outline = BorderLight,
)

@Composable
fun AibiPilotTheme(themeMode: String, content: @Composable () -> Unit) {
    val darkTheme = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    CompositionLocalProvider(
        LocalAibiColors provides if (darkTheme) DarkAibiColors else LightAibiColors
    ) {
        MaterialTheme(colorScheme = if (darkTheme) dark else light, content = content)
    }
}
